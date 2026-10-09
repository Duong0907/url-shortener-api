package com.urlshortener.link.controller;

import com.urlshortener.link.dto.CreateLinkRequest;
import com.urlshortener.link.dto.LinkResponse;
import com.urlshortener.link.service.LinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/links")
@RequiredArgsConstructor
@Tag(name = "Links")
public class LinkController {

    private final LinkService service;

    @Value("${app.base-url}")
    private final String baseUrl;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Shorten a URL")
    @ApiResponse(responseCode = "400", description = "Invalid URL, custom slug or expiry")
    public LinkResponse create(@Valid @RequestBody CreateLinkRequest body, HttpServletRequest request) {
        return LinkResponse.from(service.create(body.targetUrl(), request.getRemoteAddr()), baseUrl);
    }
}
