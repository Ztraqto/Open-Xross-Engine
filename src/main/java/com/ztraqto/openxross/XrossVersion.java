package com.ztraqto.openxross;

/** Build/runtime version information for OpenXrossEngine. */
public final class XrossVersion {
    public static final String VERSION = "1.4.1";
    public static final String SYSTEM_PLUGIN_API_MAJOR = "1";

    private XrossVersion() {
    }

    /**
     * Prefer the JAR implementation version when a published artifact provides it,
     * while keeping a stable source-tree fallback for local development.
     */
    public static String current() {
        Package pkg = XrossVersion.class.getPackage();
        String implementation = pkg == null ? null : pkg.getImplementationVersion();
        return implementation == null || implementation.isBlank() ? VERSION : implementation;
    }
}
