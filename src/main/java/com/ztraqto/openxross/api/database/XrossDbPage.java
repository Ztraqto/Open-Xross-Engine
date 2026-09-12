package com.ztraqto.openxross.api.database;

import java.util.List;

public record XrossDbPage(List<XrossDbRecord> records, String nextKey) {

    public XrossDbPage {
        records = records == null ? List.of() : List.copyOf(records);
    }
}
