package com.urlshortener.link.service;

import com.urlshortener.link.entity.Link;
import com.urlshortener.link.repository.LinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LinkService {

    static final int MAX_SLUG_ATTEMPTS = 3;

    private final LinkRepository links;
    private final SlugGenerator slugs;
    private final UrlValidator urls;

    // Not @Transactional on purpose: each saveAndFlush runs in its own transaction,
    // so a failed insert rolls back alone and the next attempt starts clean.
    public Link create(String targetUrl, String createdIp) {
        urls.validate(targetUrl);
        for (int attempt = 1; ; attempt++) {
            try {
                return links.saveAndFlush(
                    new Link(slugs.next(), targetUrl, null, false, null, createdIp)
                );
            } catch (DataIntegrityViolationException e) {
                if (!isSlugConflict(e) || attempt == MAX_SLUG_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    static boolean isSlugConflict(DataIntegrityViolationException e) {
        String message = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
        return message != null && message.contains("uk_links_slug");
    }
}
