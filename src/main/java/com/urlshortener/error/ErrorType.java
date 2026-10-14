package com.urlshortener.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorType {
    URL_INVALID("urn:problem:url-invalid", "Invalid URL", HttpStatus.BAD_REQUEST),
    LINK_NOT_FOUND("urn:problem:link-not-found", "Short link not found", HttpStatus.NOT_FOUND),
    VALIDATION_FAILED(
        "urn:problem:validation-failed",
        "Request validation failed",
        HttpStatus.BAD_REQUEST
    ),
    EMAIL_TAKEN("urn:problem:email-taken", "Email is already registered", HttpStatus.CONFLICT),
    INVALID_CREDENTIALS(
        "urn:problem:invalid-credentials",
        "Invalid email or password",
        HttpStatus.UNAUTHORIZED
    ),
    REFRESH_TOKEN_INVALID(
        "urn:problem:refresh-token-invalid",
        "Refresh token is invalid",
        HttpStatus.UNAUTHORIZED
    ),
    INTERNAL_ERROR(
        "urn:problem:internal-error",
        "Internal server error",
        HttpStatus.INTERNAL_SERVER_ERROR
    );

    private final String type;
    private final String title;
    private final HttpStatus status;
}
