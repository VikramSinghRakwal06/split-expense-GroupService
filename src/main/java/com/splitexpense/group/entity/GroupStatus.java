package com.splitexpense.group.entity;

/**
 * Whether a group still accepts new activity.
 *
 * <p>Persisted as a string via {@code @Enumerated(EnumType.STRING)} and mirrored by
 * {@code ck_groups_status}. Never persisted by ordinal: reordering the constants would
 * silently reinterpret every existing row.
 */
public enum GroupStatus {

    /** Normal state. Expenses and settlements may be applied. */
    ACTIVE,

    /**
     * Read-only. History and balances remain visible, but no new deltas are accepted.
     *
     * <p>Archiving is how a group ends. Deleting one would destroy the evidence for debts
     * that may still be outstanding between its members.
     */
    ARCHIVED;

    /**
     * @return whether balances in this group may currently change
     */
    public boolean permitsChanges() {
        return this == ACTIVE;
    }
}
