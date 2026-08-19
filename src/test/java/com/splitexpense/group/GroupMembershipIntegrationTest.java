package com.splitexpense.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.splitexpense.group.dto.request.ApplyBalancesRequest;
import com.splitexpense.group.dto.request.BalanceDeltaRequest;
import com.splitexpense.group.dto.request.CreateGroupRequest;
import com.splitexpense.group.dto.response.GroupResponse;
import com.splitexpense.group.entity.BalanceEntryReason;
import com.splitexpense.group.entity.GroupMemberRole;
import com.splitexpense.group.exception.DuplicateResourceException;
import com.splitexpense.group.exception.NotGroupOwnerException;
import com.splitexpense.group.exception.OutstandingBalanceException;
import com.splitexpense.group.exception.ResourceNotFoundException;
import com.splitexpense.group.repository.GroupMemberRepository;
import com.splitexpense.group.service.GroupService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration tests for group membership and the authorisation it carries.
 *
 * <p>This is the service's real broken-object-level-authorisation surface. The {@code /me}
 * endpoints are safe by construction — they read the caller from the token and expose no
 * parameter pointing elsewhere — but every {@code /{groupId}} endpoint names a resource, so
 * each one needs an explicit check and each check is worth a test.
 *
 * <p>The distinction these pin down: a caller outside a group gets a <strong>404</strong>,
 * not a 403, because confirming that a group id exists is already more than an outsider
 * should learn. A caller inside the group who merely lacks authority gets a
 * <strong>403</strong>, because its existence is not a secret from them.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "spring.cache.type=none")
class GroupMembershipIntegrationTest {

    // Non-generic in Testcontainers 2.x, unlike the 1.x class of the same name.
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired
    private GroupService groupService;
    @Autowired
    private GroupMemberRepository groupMemberRepository;

    private UUID owner;
    private UUID member;
    private UUID outsider;
    private UUID groupId;

    @BeforeEach
    void setUp() {
        owner = UUID.randomUUID();
        member = UUID.randomUUID();
        outsider = UUID.randomUUID();

        GroupResponse group = groupService.createGroup(
                owner, new CreateGroupRequest("Goa Trip", "Flights and hotel", "INR"));
        groupId = group.id();
        groupService.addMember(groupId, owner, member);
    }

    @Test
    @DisplayName("enrols the creator as the group's owner")
    void creatorBecomesOwner() {
        GroupResponse group = groupService.getGroup(groupId, owner);

        assertThat(group.createdBy()).isEqualTo(owner);
        assertThat(group.members())
                .filteredOn(m -> m.userId().equals(owner))
                .singleElement()
                .satisfies(m -> assertThat(m.role()).isEqualTo(GroupMemberRole.OWNER));
    }

    @Test
    @DisplayName("lists a group for its members and not for anybody else")
    void listsOnlyTheCallersOwnGroups() {
        assertThat(groupService.getGroupsForUser(owner)).extracting(GroupResponse::id)
                .containsExactly(groupId);
        assertThat(groupService.getGroupsForUser(member)).extracting(GroupResponse::id)
                .containsExactly(groupId);
        assertThat(groupService.getGroupsForUser(outsider)).isEmpty();
    }

    @Test
    @DisplayName("hides a group's existence from a non-member behind a 404")
    void nonMemberCannotReadTheGroup() {
        assertThatThrownBy(() -> groupService.getGroup(groupId, outsider))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThatThrownBy(() -> groupService.getBalances(groupId, outsider))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("reports a group that does not exist the same way as one you cannot see")
    void unknownGroupIsIndistinguishableFromAHiddenOne() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> groupService.getGroup(unknown, owner))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> groupService.getGroup(groupId, outsider))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("lets only the owner add a member")
    void onlyOwnerMayAddMembers() {
        UUID newcomer = UUID.randomUUID();

        assertThatThrownBy(() -> groupService.addMember(groupId, member, newcomer))
                .isInstanceOf(NotGroupOwnerException.class);

        // A non-member gets a 404 instead: they should not learn the group exists at all.
        assertThatThrownBy(() -> groupService.addMember(groupId, outsider, newcomer))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThatCode(() -> groupService.addMember(groupId, owner, newcomer))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refuses to add the same person twice")
    void rejectsDuplicateMember() {
        assertThatThrownBy(() -> groupService.addMember(groupId, owner, member))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    @DisplayName("re-adding the owner does not silently demote them")
    void reAddingOwnerDoesNotDemoteThem() {
        // The failure this guards is not the missing 409 above — it is what a merge would do
        // instead of an insert. GroupMember's constructor hard-codes MEMBER for newcomers, so
        // an UPDATE here would overwrite the owner's row and strip their authority, leaving a
        // group nobody can administer. See GroupMember's Javadoc.
        assertThatThrownBy(() -> groupService.addMember(groupId, owner, owner))
                .isInstanceOf(DuplicateResourceException.class);

        assertThat(groupService.getGroup(groupId, owner).members())
                .filteredOn(m -> m.userId().equals(owner))
                .singleElement()
                .satisfies(m -> assertThat(m.role())
                        .as("the owner must still be an OWNER")
                        .isEqualTo(GroupMemberRole.OWNER));

        // And they must still be able to administer the group.
        assertThatCode(() -> groupService.addMember(groupId, owner, UUID.randomUUID()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("lets a settled member leave, and lets the owner remove them")
    void settledMemberCanBeRemoved() {
        UUID newcomer = UUID.randomUUID();
        groupService.addMember(groupId, owner, newcomer);

        // Anyone may leave a group they are in.
        groupService.removeMember(groupId, member, member);
        assertThat(groupMemberRepository.existsByIdGroupIdAndIdUserId(groupId, member)).isFalse();

        // The owner may remove somebody else.
        groupService.removeMember(groupId, owner, newcomer);
        assertThat(groupMemberRepository.existsByIdGroupIdAndIdUserId(groupId, newcomer)).isFalse();
    }

    @Test
    @DisplayName("stops an ordinary member removing somebody else")
    void memberCannotRemoveAnother() {
        UUID newcomer = UUID.randomUUID();
        groupService.addMember(groupId, owner, newcomer);

        assertThatThrownBy(() -> groupService.removeMember(groupId, member, newcomer))
                .isInstanceOf(NotGroupOwnerException.class);
    }

    @Test
    @DisplayName("refuses to remove a member who still owes or is owed")
    void unsettledMemberCannotBeRemoved() {
        groupService.applyBalances(groupId, new ApplyBalancesRequest(
                UUID.randomUUID().toString(),
                BalanceEntryReason.EXPENSE,
                "Dinner",
                List.of(new BalanceDeltaRequest(member, owner, new BigDecimal("450.0000")))));

        // Removing them would leave a debt pointing at somebody the group no longer contains,
        // with no endpoint through which it could ever be settled.
        assertThatThrownBy(() -> groupService.removeMember(groupId, owner, member))
                .isInstanceOf(OutstandingBalanceException.class);

        // The creditor is equally stuck, from the other side of the same debt.
        assertThatThrownBy(() -> groupService.removeMember(groupId, owner, owner))
                .isInstanceOf(OutstandingBalanceException.class);

        // Settling up releases them.
        groupService.applyBalances(groupId, new ApplyBalancesRequest(
                UUID.randomUUID().toString(),
                BalanceEntryReason.SETTLEMENT,
                "Paid back",
                List.of(new BalanceDeltaRequest(owner, member, new BigDecimal("450.0000")))));

        assertThatCode(() -> groupService.removeMember(groupId, owner, member))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("hides a group's activity feed from a non-member")
    void nonMemberCannotReadTheFeed() {
        assertThatThrownBy(() -> groupService.getEntries(
                groupId, outsider, org.springframework.data.domain.PageRequest.of(0, 20)))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
