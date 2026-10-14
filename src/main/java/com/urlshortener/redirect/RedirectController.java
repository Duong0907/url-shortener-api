package com.urlshortener.redirect;

import com.urlshortener.link.service.SlugResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "Redirect")
public class RedirectController {

    private final SlugResolver resolver;

    // 302 + no-store, never 301: browsers cache a 301 forever, so a disabled link
    // would keep working.
    @GetMapping("/{slug:[A-Za-z0-9_-]{3,32}}")
    @Operation(summary = "Open a short link")
    @ApiResponse(responseCode = "302", description = "Redirect to the target URL")
    @ApiResponse(
        responseCode = "404",
        description = "Short link not found (urn:problem:link-not-found)",
        content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class),
            examples = @ExampleObject(
                value = """
                {
                  "type": "urn:problem:link-not-found",
                  "title": "Short link not found",
                  "status": 404,
                  "detail": "Short link not found"
                }"""
            )
        )
    )
    public ResponseEntity<Void> redirect(@PathVariable String slug) {
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create(resolver.resolve(slug)))
            .cacheControl(CacheControl.noStore())
            .build();
    }
}
