package com.ztraqto.openxross.api.certification;

import com.ztraqto.openxross.service.CertificationService;

/** Compatibility entry point for plugins that cannot obtain a service directly. */
public final class CertificationAccess {
    private static volatile CertificationService service;

    private CertificationAccess() { }

    public static boolean hasCertification(long userId, String programId) {
        CertificationService current = service;
        return current != null && current.hasCertification(userId, programId);
    }

    public static void install(CertificationService certificationService) { service = certificationService; }

    public static void clear(CertificationService certificationService) {
        if (service == certificationService) service = null;
    }
}
