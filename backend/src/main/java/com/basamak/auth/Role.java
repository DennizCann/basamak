package com.basamak.auth;

import java.util.EnumSet;
import java.util.Set;

public enum Role {
    MEMBER, AUTHOR, EDITOR, PUBLISHER, ADMIN;

    /** Content-team roles; only these may sign in to the CMS. */
    public static final Set<Role> STAFF = EnumSet.of(AUTHOR, EDITOR, PUBLISHER, ADMIN);
}
