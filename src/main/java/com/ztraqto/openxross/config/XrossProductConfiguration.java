package com.ztraqto.openxross.config;

import java.time.LocalDate;
import java.util.Objects;

/** Product identity supplied by the Bot application embedding OpenXrossEngine. */
public record XrossProductConfiguration(
        String displayName,
        String certificationProgramId,
        LocalDate privacyPolicyUpdatedAt,
        String homepageUrl,
        String privacyPolicyUrl,
        String termsUrl
) {
    public XrossProductConfiguration {
        displayName = normalizeOptional(displayName, "displayName");
        certificationProgramId = requireText(certificationProgramId, "certificationProgramId");
        privacyPolicyUpdatedAt = Objects.requireNonNull(privacyPolicyUpdatedAt, "privacyPolicyUpdatedAt");
        homepageUrl = normalizeUrl(homepageUrl, "homepageUrl");
        privacyPolicyUrl = normalizeUrl(privacyPolicyUrl, "privacyPolicyUrl");
        termsUrl = normalizeUrl(termsUrl, "termsUrl");
    }

    public static XrossProductConfiguration openXross() {
        return new XrossProductConfiguration(
                XrossLocalConfiguration.string("product.name", "XROSS_PRODUCT_NAME"),
                XrossLocalConfiguration.string(
                        "product.certificationProgramId",
                        "XROSS_CERTIFICATION_PROGRAM_ID",
                        "openxross-partner"
                ),
                parseDate(XrossLocalConfiguration.string(
                        "product.privacyPolicyUpdatedAt",
                        "XROSS_PRIVACY_POLICY_UPDATED_AT",
                        "2026-08-26"
                )),
                XrossLocalConfiguration.string("product.homepageUrl", "XROSS_HOMEPAGE_URL"),
                XrossLocalConfiguration.string("product.privacyPolicyUrl", "XROSS_PRIVACY_POLICY_URL"),
                XrossLocalConfiguration.string("product.termsUrl", "XROSS_TERMS_URL")
        );
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "product.privacyPolicyUpdatedAt must use ISO format YYYY-MM-DD.", exception);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        String normalized = value.trim();
        if (normalized.length() > 100) {
            throw new IllegalArgumentException(name + " must not exceed 100 characters.");
        }
        return normalized;
    }

    private static String normalizeOptional(String value, String name) {
        if (value == null || value.isBlank()) return null;
        return requireText(value, name);
    }

    private static String normalizeUrl(String value, String name) {
        String normalized = normalizeOptional(value, name);
        if (normalized == null) return null;
        java.net.URI uri = java.net.URI.create(normalized);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("product." + name + " must be an absolute HTTPS URL.");
        }
        return uri.toString();
    }
}
