package com.urlshortener.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.urlshortener.TestcontainersConfiguration;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

// Replays V1..V2, inserts an old-style user, then applies V3 in a scratch schema.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UserMigrationTest {

    static final String SCHEMA = "migration_check";
    static final String INSERT_USER =
        "INSERT INTO " +
        SCHEMA +
        ".users (id, email, password_hash) VALUES (gen_random_uuid(), ?, ?)";

    @Autowired
    DataSource dataSource;

    @AfterEach
    void dropScratchSchema() {
        new JdbcTemplate(dataSource).execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Test
    void usersCreatedBeforeV3AreVerifiedAfterIt() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        flyway("2").migrate();
        jdbc.update(INSERT_USER, "old@example.com", "hash");

        flyway("3").migrate();

        assertThat(verified(jdbc, "old@example.com")).isTrue();
    }

    @Test
    void usersInsertedAfterV3StartUnverified() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        flyway("3").migrate();

        jdbc.update(INSERT_USER, "new@example.com", "hash");

        assertThat(verified(jdbc, "new@example.com")).isFalse();
    }

    private static Boolean verified(JdbcTemplate jdbc, String email) {
        return jdbc.queryForObject(
            "SELECT email_verified FROM " + SCHEMA + ".users WHERE email = ?",
            Boolean.class,
            email
        );
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
            .dataSource(dataSource)
            .schemas(SCHEMA)
            .locations("classpath:db/migration")
            .target(target)
            .load();
    }
}
