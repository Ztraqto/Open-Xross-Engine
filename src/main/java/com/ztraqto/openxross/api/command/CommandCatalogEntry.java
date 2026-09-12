package com.ztraqto.openxross.api.command;

import java.util.List;

public record CommandCatalogEntry(
        String name,
        String description,
        String usage,
        String detail,
        List<String> examples,
        String pluginId,
        String pluginName,
        boolean contextMenu
) {

    public CommandCatalogEntry {
        examples = examples == null ? List.of() : List.copyOf(examples);
    }
}
