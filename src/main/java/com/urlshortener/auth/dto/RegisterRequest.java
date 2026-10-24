package com.urlshortener.auth.dto;

import com.urlshortener.auth.validation.MaxUtf8Bytes;
import com.urlshortener.auth.validation.PasswordsMatch;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@PasswordsMatch
public record RegisterRequest(
    @NotBlank @Email @Size(max = 254) String email,
    @NotBlank @Size(min = 8) @MaxUtf8Bytes(72) String password,
    String confirmPassword
) {}
