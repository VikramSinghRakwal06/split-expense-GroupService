package com.payflow.wallet.config;

import com.payflow.wallet.dto.response.WalletResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * Redis-backed caching for wallet balance reads.
 *
 * <h2>What is cached, and what deliberately is not</h2>
 *
 * <p>Only the read path. Balances are read far more often than they change — every app
 * launch shows one — and each read is an indexed single-row lookup that Redis can answer
 * without touching PostgreSQL at all.
 *
 * <p>Nothing on the <em>write</em> path consults the cache. A debit decides whether a user
 * can afford a payment, and that decision has to be made against the authoritative row
 * under the protection of its version column; see {@code WalletTransactionService}.
 *
 * <h2>Why the TTL is short</h2>
 *
 * <p>Thirty seconds by default. The cache is invalidated explicitly whenever money moves,
 * so the TTL is not the primary correctness mechanism — it is the backstop for the case
 * where an eviction is lost, most plausibly a Redis hiccup between the commit and the
 * evict. It caps how long any reader can trail the database at half a minute, while still
 * absorbing the overwhelming majority of repeat reads.
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    /** Holds {@code WalletResponse} keyed by wallet id. */
    public static final String WALLET_BALANCE_CACHE = "walletBalance";

    /**
     * Keeps Redis a performance dependency rather than an availability one.
     *
     * <p>Spring's default error handler rethrows, which on this service would be severe: a
     * {@code @CacheEvict} runs as part of {@code credit} and {@code debit}, so an
     * unreachable Redis would fail <em>money movement</em> — for a cache the write path does
     * not even read. Worse, the eviction fires after the transaction has already committed,
     * so the caller would receive an error describing a payment that in fact succeeded.
     *
     * <p>So cache faults are logged and swallowed. Degradation is graceful and bounded:
     * a failed read simply falls through to PostgreSQL, and a failed eviction leaves an
     * entry that the 30-second TTL will expire anyway. Warn level, because a service running
     * blind on its cache is worth noticing even though nothing is broken.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {

            @Override
            public void handleCacheGetError(RuntimeException ex, Cache cache, Object key) {
                log.warn("Cache read failed for {}[{}]; falling back to the database",
                        cache.getName(), key, ex);
            }

            @Override
            public void handleCachePutError(
                    RuntimeException ex, Cache cache, Object key, Object value) {
                log.warn("Cache write failed for {}[{}]; the value is simply not cached",
                        cache.getName(), key, ex);
            }

            @Override
            public void handleCacheEvictError(RuntimeException ex, Cache cache, Object key) {
                // The money already moved and the database is correct; this entry is stale
                // until the TTL expires it.
                log.warn("Cache eviction failed for {}[{}]; stale until the TTL expires",
                        cache.getName(), key, ex);
            }

            @Override
            public void handleCacheClearError(RuntimeException ex, Cache cache) {
                log.warn("Cache clear failed for {}", cache.getName(), ex);
            }
        };
    }

    /**
     * Registers the balance cache with a serialiser locked to {@link WalletResponse}.
     *
     * <p>Scoped to this one cache rather than published as a bare
     * {@code RedisCacheConfiguration} bean, which Boot would apply to <em>every</em> cache —
     * and the value serialiser below is correct only for a cache that holds exactly one
     * type.
     *
     * <p><strong>The value serialiser must be type-locked, and this is a money-correctness
     * requirement rather than a preference.</strong> A generic JSON serialiser writes no
     * type information unless default typing is switched on, so a
     * {@code "balance": 100.0000} comes back out of Redis as a {@code LinkedHashMap} whose
     * balance is a {@code Double} — binary floating point, the one representation this
     * service forbids everywhere else. Naming the target type means Jackson binds the JSON
     * number straight into the record's {@code BigDecimal} field, exactly as it does for an
     * incoming request body. Pinning the type also avoids enabling polymorphic default
     * typing, which deserialises whatever class the stored payload names.
     *
     * @param ttl how long an entry may survive without being re-read, from
     *            {@code payflow.cache.balance-ttl}
     * @return a customizer registering the balance cache
     */
    @Bean
    public RedisCacheManagerBuilderCustomizer walletCacheCustomizer(
            @Value("${payflow.cache.balance-ttl:30s}") Duration ttl) {

        RedisCacheConfiguration configuration = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)

                // A null would mean "this wallet does not exist", and caching that would
                // let a wallet created a moment later keep reporting 404 until the TTL
                // expired. Misses are cheap; a cached absence is not.
                .disableCachingNullValues()

                // Plain string keys, so an operator can read and delete them by hand during
                // an incident. The JDK default would write opaque serialised bytes.
                .serializeKeysWith(SerializationPair.fromSerializer(RedisSerializer.string()))

                // JSON rather than JDK serialisation: readable in redis-cli, portable across
                // services, and it does not break every time a DTO gains a field.
                // JacksonJsonRedisSerializer is the Jackson 3 implementation — Boot 4 moved
                // to tools.jackson, so the Jackson2-named variants are the legacy ones.
                .serializeValuesWith(SerializationPair.fromSerializer(
                        new JacksonJsonRedisSerializer<>(WalletResponse.class)));

        return builder -> builder.withCacheConfiguration(WALLET_BALANCE_CACHE, configuration);
    }
}
