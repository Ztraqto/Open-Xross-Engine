package com.ztraqto.openxross.api.plugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.LinkedHashSet;
import java.util.Set;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PluginMeta {
    private String id;
    private String name;
    private String version;
    private String main;
    private String description;
    private String[] authors;
    private Set<PluginPermission> permissions = new LinkedHashSet<>();

    public Set<PluginPermission> requestedPermissions() {
        return permissions == null ? Set.of() : Set.copyOf(permissions);
    }
}
