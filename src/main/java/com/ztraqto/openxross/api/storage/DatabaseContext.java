package com.ztraqto.openxross.api.storage;

import com.ztraqto.openxross.api.database.XrossDbKey;

/** Creates an isolated child namespace for a database owner. */
public final class DatabaseContext implements IDatabaseOwner {

    private final String contextId;

    public DatabaseContext(IDatabaseOwner parent, String subName) {
        if (parent == null) {
            throw new IllegalArgumentException("parent is required.");
        }
        XrossDbKey.validateName(subName, "subName");
        this.contextId = parent.getDatabaseContextId() + "_" + subName;
        XrossDbKey.validateName(contextId, "databaseContextId");
    }

    @Override
    public String getDatabaseContextId() {
        return contextId;
    }
}
