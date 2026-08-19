package com.splitexpense.group.controller;

import com.splitexpense.group.dto.request.AddMemberRequest;
import com.splitexpense.group.dto.request.ApplyBalancesRequest;
import com.splitexpense.group.dto.request.CreateGroupRequest;
import com.splitexpense.group.dto.response.ApplyBalancesResponse;
import com.splitexpense.group.dto.response.BalanceEntryResponse;
import com.splitexpense.group.dto.response.ErrorResponse;
import com.splitexpense.group.dto.response.GroupBalancesResponse;
import com.splitexpense.group.dto.response.GroupMemberResponse;
import com.splitexpense.group.dto.response.GroupResponse;
import com.splitexpense.group.security.AuthenticatedUser;
import com.splitexpense.group.service.GroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry point for groups, membership and balances.
 *
 * <p>Holds no business logic: each method resolves the caller, delegates to
 * {@link GroupService}, and returns a DTO. Entities are never returned — see
 * {@code GroupMapper} for why.
 *
 * <h2>Three families of endpoint</h2>
 *
 * <p>The {@code /me} endpoint belongs to the authenticated person and takes the account
 * identifier <strong>only</strong> from the verified JWT. It accepts no user id in the path,
 * the query string, or the body, so there is no parameter through which one user could list
 * another's groups.
 *
 * <p>The {@code /{groupId}} endpoints do name a resource, so they cannot rely on that
 * property. Every one of them checks the caller's membership before doing anything, and
 * reports a non-member a <strong>404 rather than a 403</strong> — confirming that a group id
 * exists is already more than somebody outside it should learn. This is the service's real
 * broken-object-level-authorisation surface and each check is covered by a test.
 *
 * <p>The {@code :apply} endpoint is internal, called by expense-service, and names arbitrary
 * users in its body. It is restricted to {@code ROLE_ADMIN} in {@code SecurityConfig},
 * because a caller who may move any balance by id could otherwise clear their own debts.
 */
@RestController
@RequestMapping("/api/v1/groups")
@Tag(name = "Groups", description = "Groups, membership and the pairwise debt graph")
@SecurityRequirement(name = "bearerAuth")
public class GroupController {

    private final GroupService groupService;

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    /**
     * Creates a group owned by the authenticated user.
     *
     * @param caller  the verified caller
     * @param request name, optional description and optional currency
     * @return 201 with the new group
     */
    @PostMapping
    @Operation(summary = "Create a group owned by the authenticated user")
    @ApiResponse(responseCode = "201", description = "Group created")
    @ApiResponse(responseCode = "400", description = "Request failed validation",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<GroupResponse> createGroup(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @Valid @RequestBody CreateGroupRequest request) {

        GroupResponse group = groupService.createGroup(caller.id(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(group);
    }

    /**
     * Lists the groups the authenticated user belongs to.
     *
     * @param caller the verified caller
     * @return 200 with their groups, newest first
     */
    @GetMapping("/me")
    @Operation(summary = "List the authenticated user's groups")
    @ApiResponse(responseCode = "200", description = "The caller's groups")
    public ResponseEntity<List<GroupResponse>> myGroups(
            @AuthenticationPrincipal AuthenticatedUser caller) {

        return ResponseEntity.ok(groupService.getGroupsForUser(caller.id()));
    }

    /**
     * Reads one group the caller belongs to.
     *
     * @param caller  the verified caller
     * @param groupId group to read
     * @return 200 with the group and its members
     */
    @GetMapping("/{groupId}")
    @Operation(summary = "Read one group and its members")
    @ApiResponse(responseCode = "200", description = "The group")
    @ApiResponse(responseCode = "404",
            description = "No such group, or the caller is not a member",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<GroupResponse> getGroup(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PathVariable UUID groupId) {

        return ResponseEntity.ok(groupService.getGroup(groupId, caller.id()));
    }

    /**
     * Adds a user to a group. Owner only.
     *
     * @param caller  the verified caller
     * @param groupId group to add to
     * @param request the person to add
     * @return 201 with the new membership
     */
    @PostMapping("/{groupId}/members")
    @Operation(summary = "Add a user to a group",
            description = "Requires the caller to be the group's owner.")
    @ApiResponse(responseCode = "201", description = "Member added")
    @ApiResponse(responseCode = "403", description = "Caller is a member but not the owner",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404",
            description = "No such group, or the caller is not a member",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "That user is already a member",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<GroupMemberResponse> addMember(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PathVariable UUID groupId,
            @Valid @RequestBody AddMemberRequest request) {

        GroupMemberResponse member =
                groupService.addMember(groupId, caller.id(), request.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(member);
    }

    /**
     * Removes a member from a group, provided they have settled up.
     *
     * <p>Anyone may remove themselves; only the owner may remove somebody else.
     *
     * @param caller  the verified caller
     * @param groupId group to remove from
     * @param userId  the person to remove
     * @return 204
     */
    @DeleteMapping("/{groupId}/members/{userId}")
    @Operation(summary = "Remove a member from a group",
            description = "Anyone may leave; only the owner may remove another member. "
                    + "Refused while the member has unsettled balances.")
    @ApiResponse(responseCode = "204", description = "Member removed")
    @ApiResponse(responseCode = "403", description = "Caller may not remove this member",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No such group or membership",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "The member still owes or is owed",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<Void> removeMember(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PathVariable UUID groupId,
            @PathVariable UUID userId) {

        groupService.removeMember(groupId, caller.id(), userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Archives a group. Owner only, refused while any balance is unsettled.
     *
     * @param caller  the verified caller
     * @param groupId group to archive
     * @return 200 with the archived group
     */
    @PostMapping("/{groupId}:archive")
    @Operation(summary = "Archive a group",
            description = "Requires the caller to be the group's owner. Refused while any "
                    + "balance in the group is unsettled.")
    @ApiResponse(responseCode = "200", description = "Group archived")
    @ApiResponse(responseCode = "403", description = "Caller is a member but not the owner",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404",
            description = "No such group, or the caller is not a member",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409",
            description = "Group is already archived, or has unsettled balances",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<GroupResponse> archiveGroup(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PathVariable UUID groupId) {

        return ResponseEntity.ok(groupService.archiveGroup(groupId, caller.id()));
    }

    /**
     * Reactivates an archived group. Owner only.
     *
     * @param caller  the verified caller
     * @param groupId group to reactivate
     * @return 200 with the reactivated group
     */
    @PostMapping("/{groupId}:reactivate")
    @Operation(summary = "Reactivate an archived group",
            description = "Requires the caller to be the group's owner.")
    @ApiResponse(responseCode = "200", description = "Group reactivated")
    @ApiResponse(responseCode = "403", description = "Caller is a member but not the owner",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404",
            description = "No such group, or the caller is not a member",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Group is not archived",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<GroupResponse> reactivateGroup(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PathVariable UUID groupId) {

        return ResponseEntity.ok(groupService.reactivateGroup(groupId, caller.id()));
    }

    /**
     * Reads a group's outstanding debts and every member's net position.
     *
     * @param caller  the verified caller
     * @param groupId group to read
     * @return 200 with the debt graph
     */
    @GetMapping("/{groupId}/balances")
    @Operation(summary = "Read a group's outstanding debts and net positions")
    @ApiResponse(responseCode = "200", description = "The group's debt graph")
    @ApiResponse(responseCode = "404",
            description = "No such group, or the caller is not a member",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<GroupBalancesResponse> getBalances(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PathVariable UUID groupId) {

        return ResponseEntity.ok(groupService.getBalances(groupId, caller.id()));
    }

    /**
     * Returns one page of a group's activity feed, newest entry first.
     *
     * <p>{@code @PageableDefault} supplies the contract's defaults when the client sends no
     * paging parameters. The sort is stated here rather than left to the client so the query
     * keeps matching {@code idx_balance_entries_group_created}; a client may still override
     * it with {@code ?sort=}.
     *
     * @param caller   the verified caller
     * @param groupId  group whose history is being read
     * @param pageable paging and sort, defaulted to 20 per page by {@code createdAt} desc
     * @return 200 with a page of balance entries
     */
    @GetMapping("/{groupId}/entries")
    @Operation(summary = "Page through a group's activity feed")
    @ApiResponse(responseCode = "200", description = "A page of balance entries")
    @ApiResponse(responseCode = "404",
            description = "No such group, or the caller is not a member",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<Page<BalanceEntryResponse>> getEntries(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @PathVariable UUID groupId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        return ResponseEntity.ok(groupService.getEntries(groupId, caller.id(), pageable));
    }

    /**
     * INTERNAL. Applies a set of debts to a group on expense-service's instruction.
     *
     * <p>Mapped with a colon rather than a further path segment, because this is an action on
     * the balances collection and not a sub-resource of it — {@code POST /balances} would
     * imply creating one balance, which is not what happens.
     *
     * <p>A repeat of an already-applied reference is a {@code 200} with
     * {@code applied: false}, not an error: the caller's intent is already satisfied, and
     * that answer is precisely what makes a retry after a timeout safe.
     *
     * @param groupId group to apply to
     * @param request reference, reason and the deltas to apply
     * @return 200 with the outcome and the resulting balances
     */
    @PostMapping("/{groupId}/balances:apply")
    @Operation(summary = "INTERNAL: apply a set of debts to a group",
            description = "Called by expense-service. Requires ROLE_ADMIN. Atomic and "
                    + "idempotent by referenceId — a repeat returns applied=false and "
                    + "changes nothing.")
    @ApiResponse(responseCode = "200", description = "Applied, or already applied")
    @ApiResponse(responseCode = "403", description = "Caller is not an internal service",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "No such group",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409",
            description = "Group is archived, or too contended to apply",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422",
            description = "A delta names somebody who is not a member",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<ApplyBalancesResponse> applyBalances(
            @PathVariable UUID groupId,
            @Valid @RequestBody ApplyBalancesRequest request) {

        return ResponseEntity.ok(groupService.applyBalances(groupId, request));
    }
}
