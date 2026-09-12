package com.ztraqto.openxross.api.database;

import java.util.List;

public record XrossDbBatchResult(List<XrossDbRecord> written, List<XrossDbKey> deleted) {

    public XrossDbBatchResult {
        written = written == null ? List.of() : List.copyOf(written);
        deleted = deleted == null ? List.of() : List.copyOf(deleted);
    }
}
