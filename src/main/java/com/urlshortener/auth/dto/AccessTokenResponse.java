package com.urlshortener.auth.dto;

public record AccessTokenResponse(String accessToken, String tokenType, long expiresIn) {}
