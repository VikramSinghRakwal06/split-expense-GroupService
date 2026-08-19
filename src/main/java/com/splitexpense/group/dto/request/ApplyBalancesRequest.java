package com.splitexpense.group.dto.request;

import com.splitexpense.group.entity.BalanceEntryReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Payload for the internal endpoint
 * {@code POST /api/v1/groups/{groupId}/balances:apply}, called by expense-service.
 *
 * <p>One expense produces many debts at once — the payer is owed a share by every other
 * participant — and they must all take effect together or not at all. A partially applied
 * expense would leave the group's debt graph disagreeing with its own expense list, with
 * nothing to detect it. So the whole set travels in one request and commits in one
 * transaction.
 *
 * <p>This single-call shape is also what removes the compensation problem the transfer saga
 * had. Because {@link #referenceId} deduplicates the operation, a caller that never learned
 * the outcome can simply ask again; there is no intermediate state to be stranded in,
 * because there is only one mutation.
 *
 * @param referenceId the causing expense or settlement id, used as the idempotency key
 * @param reason      what kind of event this was, recorded on every resulting ledger entry
 * @param description optional human-readable note, likewise recorded on every entry
 * @param deltas      the debts to apply, at least one
 */
@Schema(description = "Internal request to apply a set of debts to a group, atomically")
public record ApplyBalancesRequest(

        /*
         * The idempotency key, and mandatory for that reason. A caller that omitted it
         * would have no safe way to retry: this service could not tell a genuine second
         * expense from a repeat of the first.
         */
        @Schema(example = "9f2c8b41-06de-4a35-8e7d-1b5c93a2704f",
                description = "Causing expense or settlement id; deduplicates the operation")
        @NotBlank(message = "Reference id is required")
        @Size(max = 100, message = "Reference id must not exceed 100 characters")
        String referenceId,

        @Schema(example = "EXPENSE")
        @NotNull(message = "Reason is required")
        BalanceEntryReason reason,

        @Schema(example = "Dinner at Toit")
        @Size(max = 255, message = "Description must not exceed 255 characters")
        String description,

        /*
         * @Valid is what makes the constraints on each delta actually run. Without it, bean
         * validation stops at this level and a list of malformed deltas would reach the
         * service unchecked.
         */
        @Schema(description = "The debts to apply; all of them commit together or none do")
        @NotEmpty(message = "At least one delta is required")
        @Size(max = 200, message = "An operation may carry at most 200 deltas")
        @Valid
        List<BalanceDeltaRequest> deltas) {
}
