package com.urlshortener.error;

public class ApiException extends RuntimeException {

    private final ErrorType errorType;

    public ApiException(ErrorType errorType, String detail) {
        super(detail);
        this.errorType = errorType;
    }

    public ErrorType getErrorType() {
        return errorType;
    }
}
