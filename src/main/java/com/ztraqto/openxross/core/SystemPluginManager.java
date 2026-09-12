package com.ztraqto.openxross.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ztraqto.openxross.core.security.SecureJson;
import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.api.system.SystemPluginContext;
import com.ztraqto.openxross.api.system.SystemPluginMeta;
import com.ztraqto.openxross.api.system.SystemProviderRegistry;
import com.ztraqto.openxross.api.system.XrossSystemPlugin;
import com.ztraqto.openxross.config.XrossSystemPluginConfiguration;
import com.ztraqto.openxross.core.plugin.PluginClassLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Bootstrap loader for privileged OpenXross system plugins.
 *
 * <p>System plugins are loaded before services and Discord and are not hot
 * reloaded. A restart is required to change them. Plugins may declare lifecycle
 * ordering dependencies through {@code system-plugin.json#requires}; the
 * dependency graph is validated before any plugin code executes.</p>
 */
public final class SystemPluginManager {

    private static final Logger logger = LoggerFactory.getLogger(SystemPluginManager.class);
    private static final long MAX_JAR_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_METADATA_BYTES = 64 * 1024;

    private final XrossEngine engine;
    private final XrossSystemPluginConfiguration configuration;
    private final ObjectMapper mapper = SecureJson.newMapper();
    private final SystemProviderRegistry providers = new SystemProviderRegistry();
    private final List<LoadedSystemPlugin> loaded = new ArrayList<>();

    public SystemPluginManager(XrossEngine engine, XrossSystemPluginConfiguration configuration) {
        this.engine = engine;
        this.configuration = configuration;
    }

    public synchronized void loadAll() throws Exception {
        if (!loaded.isEmpty() || providers.isSealed()) {
            throw new IllegalStateException("System plugins are already loaded.");
        }

        Path directory = configuration.directory();
        Files.createDirectories(directory);
        Set<String> trusted = loadTrustedFingerprints();

        File[] jars = directory.toFile().listFiles((ignored, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            providers.seal();
            logger.info("No external system plugins found in {}.", directory);
            return;
        }
        java.util.Arrays.sort(jars, Comparator.comparing(File::getName));

        Map<String, Candidate> candidates = discover(jars, trusted);
        for (Candidate candidate : resolveLoadOrder(candidates)) {
            loadOne(candidate);
        }
        providers.seal();
        logger.info("Loaded {} system plugin(s). Registered providers are now sealed.", loaded.size());
    }

    public synchronized void notifyEngineReady() {
        for (LoadedSystemPlugin entry : List.copyOf(loaded)) {
            try {
                entry.plugin().onEngineReady();
            } catch (Exception exception) {
                logger.error("System plugin {} failed in onEngineReady().", entry.meta().getId(), exception);
            }
        }
    }

    public synchronized void notifyEngineStopping() {
        List<LoadedSystemPlugin> reverse = new ArrayList<>(loaded);
        java.util.Collections.reverse(reverse);
        for (LoadedSystemPlugin entry : reverse) {
            try {
                entry.plugin().onEngineStopping();
            } catch (Exception exception) {
                logger.error("System plugin {} failed in onEngineStopping().", entry.meta().getId(), exception);
            }
        }
    }

    public synchronized void unloadAll() {
        List<LoadedSystemPlugin> reverse = new ArrayList<>(loaded);
        java.util.Collections.reverse(reverse);
        for (LoadedSystemPlugin entry : reverse) {
            try {
                entry.plugin().onUnload();
            } catch (Exception exception) {
                logger.error("System plugin {} failed in onUnload().", entry.meta().getId(), exception);
            }
            try {
                entry.loader().close();
            } catch (Exception exception) {
                logger.warn("Could not close system plugin classloader for {}.", entry.meta().getId(), exception);
            }
            try {
                Files.deleteIfExists(entry.snapshot());
            } catch (Exception exception) {
                logger.warn("Could not delete system plugin snapshot for {}.", entry.meta().getId(), exception);
            }
        }
        loaded.clear();
        providers.clear();
    }

    public SystemProviderRegistry providers() {
        return providers;
    }

    public synchronized List<LoadedSystemPluginInfo> getLoadedPlugins() {
        return loaded.stream()
                .map(entry -> new LoadedSystemPluginInfo(entry.meta(), entry.fingerprint(), entry.file()))
                .toList();
    }

    private Map<String, Candidate> discover(File[] jars, Set<String> trustedFingerprints) throws Exception {
        LinkedHashMap<String, Candidate> candidates = new LinkedHashMap<>();
        for (File jar : jars) {
            if (!isSafeJar(jar)) {
                throw new IllegalArgumentException("Unsafe or invalid system plugin JAR: " + jar);
            }
            String fingerprint = fingerprint(jar.toPath());
            SystemPluginMeta meta;
            Path inspected = PluginManager.createVerifiedSnapshot(jar, "system-inspection", fingerprint);
            try {
                meta = readMeta(inspected.toFile());
                validateMeta(meta, jar);
            } finally {
                Files.deleteIfExists(inspected);
            }
            if (configuration.requireTrustedFingerprints() && !trustedFingerprints.contains(fingerprint)) {
                throw new SecurityException("System plugin is not trusted by fingerprint: " + meta.getId()
                        + " (" + fingerprint + ")");
            }
            if (!configuration.requireTrustedFingerprints()) {
                logger.warn("Privileged system-plugin fingerprint enforcement is disabled; {} will be loaded as trusted code.", meta.getId());
            }
            Candidate previous = candidates.putIfAbsent(meta.getId(), new Candidate(meta, fingerprint, jar.getAbsoluteFile()));
            if (previous != null) {
                throw new IllegalStateException("Duplicate system plugin id: " + meta.getId());
            }
        }

        for (Candidate candidate : candidates.values()) {
            for (String dependency : candidate.meta().getRequires()) {
                if (!candidates.containsKey(dependency)) {
                    throw new IllegalStateException("System plugin '" + candidate.meta().getId()
                            + "' requires missing system plugin '" + dependency + "'.");
                }
                if (dependency.equals(candidate.meta().getId())) {
                    throw new IllegalStateException("System plugin cannot require itself: " + dependency);
                }
            }
        }
        return candidates;
    }

    /** Resolve deterministic dependency order before executing any plugin code. */
    private static List<Candidate> resolveLoadOrder(Map<String, Candidate> candidates) {
        ArrayList<Candidate> ordered = new ArrayList<>();
        Map<String, Visit> states = new LinkedHashMap<>();
        ArrayList<String> stack = new ArrayList<>();
        List<String> ids = candidates.keySet().stream().sorted().toList();
        for (String id : ids) {
            visit(id, candidates, states, stack, ordered);
        }
        return List.copyOf(ordered);
    }

    private static void visit(
            String id,
            Map<String, Candidate> candidates,
            Map<String, Visit> states,
            List<String> stack,
            List<Candidate> ordered
    ) {
        Visit state = states.get(id);
        if (state == Visit.DONE) return;
        if (state == Visit.VISITING) {
            int start = stack.indexOf(id);
            List<String> cycle = start >= 0 ? new ArrayList<>(stack.subList(start, stack.size())) : new ArrayList<>(stack);
            cycle.add(id);
            throw new IllegalStateException("System plugin dependency cycle: " + String.join(" -> ", cycle));
        }

        Candidate candidate = candidates.get(id);
        if (candidate == null) {
            throw new IllegalStateException("Missing system plugin dependency: " + id);
        }
        states.put(id, Visit.VISITING);
        stack.add(id);
        List<String> dependencies = java.util.Arrays.stream(candidate.meta().getRequires()).sorted().toList();
        for (String dependency : dependencies) {
            visit(dependency, candidates, states, stack, ordered);
        }
        stack.remove(stack.size() - 1);
        states.put(id, Visit.DONE);
        ordered.add(candidate);
    }

    private void loadOne(Candidate candidate) throws Exception {
        SystemPluginMeta meta = candidate.meta();
        String fingerprint = candidate.fingerprint();
        File jar = candidate.file();

        Path snapshot = PluginManager.createVerifiedSnapshot(jar, "system-" + meta.getId(), fingerprint);
        PluginClassLoader loader = new PluginClassLoader(
                new URL[]{snapshot.toUri().toURL()},
                getClass().getClassLoader()
        );
        SystemProviderRegistry.Snapshot providerCheckpoint = providers.checkpoint();
        XrossSystemPlugin plugin = null;
        boolean success = false;
        try {
            Class<?> mainClass = Class.forName(meta.getMain(), true, loader);
            if (!XrossSystemPlugin.class.isAssignableFrom(mainClass)) {
                throw new IllegalArgumentException("System plugin main does not extend XrossSystemPlugin: " + meta.getMain());
            }
            plugin = (XrossSystemPlugin) mainClass.getDeclaredConstructor().newInstance();
            plugin.init(new SystemPluginContext(engine, providers), meta);
            plugin.onLoad();
            plugin.registerProviders(providers);
            loaded.add(new LoadedSystemPlugin(plugin, meta, fingerprint, jar, snapshot, loader));
            success = true;
            logger.info("Loaded system plugin {} v{} [{}].", meta.getName(), meta.getVersion(), fingerprint.substring(0, 12));
        } finally {
            if (!success) {
                providers.restore(providerCheckpoint);
                if (plugin != null) {
                    try {
                        plugin.onUnload();
                    } catch (Exception ignored) {
                    }
                }
                try {
                    loader.close();
                } catch (Exception ignored) {
                }
                try {
                    Files.deleteIfExists(snapshot);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private Set<String> loadTrustedFingerprints() throws Exception {
        if (!configuration.requireTrustedFingerprints()) {
            return Set.of();
        }
        Path trustFile = configuration.trustFile();
        if (!Files.isRegularFile(trustFile)) {
            throw new SecurityException("System plugin fingerprint trust file is required but missing: " + trustFile);
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String line : Files.readAllLines(trustFile, StandardCharsets.UTF_8)) {
            String value = line.strip();
            if (value.isEmpty() || value.startsWith("#")) continue;
            String fingerprint = value.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
            if (!fingerprint.matches("[0-9a-f]{64}")) {
                throw new SecurityException("Invalid SHA-256 fingerprint in " + trustFile + ": " + value);
            }
            values.add(fingerprint);
        }
        return Set.copyOf(values);
    }

    private SystemPluginMeta readMeta(File jarFile) throws Exception {
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry entry = jar.getJarEntry("system-plugin.json");
            if (entry == null) {
                throw new IllegalArgumentException("system-plugin.json not found in " + jarFile.getName());
            }
            try (InputStream input = jar.getInputStream(entry)) {
                byte[] bytes = input.readNBytes(MAX_METADATA_BYTES + 1);
                if (bytes.length > MAX_METADATA_BYTES) {
                    throw new IllegalArgumentException("system-plugin.json exceeds " + MAX_METADATA_BYTES + " bytes.");
                }
                return mapper.readValue(bytes, SystemPluginMeta.class);
            }
        }
    }

    private static void validateMeta(SystemPluginMeta meta, File jar) {
        if (meta == null) throw new IllegalArgumentException("System plugin metadata missing: " + jar.getName());
        require(meta.getId(), "id");
        require(meta.getName(), "name");
        require(meta.getVersion(), "version");
        require(meta.getMain(), "main");
        meta.setId(normalizeId(meta.getId(), "id"));
        if (!meta.getMain().matches("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+")) {
            throw new IllegalArgumentException("System plugin main must be a fully-qualified Java class name.");
        }
        if (meta.getEngineApi() == null || !XrossSystemPlugin.API_MAJOR.equals(meta.getEngineApi().trim())) {
            throw new IllegalArgumentException("Unsupported system plugin engineApi: " + meta.getEngineApi()
                    + " (expected " + XrossSystemPlugin.API_MAJOR + ")");
        }
        String[] requires = meta.getRequires();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String dependency : requires) {
            String id = normalizeId(dependency, "requires");
            if (!normalized.add(id)) {
                throw new IllegalArgumentException("Duplicate system plugin dependency '" + id + "' in " + meta.getId());
            }
        }
        meta.setRequires(normalized.toArray(String[]::new));
    }

    private static String normalizeId(String value, String field) {
        require(value, field);
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Invalid system plugin " + field + ": " + value);
        }
        return normalized;
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("System plugin metadata field is required: " + field);
        }
    }

    private static boolean isSafeJar(File file) {
        if (file == null || !file.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) return false;
        Path path = file.toPath();
        return Files.isRegularFile(path) && !Files.isSymbolicLink(path) && file.length() > 0L && file.length() <= MAX_JAR_BYTES;
    }

    private static String fingerprint(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private enum Visit { VISITING, DONE }

    private record Candidate(SystemPluginMeta meta, String fingerprint, File file) {}

    private record LoadedSystemPlugin(
            XrossSystemPlugin plugin,
            SystemPluginMeta meta,
            String fingerprint,
            File file,
            Path snapshot,
            PluginClassLoader loader
    ) {}

    public record LoadedSystemPluginInfo(SystemPluginMeta meta, String fingerprint, File file) {}
}
