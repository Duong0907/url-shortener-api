package com.urlshortener.link.service;

import com.urlshortener.link.entity.Link;
import com.urlshortener.link.repository.LinkRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    // ownerId is null for anonymous callers.
    public Link create(String targetUrl, UUID ownerId, String createdIp) {
        urls.validate(targetUrl);
        for (int attempt = 1; ; attempt++) {
            try {
                return links.saveAndFlush(
                    new Link(slugs.next(), targetUrl, ownerId, false, null, createdIp)
                );
            } catch (DataIntegrityViolationException e) {
                if (!isSlugConflict(e) || attempt == MAX_SLUG_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    public Page<Link> listOwned(UUID ownerId, Pageable pageable) {
        return links.findByOwnerIdOrderByCreatedAtDesc(ownerId, pageable);
    }

    static boolean isSlugConflict(DataIntegrityViolationException e) {
        String message = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
        return message != null && message.contains("uk_links_slug");
    }
}
