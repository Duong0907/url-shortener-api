package com.urlshortener.link.service;

import com.urlshortener.error.ApiException;
import com.urlshortener.error.ErrorType;
import com.urlshortener.link.entity.Link;
import com.urlshortener.link.repository.LinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// The one place redirects read links. Add a cache here when redirect load needs it.
@Component
@RequiredArgsConstructor
public class SlugResolver {

    private final LinkRepository links;

    public String resolve(String slug) {
        return links
            .findBySlug(slug)
            .map(Link::getTargetUrl)
            .orElseThrow(() -> new ApiException(ErrorType.LINK_NOT_FOUND, "Short link not found"));
    }
}
