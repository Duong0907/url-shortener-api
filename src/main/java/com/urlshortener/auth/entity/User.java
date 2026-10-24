package com.urlshortener.auth.entity;

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

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    // SHA-256 hex of the emailed token. NULL when no verification is pending.
    @Column(name = "verification_token_hash", length = 64)
    private String verificationTokenHash;

    @Column(name = "verification_expires_at")
    private Instant verificationExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public User(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
    }

    public void startVerification(String tokenHash, Instant expiresAt) {
        this.verificationTokenHash = tokenHash;
        this.verificationExpiresAt = expiresAt;
    }

    public boolean hasUsableVerification(Instant now) {
        return (
            verificationTokenHash != null &&
            verificationExpiresAt != null &&
            verificationExpiresAt.isAfter(now)
        );
    }

    // Clearing the token makes it single-use.
    public void verifyEmail() {
        this.emailVerified = true;
        this.verificationTokenHash = null;
        this.verificationExpiresAt = null;
    }
}
