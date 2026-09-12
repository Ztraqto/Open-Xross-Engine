package com.ztraqto.openxross.api.certification;

/** Public definition of a user certification program. */
public record CertificationProgram(String id, String name, String description, String badgeLabel, String badgeIconUrl) {
    public CertificationProgram {
        if (id == null || !id.matches("[a-z][a-z0-9-]{1,62}")) throw new IllegalArgumentException("Invalid certification id.");
        if (name == null || name.isBlank() || name.length() > 100) throw new IllegalArgumentException("Invalid certification name.");
        description = description == null ? "" : description;
        badgeLabel = badgeLabel == null || badgeLabel.isBlank() ? name : badgeLabel;
        badgeIconUrl = badgeIconUrl == null ? "" : badgeIconUrl;
    }
}
