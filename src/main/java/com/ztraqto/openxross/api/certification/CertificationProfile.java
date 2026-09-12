package com.ztraqto.openxross.api.certification;

/** A user's membership profile in one certification program. */
public record CertificationProfile(String programId, long userId, String displayName, String detail, long grantedAt, long grantedBy) {
    public CertificationProfile {
        if (programId == null || !programId.matches("[a-z][a-z0-9-]{1,62}")) throw new IllegalArgumentException("Invalid certification id.");
        if (userId <= 0L) throw new IllegalArgumentException("User id must be positive.");
        displayName = displayName == null ? "" : displayName;
        detail = detail == null ? "" : detail;
    }
}
