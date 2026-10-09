package com.urlshortener.link.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateLinkRequest(@NotBlank String targetUrl) {
}
