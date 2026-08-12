package com.payflow.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.wallet.entity.Wallet;
import com.payflow.wallet.repository.LedgerEntryRepository;
import com.payflow.wallet.repository.WalletRepository;
import com.payflow.wallet.service.WalletService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves that concurrent credits against one wallet neither lose money nor lose evidence.
 *
 * <h2>The lost update problem, concretely</h2>
 *
 * <p>Two threads credit the same wallet at the same moment. Both read balance 0. Both
 * compute 0 + 1 = 1. Both write 1. Two credits happened, one unit of money exists, and the
 * ledger says otherwise — money has silently evaporated, with nothing in the logs to say
 * so. Nothing about that is exotic: it is the default behaviour of read-modify-write under
 * concurrency, and it is why {@code wallets.version} exists.
 *
 * <p>This test drives that race deliberately: 100 threads, all released at once by a
 * {@link CountDownLatch}, each crediting {@code "1.00"} to a wallet that starts at zero.
 * With the {@code @Version} column removed, the final balance comes out well under 100 and
 * this test fails — which is precisely what makes it worth having.
 *
 * <p>Runs against a real PostgreSQL through Testcontainers rather than an in-memory
 * database, because the guarantee under test is enforced by the database: the versioned
 * {@code UPDATE ... WHERE version = ?} matching zero rows is what raises the conflict.
 */
@Testcontainers
@SpringBootTest(properties = {
        // The retry budget is what makes contention survivable, and the production default
        // of 3 is sized for ordinary wallets that see one writer at a time. This test is a
        // deliberate worst case — 100 writers on one row, the workload optimistic locking
        // is worst at — so it runs at the setting a genuinely hot wallet would be deployed
        // with. Measured on this schema: 3 attempts loses ~55% of the credits to 409s, 20
        // passes consistently, and 40 is used here purely for margin on slower CI hardware,
        // since an unused attempt costs nothing. See WalletMovementService#maxAttempts.
        "payflow.wallet.movement-max-attempts=40",
        // Enough connections that threads are not merely queueing for the pool; real
        // contention on the row is the point of the exercise.
        "spring.datasource.hikari.maximum-pool-size=20",
        // No Redis here. Caching is irrelevant to what this test proves, and running it
        // without a Redis container would only exercise the cache error handler 100 times.
        // The cache has its own test, WalletCacheIntegrationTest.
        "spring.cache.type=none"
})
class WalletConcurrencyIntegrationTest {

    private static final int CONCURRENT_CREDITS = 100;

    /** Constructed from a String literal. Never {@code new BigDecimal(1.00)}. */
    private static final BigDecimal ONE_RUPEE = new BigDecimal("1.00");

    private static final BigDecimal EXPECTED_TOTAL = new BigDecimal("100.0000");

    @Container
    @SuppressWarnings("resource") // Lifecycle is managed by the Testcontainers extension.
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("payflow_wallet")
            .withUsername("payflow")
            .withPassword("payflow");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Pinned so the test does not depend on whichever profile a developer has active.
        registry.add("payflow.jwt.secret",
                () -> "integration-test-secret-key-of-more-than-32-bytes");
        registry.add("payflow.jwt.issuer", () -> "payflow-auth-service");
    }

    @Autowired private WalletService walletService;
    @Autowired private WalletRepository walletRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;

    @Test
    @DisplayName("100 concurrent credits of 1.00 leave exactly 100.0000 and exactly 100 entries")
    void concurrentCreditsNeitherLoseMoneyNorEvidence() throws InterruptedException {
        UUID walletId = walletService.createWallet(UUID.randomUUID()).id();

        // One latch to release every thread at the same instant, so the credits genuinely
        // overlap instead of trickling through one after another; one to wait for the lot.
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(CONCURRENT_CREDITS);

        AtomicInteger succeeded = new AtomicInteger();
        List<String> failures = new CopyOnWriteArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_CREDITS)) {
            for (int i = 0; i < CONCURRENT_CREDITS; i++) {
                String reference = "concurrent-credit-" + i;
                pool.submit(() -> {
                    try {
                        startGun.await();
                        walletService.credit(walletId, ONE_RUPEE, reference, "Load test");
                        succeeded.incrementAndGet();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        failures.add("interrupted");
                    } catch (RuntimeException ex) {
                        failures.add(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                    } finally {
                        finished.countDown();
                    }
                });
            }

            startGun.countDown();
            assertThat(finished.await(120, TimeUnit.SECONDS))
                    .as("all %d credits should finish within the timeout", CONCURRENT_CREDITS)
                    .isTrue();
        }

        assertThat(failures)
                .as("no credit should be rejected; a rejection means the retry budget ran out")
                .isEmpty();
        assertThat(succeeded).hasValue(CONCURRENT_CREDITS);

        Wallet wallet = walletRepository.findById(walletId).orElseThrow();

        // compareTo, never equals: the balance comes back from NUMERIC(19,4) at scale 4,
        // and equals() would call 100.0000 unequal to a literal 100.00 purely over scale.
        assertThat(wallet.getBalance())
                .as("every credit must be reflected exactly once in the balance")
                .usingComparator(BigDecimal::compareTo)
                .isEqualTo(EXPECTED_TOTAL);

        assertThat(ledgerEntryRepository.countByWalletId(walletId))
                .as("every credit must have left exactly one ledger entry")
                .isEqualTo(CONCURRENT_CREDITS);

        // The version proves the writes were genuinely serialised rather than merged: one
        // successful versioned UPDATE per credit, and no two threads sharing a version.
        assertThat(wallet.getVersion())
                .as("each credit should have advanced the optimistic-lock version once")
                .isEqualTo((long) CONCURRENT_CREDITS);
    }
}
