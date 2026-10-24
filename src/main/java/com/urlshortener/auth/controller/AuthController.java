package com.urlshortener.auth.controller;

import com.urlshortener.auth.dto.AccessTokenResponse;
import com.urlshortener.auth.dto.LoginRequest;
import com.urlshortener.auth.dto.RegisterRequest;
import com.urlshortener.auth.dto.ResendVerificationRequest;
import com.urlshortener.auth.dto.UserResponse;
import com.urlshortener.auth.dto.VerifyEmailRequest;
import com.urlshortener.auth.entity.User;
import com.urlshortener.auth.service.AuthService;
import com.urlshortener.error.ApiException;
import com.urlshortener.error.ErrorType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Auth")
public class AuthController {

    static final String REFRESH_COOKIE = "refresh_token";

    private final AuthService auth;

    @Value("${app.refresh-token.cookie-secure}")
    private final boolean secureCookie;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Create an account",
        description = "Sends a verification email. The account cannot log in until it is verified."
    )
    @ApiResponse(responseCode = "400", description = "Invalid email or password")
    @ApiResponse(responseCode = "409", description = "Email is already registered")
    public UserResponse register(@Valid @RequestBody RegisterRequest body) {
        User user = auth.register(body.email(), body.password());

        return new UserResponse(user.getId(), user.getEmail(), user.isEmailVerified());
    }

    @PostMapping("/login")
    @Operation(
        summary = "Log in",
        description = "Returns an access token and sets the refresh_token cookie."
    )
    @ApiResponse(responseCode = "200", description = "Access token; refresh_token cookie is set")
    @ApiResponse(responseCode = "401", description = "Invalid email or password")
    @ApiResponse(responseCode = "403", description = "Email is not verified")
    public ResponseEntity<AccessTokenResponse> login(@Valid @RequestBody LoginRequest body) {
        return withTokens(auth.login(body.email(), body.password()));
    }

    @PostMapping("/refresh")
    @Operation(
        summary = "Get a new access token",
        description = "Reads and rotates the refresh_token cookie."
    )
    @ApiResponse(
        responseCode = "200",
        description = "New access token; refresh_token cookie is rotated"
    )
    @ApiResponse(responseCode = "401", description = "Refresh token is missing, revoked or expired")
    public ResponseEntity<AccessTokenResponse> refresh(
        @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken
    ) {
        if (refreshToken == null) {
            throw new ApiException(ErrorType.REFRESH_TOKEN_INVALID, "Refresh token is missing");
        }
        return withTokens(auth.refresh(refreshToken));
    }

    @PostMapping("/logout")
    @Operation(
        summary = "Log out",
        description = "Revokes the refresh token and clears the cookie."
    )
    @ApiResponse(responseCode = "204", description = "Logged out")
    public ResponseEntity<Void> logout(
        @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken
    ) {
        if (refreshToken != null) {
            auth.logout(refreshToken);
        }
        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString())
            .build();
    }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        summary = "Verify an email address",
        description = "Consumes the token from the verification email. A token works once."
    )
    @ApiResponse(responseCode = "204", description = "Email verified")
    @ApiResponse(responseCode = "400", description = "Token is unknown, expired or already used")
    public void verifyEmail(@Valid @RequestBody VerifyEmailRequest body) {
        auth.verifyEmail(body.token());
    }

    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        summary = "Send a new verification email",
        description = "Always returns 204, so it does not reveal which emails are registered. " +
            "The new link replaces any earlier link."
    )
    @ApiResponse(responseCode = "204", description = "Accepted")
    @ApiResponse(responseCode = "400", description = "Malformed email")
    public void resendVerification(@Valid @RequestBody ResendVerificationRequest body) {
        auth.resendVerification(body.email());
    }

    private ResponseEntity<AccessTokenResponse> withTokens(AuthService.Tokens tokens) {
        return ResponseEntity.ok()
            .header(
                HttpHeaders.SET_COOKIE,
                refreshCookie(tokens.refreshToken(), auth.refreshTtl()).toString()
            )
            .body(
                new AccessTokenResponse(tokens.accessToken(), "Bearer", tokens.expiresInSeconds())
            );
    }

    // Path limits the cookie to /api/auth, so it never rides along on link or redirect requests.
    private ResponseCookie refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
            .httpOnly(true)
            .secure(secureCookie)
            .sameSite("Strict")
            .path("/api/auth")
            .maxAge(maxAge)
            .build();
    }
}
