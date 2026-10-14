package com.urlshortener.auth.repository;

import com.urlshortener.auth.entity.RefreshToken;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    // Row lock: two parallel refreshes with one token must not both rotate it.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByTokenHash(String tokenHash);
}
