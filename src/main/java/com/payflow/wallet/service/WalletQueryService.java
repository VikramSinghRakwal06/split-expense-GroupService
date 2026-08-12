package com.payflow.wallet.service;

import com.payflow.wallet.config.CacheConfig;
import com.payflow.wallet.dto.response.WalletResponse;
import com.payflow.wallet.exception.ResourceNotFoundException;
import com.payflow.wallet.mapper.WalletMapper;
import com.payflow.wallet.repository.WalletRepository;
import java.util.UUID;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cached balance reads, keyed by wallet id.
 *
 * <h2>Why this is its own bean</h2>
 *
 * <p>Same reason the retry and transaction boundaries are separate beans: {@code @Cacheable}
 * is applied by a proxy, and a proxy only intercepts calls arriving from outside the
 * object. If this method lived on {@code WalletService}, the {@code /me} path would reach
 * it as a plain {@code this} call and silently never cache anything — the annotation would
 * look correct in review and do nothing at runtime.
 *
 * @see CacheConfig for the TTL and serialisation, and for why writes never read the cache
 */
@Service
public class WalletQueryService {

    private final WalletRepository walletRepository;
    private final WalletMapper walletMapper;

    public WalletQueryService(WalletRepository walletRepository, WalletMapper walletMapper) {
        this.walletRepository = walletRepository;
        this.walletMapper = walletMapper;
    }

    /**
     * Reads a wallet and its balance, from Redis when possible.
     *
     * <p>Returns a DTO rather than the entity on purpose: a detached entity in a shared
     * cache would be a trap, since callers could mutate it and Hibernate would know nothing
     * about it. A record is immutable and safe to hand to every caller.
     *
     * @param walletId wallet to read
     * @return the wallet's current state
     * @throws ResourceNotFoundException if no such wallet exists; not cached, so a wallet
     *                                   created moments later is visible immediately
     */
    @Cacheable(cacheNames = CacheConfig.WALLET_BALANCE_CACHE, key = "#walletId")
    @Transactional(readOnly = true)
    public WalletResponse getWalletById(UUID walletId) {
        return walletRepository.findById(walletId)
                .map(walletMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found: " + walletId));
    }
}
