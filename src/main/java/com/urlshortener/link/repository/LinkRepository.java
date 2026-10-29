package com.urlshortener.link.repository;

import com.urlshortener.link.entity.Link;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LinkRepository extends JpaRepository<Link, UUID> {
    Optional<Link> findBySlug(String slug);

    // Both list queries are served by idx_links_owner_created.
    List<Link> findByOwnerIdOrderByCreatedAtDescIdDesc(UUID ownerId, Limit limit);

    // Keyset query: links strictly after the cursor in (createdAt, id) DESC order.
    @Query(
        """
        SELECT l FROM Link l
        WHERE l.ownerId = :ownerId
          AND (l.createdAt < :createdAt OR (l.createdAt = :createdAt AND l.id < :id))
        ORDER BY l.createdAt DESC, l.id DESC
        """
    )
    List<Link> findOwnedAfter(UUID ownerId, Instant createdAt, UUID id, Limit limit);
}
