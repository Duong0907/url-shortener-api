package com.urlshortener.link.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record LinkListResponse(
    List<LinkResponse> items,
    @Schema(
        description = "Pass as `cursor` to load the next links. Null when no more links exist",
        nullable = true
    )
    String nextCursor
) {}
