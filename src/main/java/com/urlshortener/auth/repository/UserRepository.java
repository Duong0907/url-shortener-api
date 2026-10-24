package com.urlshortener.auth.repository;

import com.urlshortener.auth.entity.User;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);

    // Row lock: two parallel verifications with one token must not both succeed.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<User> findByVerificationTokenHash(String tokenHash);

    // One atomic UPDATE. It cannot undo a verification that commits at the same moment,
    // which a read-modify-save of the whole entity could.
    @Transactional
    @Modifying
    @Query(
        "update User u set u.verificationTokenHash = :tokenHash, " +
            "u.verificationExpiresAt = :expiresAt " +
            "where u.email = :email and u.emailVerified = false"
    )
    int replaceVerificationToken(
        @Param("email") String email,
        @Param("tokenHash") String tokenHash,
        @Param("expiresAt") Instant expiresAt
    );
}
