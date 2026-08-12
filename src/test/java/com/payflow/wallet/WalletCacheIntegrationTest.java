package com.payflow.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.wallet.config.CacheConfig;
import com.payflow.wallet.dto.response.WalletResponse;
import com.payflow.wallet.entity.Wallet;
import com.payflow.wallet.entity.WalletStatus;
import com.payflow.wallet.exception.InsufficientFundsException;
import com.payflow.wallet.repository.WalletRepository;
import com.payflow.wallet.service.WalletService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Exercises the Redis cache against a real Redis and a real PostgreSQL.
 *
 * <p>Worth running against the genuine article rather than a map-backed cache, because the
 * things most likely to be wrong are the ones a fake would hide. The serialiser is the
 * clearest example: an untyped JSON serialiser round-trips a {@code WalletResponse} into a
 * {@code LinkedHashMap} whose balance is a {@code Double}, silently turning money into
 * binary floating point. Only a real Redis catches that.
 *
 * <h2>Why these assertions poll</h2>
 *
 * <p>Cache reads and writes borrow different connections from the Lettuce pool, so a value
 * written a microsecond ago is not guaranteed to be visible to the very next read. Nothing
 * in the service depends on that being instantaneous — a read that misses simply falls
 * through to PostgreSQL and returns the same answer — so these tests do not depend on it
 * either. Asserting immediately would be asserting a guarantee Redis never offered, and
 * such a test fails roughly one run in three.
 */
@Testcontainers
@SpringBootTest
class WalletCacheIntegrationTest {

    /** Generous: it only ever elapses in full when an assertion is genuinely going to fail. */
    private static final Duration CACHE_VISIBILITY_TIMEOUT = Duration.ofSeconds(5);

    @Container
    @SuppressWarnings("resource") // Lifecycle is managed by the Testcontainers extension.
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("payflow_wallet")
            .withUsername("payflow")
            .withPassword("payflow");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("payflow.jwt.secret",
                () -> "integration-test-secret-key-of-more-than-32-bytes");
        registry.add("payflow.jwt.issuer", () -> "payflow-auth-service");
    }

    @Autowired private WalletService walletService;
    @Autowired private WalletRepository walletRepository;
    @Autowired private CacheManager cacheManager;
    @Autowired private StringRedisTemplate redisTemplate;

    private WalletResponse cachedEntry(UUID walletId) {
        return cacheManager.getCache(CacheConfig.WALLET_BALANCE_CACHE)
                .get(walletId, WalletResponse.class);
    }

    /** Reads until the condition holds or the timeout expires, then returns what it saw. */
    private static <T> T pollUntil(Supplier<T> read, Predicate<T> condition) {
        long deadline = System.nanoTime() + CACHE_VISIBILITY_TIMEOUT.toNanos();
        T value = read.get();
        while (!condition.test(value) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
            value = read.get();
        }
        return value;
    }

    private WalletResponse awaitCached(UUID walletId) {
        return pollUntil(() -> cachedEntry(walletId), Objects::nonNull);
    }

    private WalletResponse awaitEvicted(UUID walletId) {
        return pollUntil(() -> cachedEntry(walletId), Objects::isNull);
    }

    @Test
    @DisplayName("a balance read populates the cache, and a credit evicts it")
    void readPopulatesCacheAndMovementEvictsIt() {
        UUID userId = UUID.randomUUID();
        UUID walletId = walletService.createWallet(userId).id();
        walletService.credit(walletId, new BigDecimal("100.0000"), "seed", null);

        assertThat(awaitEvicted(walletId))
                .as("the seeding credit should have evicted, not populated, the cache")
                .isNull();

        assertThat(walletService.getWalletForUser(userId).balance())
                .isEqualByComparingTo(new BigDecimal("100.00"));

        WalletResponse cached = awaitCached(walletId);
        assertThat(cached).as("the read should have populated the cache").isNotNull();

        // The whole record must survive the JSON round trip, not just the balance — and the
        // balance must come back a BigDecimal rather than a Double.
        assertThat(cached.balance()).isInstanceOf(BigDecimal.class);
        assertThat(cached.balance()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(cached.id()).isEqualTo(walletId);
        assertThat(cached.userId()).isEqualTo(userId);
        assertThat(cached.currency()).isEqualTo("INR");
        assertThat(cached.status()).isEqualTo(WalletStatus.ACTIVE);
        assertThat(cached.createdAt()).isNotNull();

        walletService.credit(walletId, new BigDecimal("50.0000"), "second", null);

        assertThat(awaitEvicted(walletId))
                .as("a credit must evict the now-stale balance")
                .isNull();
        assertThat(walletService.getWalletForUser(userId).balance())
                .isEqualByComparingTo(new BigDecimal("150.00"));
    }

    @Test
    @DisplayName("the cache is keyed by wallet id and holds readable JSON with the configured TTL")
    void cacheKeyIsWalletIdAndValueIsJson() {
        UUID userId = UUID.randomUUID();
        UUID walletId = walletService.createWallet(userId).id();
        walletService.getWalletForUser(userId);

        String key = CacheConfig.WALLET_BALANCE_CACHE + "::" + walletId;
        assertThat(pollUntil(() -> redisTemplate.hasKey(key), Boolean::booleanValue))
                .as("key should be the plain, operator-readable %s", key)
                .isTrue();

        // JSON, not opaque JDK-serialised bytes: an operator can read this during an
        // incident, and a DTO gaining a field does not invalidate every existing entry.
        assertThat(redisTemplate.opsForValue().get(key))
                .contains("\"currency\"")
                .contains("INR");

        assertThat(redisTemplate.getExpire(key))
                .as("entries should carry the configured 30s TTL")
                .isBetween(1L, 30L);
    }

    @Test
    @DisplayName("a debit decides against the database, never against a cached balance")
    void debitIgnoresTheCache() {
        UUID userId = UUID.randomUUID();
        UUID walletId = walletService.createWallet(userId).id();
        walletService.credit(walletId, new BigDecimal("100.0000"), "seed", null);

        // Warm the cache, then move the real balance to zero behind its back. This is
        // exactly the situation a stale cache creates; the point is that a debit must not be
        // fooled by it. Writing through the repository deliberately skips the eviction.
        walletService.getWalletForUser(userId);
        assertThat(awaitCached(walletId)).isNotNull();

        Wallet wallet = walletRepository.findById(walletId).orElseThrow();
        wallet.setBalance(new BigDecimal("0.0000"));
        walletRepository.saveAndFlush(wallet);

        assertThat(cachedEntry(walletId))
                .as("the cache should still be holding the pre-change balance")
                .isNotNull()
                .extracting(WalletResponse::balance)
                .satisfies(balance -> assertThat((BigDecimal) balance)
                        .isEqualByComparingTo(new BigDecimal("100.00")));

        // Had the funds check consulted the cache it would have seen 100.00 and allowed this,
        // letting the wallet be spent twice over inside one TTL window.
        assertThatThrownBy(() ->
                walletService.debit(walletId, new BigDecimal("100.0000"), "pay-1", null))
                .isInstanceOf(InsufficientFundsException.class);
    }
}
