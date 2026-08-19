package com.splitexpense.group.entity;

/**
 * A member's authority within one group.
 *
 * <p>Distinct from the platform-wide role carried in the JWT: that says whether the caller
 * is an ordinary user or an internal service, this says what they may do inside a
 * particular group. A user is an {@code OWNER} of the groups they created and a
 * {@code MEMBER} of the rest.
 *
 * <p>Persisted as a string and mirrored by {@code ck_group_members_role}.
 */
public enum GroupMemberRole {

    /** Created the group. May add and remove members. */
    OWNER,

    /** May read the group and take part in its expenses. */
    MEMBER
}
