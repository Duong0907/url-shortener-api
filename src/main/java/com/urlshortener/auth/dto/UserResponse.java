package com.urlshortener.auth.dto;

import java.util.UUID;

public record UserResponse(UUID id, String email, boolean emailVerified) {}
