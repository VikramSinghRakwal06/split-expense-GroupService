package com.payflow.wallet.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Switches on the {@code @Retryable} / {@code @Recover} annotations that guard the money
 * movement paths against optimistic-lock contention.
 *
 * <p>{@code @EnableRetry} registers a bean post-processor that proxies any bean carrying
 * {@code @Retryable} methods. Without it those annotations are inert metadata and a lost
 * update becomes a 409 the first time two people pay from one wallet at once.
 *
 * @see com.payflow.wallet.service.WalletMovementService for the retry/transaction layering
 */
@Configuration
@EnableRetry
public class RetryConfig {
}
