package com.urlshortener.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.urlshortener.TestcontainersConfiguration;
import com.urlshortener.auth.entity.RefreshToken;
import com.urlshortener.auth.entity.User;
import com.urlshortener.auth.repository.RefreshTokenRepository;
import com.urlshortener.auth.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

// HTTP contract of /api/auth: status codes, bodies, cookies, validation.
// Business rules are tested in AuthServiceTest; races in AuthServiceConcurrencyTest.
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JavaMailSender mailSender;

    @Autowired
    JwtDecoder jwtDecoder;

    @Autowired
    UserRepository users;

    @Autowired
    RefreshTokenRepository refreshTokens;

    @BeforeEach
    void cleanDatabase() {
        refreshTokens.deleteAll();
        users.deleteAll();
    }

    // ---------- register ----------

    @Test
    void registersUserWithNormalizedEmail() throws Exception {
        register("Alice@Example.com", "correct-horse")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.email").value("alice@example.com"));
        assertThat(users.findByEmail("alice@example.com").orElseThrow().getPasswordHash())
            .isNotEqualTo("correct-horse")
            .startsWith("$2");
    }

    @Test
    void registerResponseHasIdEmailAndVerificationFlag() throws Exception {
        register("alice@example.com", "correct-horse")
            .andExpect(jsonPath("$.*", hasSize(3)))
            .andExpect(jsonPath("$.emailVerified").value(false))
            .andExpect(jsonPath("$.id").isNotEmpty())
            .andExpect(jsonPath("$.passwordHash").doesNotExist())
            .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void rejectsDuplicateEmailIgnoringCase() throws Exception {
        register("alice@example.com", "correct-horse").andExpect(status().isCreated());
        register("ALICE@example.com", "another-pass")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.type").value("urn:problem:email-taken"));
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void passwordLengthLimitsAre8To72() throws Exception {
        register("a7@example.com", "a".repeat(7)).andExpect(status().isBadRequest());
        register("a8@example.com", "a".repeat(8)).andExpect(status().isCreated());
        register("a72@example.com", "a".repeat(72)).andExpect(status().isCreated());
        register("a73@example.com", "a".repeat(73)).andExpect(status().isBadRequest());
    }

    @Test
    void shortPasswordReportsTheFieldAsProblemJson() throws Exception {
        register("alice@example.com", "short")
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").value("urn:problem:validation-failed"))
            .andExpect(jsonPath("$.errors[*].field", hasItem("password")));
    }

    @Test
    void rejectsInvalidEmailFormats() throws Exception {
        for (String email : new String[] {
            "not-an-email",
            "a@",
            "@example.com",
            "a b@example.com",
        }) {
            register(email, "correct-horse")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("email")));
        }
        assertThat(users.count()).isZero();
    }

    @Test
    void rejectsBlankEmailAndPassword() throws Exception {
        register("", "")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[*].field", hasItem("email")))
            .andExpect(jsonPath("$.errors[*].field", hasItem("password")));
    }

    @Test
    void rejectsMismatchedConfirmation() throws Exception {
        postJson(
            "/api/auth/register",
            registration("alice@example.com", "correct-horse", "correct-horsE")
        )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:problem:validation-failed"))
            .andExpect(jsonPath("$.errors[*].field", hasItem("confirmPassword")));
        assertThat(users.count()).isZero();
    }

    @Test
    void rejectsMissingConfirmation() throws Exception {
        postJson(
            "/api/auth/register",
            "{\"email\":\"alice@example.com\",\"password\":\"correct-horse\"}"
        )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[*].field", hasItem("confirmPassword")));
    }

    @Test
    void reportsMismatchTogetherWithOtherFieldErrors() throws Exception {
        postJson("/api/auth/register", registration("not-an-email", "correct-horse", "other-pass"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[*].field", hasItem("email")))
            .andExpect(jsonPath("$.errors[*].field", hasItem("confirmPassword")));
    }

    @Test
    void rejectsMissingFields() throws Exception {
        postJson("/api/auth/register", "{}").andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMalformedJsonAndMissingBody() throws Exception {
        postJson("/api/auth/register", "{not json").andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)).andExpect(
            status().isBadRequest()
        );
    }

    @Test
    void rejectsWrongContentType() throws Exception {
        mvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.TEXT_PLAIN)
                .content("email=a@example.com")
        ).andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void passwordLimitIs72BytesNotCharacters() throws Exception {
        // BCrypt reads 72 bytes. "é" is 2 bytes in UTF-8, so 36 chars fit and 37 do not.
        register("fits@example.com", "é".repeat(36)).andExpect(status().isCreated());
        register("long@example.com", "é".repeat(37))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:problem:validation-failed"))
            .andExpect(jsonPath("$.errors[*].field", hasItem("password")));
    }

    @Test
    void emojiPasswordsAreCountedInBytesToo() throws Exception {
        register("fits@example.com", "😀".repeat(18)).andExpect(status().isCreated());
        register("long@example.com", "😀".repeat(19)).andExpect(status().isBadRequest());
    }

    @Test
    void registerOnlyAcceptsPost() throws Exception {
        mvc.perform(get("/api/auth/register")).andExpect(status().isMethodNotAllowed());
    }

    // ---------- login ----------

    @Test
    void loginReturnsAccessTokenAndRefreshCookie() throws Exception {
        register("alice@example.com", "correct-horse");

        MvcResult result = login("alice@example.com", "correct-horse")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").value(900))
            .andReturn();

        String userId = users.findByEmail("alice@example.com").orElseThrow().getId().toString();
        assertThat(jwtDecoder.decode(accessToken(result)).getSubject()).isEqualTo(userId);
    }

    @Test
    void refreshCookieIsHardened() throws Exception {
        register("alice@example.com", "correct-horse");

        MvcResult result = login("alice@example.com", "correct-horse").andReturn();

        // Exactly one cookie: no JSESSIONID, the API is stateless.
        assertThat(result.getResponse().getHeaders("Set-Cookie")).hasSize(1);
        String cookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(cookie)
            .startsWith("refresh_token=")
            .contains("HttpOnly")
            .contains("Secure")
            .contains("SameSite=Strict")
            .contains("Path=/api/auth")
            .contains("Max-Age=2592000");
    }

    @Test
    void tokenResponsesAreNotCacheable() throws Exception {
        register("alice@example.com", "correct-horse");

        login("alice@example.com", "correct-horse").andExpect(
            header().string("Cache-Control", containsString("no-store"))
        );
    }

    @Test
    void loginIgnoresEmailCase() throws Exception {
        register("alice@example.com", "correct-horse");

        login("ALICE@Example.COM", "correct-horse").andExpect(status().isOk());
    }

    @Test
    void loginPasswordIsCaseSensitive() throws Exception {
        register("alice@example.com", "correct-horse");

        login("alice@example.com", "CORRECT-HORSE").andExpect(status().isUnauthorized());
    }

    @Test
    void loginRejectsWrongPassword() throws Exception {
        register("alice@example.com", "correct-horse");
        login("alice@example.com", "wrong-horse")
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.type").value("urn:problem:invalid-credentials"))
            .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void wrongPasswordAndUnknownEmailLookIdentical() throws Exception {
        register("alice@example.com", "correct-horse");

        String wrongPassword = login("alice@example.com", "wrong-horse")
            .andReturn()
            .getResponse()
            .getContentAsString();
        String unknownEmail = login("nobody@example.com", "wrong-horse")
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(unknownEmail).isEqualTo(wrongPassword);
    }

    @Test
    void loginRejectsBlankFields() throws Exception {
        login("", "").andExpect(status().isBadRequest());
        login("alice@example.com", "").andExpect(status().isBadRequest());
    }

    @Test
    void loginOnlyAcceptsPost() throws Exception {
        mvc.perform(get("/api/auth/login")).andExpect(status().isMethodNotAllowed());
    }

    // ---------- refresh ----------

    @Test
    void refreshRotatesTokenAndRejectsReuse() throws Exception {
        register("alice@example.com", "correct-horse");
        Cookie first = loginCookie("alice@example.com", "correct-horse");

        MvcResult refreshed = mvc
            .perform(post("/api/auth/refresh").cookie(first))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andReturn();
        Cookie second = refreshed.getResponse().getCookie("refresh_token");
        assertThat(second.getValue()).isNotEqualTo(first.getValue());

        mvc.perform(post("/api/auth/refresh").cookie(first)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").cookie(second)).andExpect(status().isOk());
    }

    @Test
    void refreshReturnsAValidAccessTokenForTheSameUser() throws Exception {
        register("alice@example.com", "correct-horse");
        Cookie cookie = loginCookie("alice@example.com", "correct-horse");

        MvcResult refreshed = mvc
            .perform(post("/api/auth/refresh").cookie(cookie))
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").value(900))
            .andExpect(header().string("Set-Cookie", containsString("Max-Age=2592000")))
            .andReturn();

        String userId = users.findByEmail("alice@example.com").orElseThrow().getId().toString();
        assertThat(jwtDecoder.decode(accessToken(refreshed)).getSubject()).isEqualTo(userId);
    }

    @Test
    void refreshWithoutCookieIsUnauthorized() throws Exception {
        mvc.perform(post("/api/auth/refresh"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.type").value("urn:problem:refresh-token-invalid"));
    }

    @Test
    void refreshWithGarbageCookieIsUnauthorized() throws Exception {
        mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", "garbage")))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.type").value("urn:problem:refresh-token-invalid"));
        mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", ""))).andExpect(
            status().isUnauthorized()
        );
    }

    @Test
    void refreshWithExpiredTokenIsUnauthorized() throws Exception {
        register("alice@example.com", "correct-horse");
        UUID userId = users.findByEmail("alice@example.com").orElseThrow().getId();
        refreshTokens.save(
            new RefreshToken(userId, sha256("expired-token"), Instant.now().minusSeconds(1))
        );

        mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", "expired-token")))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.type").value("urn:problem:refresh-token-invalid"));
    }

    @Test
    void refreshAfterTheUserWasDeletedIsUnauthorized() throws Exception {
        register("alice@example.com", "correct-horse");
        Cookie cookie = loginCookie("alice@example.com", "correct-horse");

        users.deleteAll();

        mvc.perform(post("/api/auth/refresh").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshOnlyAcceptsPost() throws Exception {
        mvc.perform(get("/api/auth/refresh")).andExpect(status().isMethodNotAllowed());
    }

    // ---------- logout ----------

    @Test
    void logoutRevokesRefreshTokenAndClearsCookie() throws Exception {
        register("alice@example.com", "correct-horse");
        Cookie cookie = loginCookie("alice@example.com", "correct-horse");

        mvc.perform(post("/api/auth/logout").cookie(cookie))
            .andExpect(status().isNoContent())
            .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")))
            .andExpect(header().string("Set-Cookie", containsString("Path=/api/auth")));

        mvc.perform(post("/api/auth/refresh").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutWithoutCookieStillSucceedsAndClearsCookie() throws Exception {
        mvc.perform(post("/api/auth/logout"))
            .andExpect(status().isNoContent())
            .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
    }

    @Test
    void logoutWithUnknownCookieSucceeds() throws Exception {
        mvc.perform(
            post("/api/auth/logout").cookie(new Cookie("refresh_token", "garbage"))
        ).andExpect(status().isNoContent());
    }

    @Test
    void logoutTwiceSucceedsBothTimes() throws Exception {
        register("alice@example.com", "correct-horse");
        Cookie cookie = loginCookie("alice@example.com", "correct-horse");

        mvc.perform(post("/api/auth/logout").cookie(cookie)).andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/logout").cookie(cookie)).andExpect(status().isNoContent());
    }

    // ---------- several devices ----------

    @Test
    void eachLoginIsAnIndependentSession() throws Exception {
        register("alice@example.com", "correct-horse");
        Cookie phone = loginCookie("alice@example.com", "correct-horse");
        Cookie laptop = loginCookie("alice@example.com", "correct-horse");
        assertThat(phone.getValue()).isNotEqualTo(laptop.getValue());

        mvc.perform(post("/api/auth/logout").cookie(phone)).andExpect(status().isNoContent());

        mvc.perform(post("/api/auth/refresh").cookie(phone)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").cookie(laptop)).andExpect(status().isOk());
    }

    @Test
    void refreshingOneDeviceDoesNotAffectTheOther() throws Exception {
        register("alice@example.com", "correct-horse");
        Cookie phone = loginCookie("alice@example.com", "correct-horse");
        Cookie laptop = loginCookie("alice@example.com", "correct-horse");

        mvc.perform(post("/api/auth/refresh").cookie(phone)).andExpect(status().isOk());

        mvc.perform(post("/api/auth/refresh").cookie(laptop)).andExpect(status().isOk());
    }

    // ---------- email verification ----------

    @Test
    void registerSendsOneVerificationMailWithALink() throws Exception {
        register("alice@example.com", "correct-horse").andExpect(status().isCreated());

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("alice@example.com");
        assertThat(sent.getValue().getText()).contains("/verify-email?token=");
    }

    @Test
    void registerResponseNeverContainsTheToken() throws Exception {
        String body = register("alice@example.com", "correct-horse")
            .andReturn()
            .getResponse()
            .getContentAsString();

        assertThat(body).doesNotContain(mailedToken());
    }

    @Test
    void failedRegistrationSendsNoMail() throws Exception {
        register("alice@example.com", "correct-horse");
        Mockito.clearInvocations(mailSender);

        register("alice@example.com", "other-passw0rd").andExpect(status().isConflict());
        postJson(
            "/api/auth/register",
            registration("bad", "correct-horse", "correct-horse")
        ).andExpect(status().isBadRequest());

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void aMailOutageDoesNotFailRegistration() throws Exception {
        Mockito.doThrow(new MailSendException("down"))
            .when(mailSender)
            .send(any(SimpleMailMessage.class));

        register("alice@example.com", "correct-horse").andExpect(status().isCreated());

        assertThat(users.findByEmail("alice@example.com")).isPresent();
    }

    @Test
    void loginBeforeVerificationIsForbidden() throws Exception {
        register("alice@example.com", "correct-horse");

        postJson("/api/auth/login", credentials("alice@example.com", "correct-horse"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.type").value("urn:problem:email-not-verified"))
            .andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(refreshTokens.count()).isZero();
    }

    @Test
    void wrongPasswordBeforeVerificationIsStillUnauthorized() throws Exception {
        register("alice@example.com", "correct-horse");

        postJson("/api/auth/login", credentials("alice@example.com", "wrong-password"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.type").value("urn:problem:invalid-credentials"));
    }

    @Test
    void verifyingTheMailedTokenUnlocksLogin() throws Exception {
        register("alice@example.com", "correct-horse");

        verifyEmail(mailedToken()).andExpect(status().isNoContent());

        postJson("/api/auth/login", credentials("alice@example.com", "correct-horse"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void aTokenWorksOnlyOnce() throws Exception {
        register("alice@example.com", "correct-horse");
        String token = mailedToken();

        verifyEmail(token).andExpect(status().isNoContent());

        verifyEmail(token)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:problem:email-verification-token-invalid"));
    }

    @Test
    void unknownTokenIsRejected() throws Exception {
        verifyEmail("not-a-real-token")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:problem:email-verification-token-invalid"));
    }

    @Test
    void tokenIsCaseSensitive() throws Exception {
        register("alice@example.com", "correct-horse");
        String token = mailedToken();
        String flipped = token
            .chars()
            .mapToObj(c ->
                String.valueOf(
                    Character.isUpperCase(c)
                        ? Character.toLowerCase((char) c)
                        : Character.toUpperCase((char) c)
                )
            )
            .collect(Collectors.joining());

        verifyEmail(flipped).andExpect(status().isBadRequest());
    }

    @Test
    void expiredTokenIsRejectedAndTheUserStaysUnverified() throws Exception {
        register("alice@example.com", "correct-horse");
        String token = mailedToken();
        User user = users.findByEmail("alice@example.com").orElseThrow();
        user.startVerification(user.getVerificationTokenHash(), Instant.now().minusSeconds(1));
        users.save(user);

        verifyEmail(token)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:problem:email-verification-token-invalid"));
        assertThat(
            users.findByEmail("alice@example.com").orElseThrow().isEmailVerified()
        ).isFalse();
    }

    @Test
    void blankOrMissingTokenIsAValidationError() throws Exception {
        postJson("/api/auth/verify-email", "{\"token\":\"\"}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[*].field", hasItem("token")));
        postJson("/api/auth/verify-email", "{}").andExpect(status().isBadRequest());
    }

    @Test
    void verifyEmailRejectsGet() throws Exception {
        // A link scanner that prefetches the URL must not be able to consume a token.
        mvc.perform(get("/api/auth/verify-email")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void resendInvalidatesTheOldLink() throws Exception {
        register("alice@example.com", "correct-horse");
        String first = mailedToken();

        resend("alice@example.com").andExpect(status().isNoContent());
        String second = mailedToken();

        assertThat(second).isNotEqualTo(first);
        verifyEmail(first).andExpect(status().isBadRequest());
        verifyEmail(second).andExpect(status().isNoContent());
    }

    @Test
    void resendNormalizesTheEmail() throws Exception {
        register("alice@example.com", "correct-horse");
        Mockito.clearInvocations(mailSender);

        resend("ALICE@Example.com").andExpect(status().isNoContent());

        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    void resendForAnUnknownEmailLooksLikeSuccessAndSendsNothing() throws Exception {
        resend("nobody@example.com")
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void resendForAVerifiedEmailLooksLikeSuccessAndSendsNothing() throws Exception {
        register("alice@example.com", "correct-horse");
        verifyEmail(mailedToken()).andExpect(status().isNoContent());
        Mockito.clearInvocations(mailSender);

        resend("alice@example.com").andExpect(status().isNoContent());

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        assertThat(
            users.findByEmail("alice@example.com").orElseThrow().getVerificationTokenHash()
        ).isNull();
    }

    @Test
    void resendRejectsAMalformedEmail() throws Exception {
        resend("not-an-email")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors[*].field", hasItem("email")));
        postJson("/api/auth/resend-verification", "{}").andExpect(status().isBadRequest());
    }

    @Test
    void verificationEndpointsNeedNoAccessToken() throws Exception {
        // No Authorization header is sent, and neither call is answered with 401.
        resend("nobody@example.com").andExpect(status().isNoContent());
        verifyEmail("x").andExpect(status().isBadRequest());
    }

    // ---------- helpers ----------

    private ResultActions register(String email, String password) throws Exception {
        return postJson("/api/auth/register", registration(email, password, password));
    }

    private static String registration(String email, String password, String confirmPassword) {
        return (
            "{\"email\":\"" +
            email +
            "\",\"password\":\"" +
            password +
            "\",\"confirmPassword\":\"" +
            confirmPassword +
            "\"}"
        );
    }

    private ResultActions login(String email, String password) throws Exception {
        verifyInDatabase(email);

        return postJson("/api/auth/login", credentials(email, password));
    }

    private void verifyInDatabase(String email) {
        users.findByEmail(email.trim().toLowerCase()).ifPresent(user -> {
            user.verifyEmail();
            users.save(user);
        });
    }

    private static final Pattern TOKEN_IN_LINK = Pattern.compile("token=([A-Za-z0-9_-]+)");

    // The raw token from the most recent verification mail.
    private String mailedToken() {
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, atLeastOnce()).send(sent.capture());
        Matcher matcher = TOKEN_IN_LINK.matcher(sent.getValue().getText());
        assertThat(matcher.find()).isTrue();

        return matcher.group(1);
    }

    private ResultActions verifyEmail(String token) throws Exception {
        return postJson("/api/auth/verify-email", "{\"token\":\"" + token + "\"}");
    }

    private ResultActions resend(String email) throws Exception {
        return postJson("/api/auth/resend-verification", "{\"email\":\"" + email + "\"}");
    }

    private Cookie loginCookie(String email, String password) throws Exception {
        return login(email, password).andReturn().getResponse().getCookie("refresh_token");
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String credentials(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private static String accessToken(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private static String sha256(String token) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
            token.getBytes(StandardCharsets.UTF_8)
        );

        return HexFormat.of().formatHex(digest);
    }
}
