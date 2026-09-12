package com.ztraqto.openxross.api.system;

/** Marker for infrastructure providers registered by a system plugin. */
public interface XrossSystemProvider {
    String id();

    /** Higher values win when callers explicitly choose by priority. */
    default int priority() {
        return 0;
    }
}
