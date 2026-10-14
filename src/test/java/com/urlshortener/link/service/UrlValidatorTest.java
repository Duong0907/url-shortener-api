package com.urlshortener.link.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.urlshortener.error.ApiException;
import com.urlshortener.error.ErrorType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UrlValidatorTest {

    private final UrlValidator validator = new UrlValidator("https://sho.rt");

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com",
            "http://example.com/path?q=1#frag",
            "HTTPS://Example.com",
            "https://8.8.8.8/dns",
            "https://[2001:4860:4860::8888]/"
    })
    void acceptsPublicHttpUrls(String url) {
        assertThatCode(() -> validator.validate(url)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "not a url",
            "example.com",
            "javascript:alert(1)",
            "data:text/html,hi",
            "file:///etc/passwd",
            "ftp://example.com",
            "https://sho.rt/abc1234",
            "https://SHO.RT/abc1234",
            "http://localhost:8080",
            "http://api.localhost",
            "http://127.0.0.1",
            "http://10.1.2.3",
            "http://172.16.0.1",
            "http://192.168.1.1",
            "http://169.254.169.254/latest/meta-data",
            "http://0.0.0.0",
            "http://[::1]/",
            "http://[fd00::1]/",
            "http://999.1.1.1"
    })
    void rejectsUnsafeOrInvalidUrls(String url) {
        assertThatThrownBy(() -> validator.validate(url)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.URL_INVALID));
    }

    @org.junit.jupiter.api.Test
    void rejectsNull() {
        assertThatThrownBy(() -> validator.validate(null)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.URL_INVALID));
    }

    @org.junit.jupiter.api.Test
    void rejectsUrlsLongerThan2048Characters() {
        String url = "https://example.com/" + "a".repeat(UrlValidator.MAX_LENGTH);
        assertThatThrownBy(() -> validator.validate(url)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.URL_INVALID));
    }

    // URI parsing already rejects malformed hosts, so only a direct call reaches
    // the unparsable-literal branch.
    // An IPv6 literal never triggers a DNS lookup, so this test needs no network.
    @org.junit.jupiter.api.Test
    void treatsUnparsableIpLiteralAsPrivate() {
        assertThat(UrlValidator.isPrivateIpLiteral("[1:2:3]")).isTrue();
    }
}
