package com.urlshortener.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.urlshortener.TestcontainersConfiguration;
import com.urlshortener.auth.entity.RefreshToken;
import com.urlshortener.auth.entity.User;
import com.urlshortener.auth.repository.RefreshTokenRepository;
import com.urlshortener.auth.repository.UserRepository;
import com.urlshortener.auth.service.AuthService.Tokens;
import com.urlshortener.error.ApiException;
import com.urlshortener.error.ErrorType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

// Real threads against a real Postgres. These races are hard to reproduce by hand.
// Not @Transactional: every call must commit for real, like production requests.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AuthServiceConcurrencyTest {

    static final int THREADS = 8;

    @Autowired
    AuthService service;

    @Autowired
    UserRepository users;

    @Autowired
    RefreshTokenRepository refreshTokens;

    @BeforeEach
    void cleanDatabase() {
        refreshTokens.deleteAll();
        users.deleteAll();
    }

    @Test
    void parallelRefreshWithOneTokenSucceedsExactlyOnce() throws Exception {
        service.register("alice@example.com", "correct-horse");
        String token = service.login("alice@example.com", "correct-horse").refreshToken();

        List<Object> results = runInParallel(THREADS, () -> service.refresh(token));

        assertThat(results.stream().filter(r -> r instanceof Tokens)).hasSize(1);
        assertThat(results.stream().filter(r -> !(r instanceof Tokens)))
            .hasSize(THREADS - 1)
            .allSatisfy(r ->
                assertThat(((ApiException) r).getErrorType()).isEqualTo(
                    ErrorType.REFRESH_TOKEN_INVALID
                )
            );
        // The login token (now revoked) plus exactly one rotated token that is still live.
        List<RefreshToken> all = refreshTokens.findAll();
        assertThat(all).hasSize(2);
        assertThat(all.stream().filter(t -> t.getRevokedAt() == null)).hasSize(1);
    }

    @Test
    void parallelRegisterOfTheSameEmailSucceedsExactlyOnce() throws Exception {
        List<Object> results = runInParallel(4, () ->
            service.register("alice@example.com", "correct-horse")
        );

        assertThat(results.stream().filter(r -> r instanceof User)).hasSize(1);
        assertThat(results.stream().filter(r -> r instanceof ApiException))
            .hasSize(3)
            .allSatisfy(r ->
                assertThat(((ApiException) r).getErrorType()).isEqualTo(ErrorType.EMAIL_TAKEN)
            );
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void parallelRegisterTreatsDifferentSpellingsOfOneEmailAsTheSame() throws Exception {
        List<String> spellings = List.of(
            "alice@example.com",
            "ALICE@example.com",
            "  Alice@Example.com "
        );
        int[] next = { 0 };

        List<Object> results = runInParallel(spellings.size(), () -> {
            String email;
            synchronized (next) {
                email = spellings.get(next[0]++);
            }

            return service.register(email, "correct-horse");
        });

        assertThat(results.stream().filter(r -> r instanceof ApiException)).hasSize(2);
        assertThat(users.count()).isEqualTo(1);
    }

    // Starts all callables at the same moment and returns each result or thrown exception.
    private static List<Object> runInParallel(int count, Callable<Object> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(
                pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return action.call();
                    } catch (Throwable e) {
                        return e;
                    }
                })
            );
        }
        ready.await();
        go.countDown();

        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        return results;
    }
}
