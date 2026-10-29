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
    SLUG_TAKEN("urn:problem:slug-taken", "Slug is not available", HttpStatus.CONFLICT),
    LOGIN_REQUIRED("urn:problem:login-required", "Login is required", HttpStatus.UNAUTHORIZED),
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
    EMAIL_NOT_VERIFIED(
        "urn:problem:email-not-verified",
        "Email is not verified",
        HttpStatus.FORBIDDEN
    ),
    EMAIL_VERIFICATION_TOKEN_INVALID(
        "urn:problem:email-verification-token-invalid",
        "Verification token is invalid",
        HttpStatus.BAD_REQUEST
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
