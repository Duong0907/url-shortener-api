package com.urlshortener.link.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "links")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Link {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false, length = 32)
    private String slug;

    @Column(name = "target_url", nullable = false, columnDefinition = "text")
    @Setter
    private String targetUrl;

    @Column(name = "owner_id", updatable = false)
    private UUID ownerId;

    @Column(name = "is_custom", nullable = false, updatable = false)
    private boolean custom;

    @Column(nullable = false)
    @Setter
    private boolean active = true;

    @Column(name = "expires_at")
    @Setter
    private Instant expiresAt;

    @Column(name = "created_ip", updatable = false, length = 45)
    private String createdIp;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Link(
        String slug,
        String targetUrl,
        UUID ownerId,
        boolean custom,
        Instant expiresAt,
        String createdIp
    ) {
        this.slug = slug;
        this.targetUrl = targetUrl;
        this.ownerId = ownerId;
        this.custom = custom;
        this.expiresAt = expiresAt;
        this.createdIp = createdIp;
    }
}
