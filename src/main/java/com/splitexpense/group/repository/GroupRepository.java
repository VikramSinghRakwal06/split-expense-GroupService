package com.splitexpense.group.repository;

import com.splitexpense.group.entity.Group;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence access for {@link Group}.
 *
 * <p>Groups are mutable — renamed, archived — so the full {@code JpaRepository} surface is
 * appropriate here, unlike {@link BalanceEntryRepository} and
 * {@link AppliedOperationRepository}, which are deliberately narrowed.
 *
 * <p>Note what is <em>absent</em>: no "find all groups" query. Nothing in the product asks
 * that question, and a repository method that returns every group in the platform is one
 * accidental controller away from becoming an enumeration endpoint. Groups are reached
 * through membership, by {@link GroupMemberRepository}.
 */
@Repository
public interface GroupRepository extends JpaRepository<Group, UUID> {
}
