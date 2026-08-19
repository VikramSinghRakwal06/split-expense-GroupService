package com.splitexpense.group.repository;

import com.splitexpense.group.entity.GroupBalance;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Persistence access for the pairwise debt graph.
 *
 * <p>Note what is <em>absent</em>: no pessimistic-lock query. Concurrent applies touching
 * one pair are resolved by the {@code @Version} column and a retry, not by
 * {@code SELECT ... FOR UPDATE}. See {@code GroupTransactionService} for why.
 */
@Repository
public interface GroupBalanceRepository extends JpaRepository<GroupBalance, UUID> {

    /**
     * The row holding one pair's balance, if the two have ever transacted.
     *
     * <p>Both ids must already be in canonical order — the order
     * {@code com.splitexpense.group.domain.UserPair} produces, which is PostgreSQL's
     * {@code uuid} ordering and <em>not</em> {@code UUID.compareTo}'s. Passing them the
     * other way round finds nothing rather than failing, so callers must go through
     * {@code UserPair} rather than sorting the ids themselves.
     *
     * <p>Backed by {@code uq_group_balances_pair}, so this is an index lookup returning at
     * most one row.
     *
     * @param groupId  group the debt is scoped to
     * @param userLow  the smaller id under PostgreSQL's ordering
     * @param userHigh the larger
     * @return the balance row, or empty if the pair has never transacted in this group
     */
    Optional<GroupBalance> findByGroupIdAndUserLowAndUserHigh(
            UUID groupId, UUID userLow, UUID userHigh);

    /**
     * Every pair balance in a group. Backed by {@code idx_group_balances_group}.
     *
     * <p>Grows with the square of the membership in the worst case, but only pairs that
     * have actually transacted get a row — a group of ten people who always split evenly
     * has forty-five, which is a single small page.
     *
     * @param groupId group to read
     * @return its pair balances, including any that have settled back to zero
     */
    List<GroupBalance> findByGroupId(UUID groupId);

    /**
     * Every pair balance in a group that involves one particular member.
     *
     * <p>Used to decide whether a member may be removed: they may not, while they still owe
     * anyone or are owed by anyone. Written as an explicit query because the column the user
     * appears in depends on the canonical ordering, so neither
     * {@code findByUserLow} nor {@code findByUserHigh} answers the question alone.
     *
     * @param groupId group to check within
     * @param userId  member in question
     * @return the pair balances they appear in, from either side
     */
    @Query("""
            SELECT b FROM GroupBalance b
            WHERE b.groupId = :groupId AND (b.userLow = :userId OR b.userHigh = :userId)
            """)
    List<GroupBalance> findByGroupIdAndUser(
            @Param("groupId") UUID groupId, @Param("userId") UUID userId);
}
