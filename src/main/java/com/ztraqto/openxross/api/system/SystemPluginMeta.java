package com.ztraqto.openxross.api.system;

/** Metadata loaded from {@code system-plugin.json}. */
public class SystemPluginMeta {
    private String id;
    private String name;
    private String version;
    private String main;
    private String description;
    private String[] authors;
    private String engineApi = XrossSystemPlugin.API_MAJOR;
    private String[] requires;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getMain() { return main; }
    public void setMain(String main) { this.main = main; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String[] getAuthors() { return authors == null ? new String[0] : authors.clone(); }
    public void setAuthors(String[] authors) { this.authors = authors == null ? null : authors.clone(); }
    public String getEngineApi() { return engineApi; }
    public void setEngineApi(String engineApi) { this.engineApi = engineApi; }
    public String[] getRequires() { return requires == null ? new String[0] : requires.clone(); }
    public void setRequires(String[] requires) { this.requires = requires == null ? null : requires.clone(); }
}
