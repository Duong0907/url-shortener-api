package com.urlshortener.link.service;

import com.urlshortener.link.entity.Link;
import com.urlshortener.link.repository.LinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

// The one place redirects read links. Add a cache here when redirect load needs it.
@Component
@RequiredArgsConstructor
public class SlugResolver {

    private final LinkRepository links;

    public String resolve(String slug) {
        return links.findBySlug(slug)
                .map(Link::getTargetUrl)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Short link not found"));
    }
}
