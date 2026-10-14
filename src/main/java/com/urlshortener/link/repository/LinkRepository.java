package com.urlshortener.link.repository;

import com.urlshortener.link.entity.Link;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LinkRepository extends JpaRepository<Link, UUID> {
    Optional<Link> findBySlug(String slug);
}
