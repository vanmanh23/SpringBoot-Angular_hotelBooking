package com.stayhotel.auth.token;

public enum RevokeReason {
    ROTATED,
    LOGOUT,
    REUSE_DETECTED,
    PASSWORD_CHANGED,
    ACCOUNT_DISABLED
}
