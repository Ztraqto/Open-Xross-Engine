package com.ztraqto.openxross.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ztraqto.openxross.core.security.SecureJson;
import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.api.plugin.PluginMeta;
import com.ztraqto.openxross.api.plugin.PluginPermission;
import com.ztraqto.openxross.api.plugin.XrossPlugin;
import com.ztraqto.openxross.core.plugin.PluginClassLoader;
import com.ztraqto.openxross.service.CommandService;
import com.ztraqto.openxross.service.GuildSettingsService;
import com.ztraqto.openxross.service.UserSettingsService;
import com.ztraqto.openxross.service.LocaleService;
import com.ztraqto.openxross.service.PluginApprovalService;
import com.ztraqto.openxross.service.XrossConsoleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class PluginManager {

    private static final Logger logger = LoggerFactory.getLogger(PluginManager.class);
    private static final long RELOAD_DEBOUNCE_MILLIS = 1200L;
    private static final long MAX_PLUGIN_JAR_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_PLUGIN_METADATA_BYTES = 64 * 1024;

    private final XrossEngine engine;
    private final ObjectMapper jsonMapper = SecureJson.newMapper();
    private final PluginApprovalService approvalService;
    private final XrossConsoleService consoleService;
    private final Path pluginDirectory = Path.of("plugins").toAbsolutePath().normalize();
    private final Map<String, XrossPlugin> plugins = new ConcurrentHashMap<>();
    private final Map<String, PluginClassLoader> loaders = new ConcurrentHashMap<>();
    private final Map<String, String> loadedFingerprints = new ConcurrentHashMap<>();
    private final Map<String, File> loadedFiles = new ConcurrentHashMap<>();
    private final Map<String, Path> loadedSnapshots = new ConcurrentHashMap<>();
    private final Map<String, PendingPlugin> pendingPlugins = new ConcurrentHashMap<>();
    private final Map<Path, ScheduledFuture<?>> scheduledReloads = new ConcurrentHashMap<>();

    private volatile WatchService watchService;
    private volatile ScheduledExecutorService reloadExecutor;
    private volatile ScheduledExecutorService approvalSyncExecutor;
    private Thread watchThread;

    public PluginManager(XrossEngine engine) {
        this.engine = engine;
        this.approvalService = engine.getServiceManager().getService(PluginApprovalService.class);
        this.consoleService = engine.getServiceManager().getService(XrossConsoleService.class);
    }

    public synchronized void loadExternalPlugin(File jarFile) {
        if (!isPluginJar(jarFile)) {
            logger.warn("Invalid plugin file: {}", jarFile);
            return;
        }

        try {
            String fingerprint = calculateFingerprint(jarFile);
            PluginMeta meta;
            Path inspected = createVerifiedSnapshot(jarFile, "inspection", fingerprint);
            try {
                meta = loadMeta(inspected.toFile());
                validateMeta(meta, jarFile);
            } finally {
                Files.deleteIfExists(inspected);
            }
            PluginDescriptor descriptor = new PluginDescriptor(jarFile.getAbsoluteFile(), meta, fingerprint);

            if (fingerprint.equals(loadedFingerprints.get(meta.getId()))) {
                return;
            }
            PendingPlugin existingPending = pendingPlugins.get(meta.getId());
            if (existingPending != null && fingerprint.equals(existingPending.fingerprint())) {
                return;
            }
            PluginApprovalService.ApprovalResolution approval = approvalService.resolve(meta, fingerprint);
            if (approval == PluginApprovalService.ApprovalResolution.DENIED) {
                logger.warn("Plugin {} update {} was denied and will not be loaded.", meta.getId(), fingerprint);
                return;
            }
            if (approval == PluginApprovalService.ApprovalResolution.REQUIRES_APPROVAL) {
                PendingPlugin pending = new PendingPlugin(descriptor.jarFile(), meta, fingerprint);
                pendingPlugins.put(meta.getId(), pending);
                consoleService.requestPluginApproval(meta, fingerprint, descriptor.jarFile());
                logger.warn("Plugin {} is waiting for Bot administrator approval.", meta.getId());
                return;
            }

            pendingPlugins.remove(meta.getId());
            activateDescriptor(descriptor);
        } catch (Exception exception) {
            logger.error("Failed to inspect plugin: " + jarFile.getName(), exception);
        }
    }

    public synchronized boolean approvePending(String pluginId, String fingerprint, long administratorId) {
        PendingPlugin pending = findPending(pluginId, fingerprint);
        if (pending == null) {
            return false;
        }
        approvalService.approve(pending.meta(), pending.fingerprint(), administratorId);
        pendingPlugins.remove(pluginId, pending);
        activateDescriptor(new PluginDescriptor(pending.jarFile(), pending.meta(), pending.fingerprint()));
        return true;
    }

    public synchronized boolean denyPending(String pluginId, String fingerprint, long administratorId) {
        PendingPlugin pending = findPending(pluginId, fingerprint);
        if (pending == null) {
            return false;
        }
        approvalService.deny(pending.meta(), pending.fingerprint(), administratorId);
        pendingPlugins.remove(pluginId, pending);
        logger.warn("Plugin {} was denied by Bot administrator {}.", pluginId, administratorId);
        return true;
    }

    public synchronized boolean denyPending(String pluginId, long administratorId) {
        PendingPlugin pending = pendingPlugins.get(pluginId);
        return pending != null && denyPending(pluginId, pending.fingerprint(), administratorId);
    }

    /**
     * Bulk approval is intentionally disabled in 1.4.1. Each artifact must be
     * reviewed and approved using its individual opaque approval request.
     */
    @Deprecated(since = "1.4.1")
    public synchronized int approveAllPending(long administratorId) {
        throw new UnsupportedOperationException(
                "Bulk plugin approval is disabled. Review each plugin individually.");
    }

    public List<PendingPlugin> getPendingPlugins() {
        return pendingPlugins.values().stream()
                .sorted(Comparator.comparing(pending -> pending.meta().getId()))
                .toList();
    }

    public List<LoadedPlugin> getLoadedPlugins() {
        return plugins.entrySet().stream()
                .map(entry -> new LoadedPlugin(
                        entry.getValue().getMeta(),
                        loadedFingerprints.get(entry.getKey()),
                        loadedFiles.get(entry.getKey()),
                        entry.getValue().isEnabled()
                ))
                .sorted(Comparator.comparing(plugin -> plugin.meta().getId()))
                .toList();
    }

    public void notifyPendingApprovals() {
        getPendingPlugins().forEach(pending -> consoleService.requestPluginApproval(
                pending.meta(),
                pending.fingerprint(),
                pending.jarFile()
        ));
    }

    public boolean hasPermission(XrossPlugin plugin, PluginPermission permission) {
        if (plugin == null || permission == null || !plugin.getMeta().requestedPermissions().contains(permission)) {
            return false;
        }
        String fingerprint = loadedFingerprints.get(plugin.getMeta().getId());
        return fingerprint != null && approvalService.isApproved(plugin.getMeta().getId(), fingerprint);
    }

    public void requirePermission(XrossPlugin plugin, PluginPermission permission) {
        if (!hasPermission(plugin, permission)) {
            throw new SecurityException(
                    "Plugin " + plugin.getMeta().getId() + " does not have approved permission " + permission + "."
            );
        }
    }

    public synchronized void enablePlugin(String pluginId) {
        XrossPlugin plugin = plugins.get(pluginId);
        if (plugin == null || plugin.isEnabled()) {
            return;
        }

        try {
            LocaleService localeService = engine.getServiceManager().getService(LocaleService.class);
            if (localeService != null) {
                localeService.loadPluginLocales(plugin);
            }
            plugin.setEnabled(true);
            engine.getPluginBus().register(plugin);
            logger.info("Enabled plugin: {}", plugin.getMeta().getName());
        } catch (Exception exception) {
            logger.error("Error enabling plugin: " + pluginId, exception);
            engine.getPluginBus().unregister(plugin);
            if (plugin.isEnabled()) {
                try {
                    plugin.setEnabled(false);
                } catch (Exception disableException) {
                    logger.error("Error rolling back plugin enable: " + pluginId, disableException);
                }
            }
            cleanupPluginResources(plugin);
        }
    }

    public synchronized void disablePlugin(String pluginId) {
        XrossPlugin plugin = plugins.get(pluginId);
        if (plugin == null) {
            return;
        }

        engine.getPluginBus().unregister(plugin);
        if (plugin.isEnabled()) {
            try {
                plugin.setEnabled(false);
            } catch (Exception exception) {
                logger.error("Error disabling plugin: " + pluginId, exception);
            }
        }
        cleanupPluginResources(plugin);
        logger.info("Disabled plugin: {}", plugin.getMeta().getName());
    }

    public synchronized void unloadPlugin(String pluginId) {
        XrossPlugin plugin = plugins.get(pluginId);
        if (plugin == null) {
            return;
        }

        logger.info("Unloading plugin: {}", pluginId);
        disablePlugin(pluginId);
        plugins.remove(pluginId);
        loadedFingerprints.remove(pluginId);
        loadedFiles.remove(pluginId);
        try {
            plugin.onUnload();
        } catch (Exception exception) {
            logger.error("Error in onUnload: " + pluginId, exception);
        } finally {
            plugin.releaseManagedResources();
        }
        GuildSettingsService settingsService = engine.getServiceManager().getService(GuildSettingsService.class);
        if (settingsService != null) {
            settingsService.unregisterOwner(plugin.getMeta().getId());
        }
        UserSettingsService userSettingsService = engine.getServiceManager().getService(UserSettingsService.class);
        if (userSettingsService != null) {
            userSettingsService.unregisterOwner(plugin.getMeta().getId());
        }
        closeLoader(pluginId, loaders.remove(pluginId));
        deleteSnapshot(pluginId, loadedSnapshots.remove(pluginId));
    }

    public int scanDirectory() {
        File directory = pluginDirectory.toFile();
        if (!directory.exists() && !directory.mkdirs()) {
            logger.error("Could not create plugin directory: {}", directory.getAbsolutePath());
            return 0;
        }
        File[] files = directory.listFiles((ignored, name) -> name.toLowerCase().endsWith(".jar"));
        if (files == null) {
            return 0;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
        for (File file : files) {
            loadExternalPlugin(file);
        }
        return files.length;
    }

    public void loadAll() {
        scanDirectory();
        startApprovalSync();
    }

    public synchronized void startWatching() throws IOException {
        if (watchService != null) {
            return;
        }
        ensurePluginDirectory(pluginDirectory);
        WatchService watcher = FileSystems.getDefault().newWatchService();
        pluginDirectory.register(
                watcher,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY
        );
        watchService = watcher;
        reloadExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Xross-Plugin-Reload");
            thread.setDaemon(true);
            return thread;
        });
        watchThread = new Thread(() -> watchLoop(watcher), "Xross-Plugin-Watcher");
        watchThread.setDaemon(true);
        watchThread.start();
        logger.info("Watching external plugin directory: {}", pluginDirectory);
    }

    static void ensurePluginDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            Files.createDirectories(directory);
        }
    }

    public synchronized void stopWatching() {
        WatchService watcher = watchService;
        watchService = null;
        if (watcher != null) {
            try {
                watcher.close();
            } catch (IOException exception) {
                logger.warn("Could not close plugin directory watcher.", exception);
            }
        }
        if (watchThread != null) {
            watchThread.interrupt();
            watchThread = null;
        }
        scheduledReloads.values().forEach(future -> future.cancel(false));
        scheduledReloads.clear();
        if (reloadExecutor != null) {
            reloadExecutor.shutdownNow();
            reloadExecutor = null;
        }
    }

    public void unloadAll() {
        stopApprovalSync();
        stopWatching();
        for (String pluginId : List.copyOf(plugins.keySet())) {
            unloadPlugin(pluginId);
        }
        pendingPlugins.clear();
    }

    private void activateDescriptor(PluginDescriptor descriptor) {
        PluginDescriptor previous = currentDescriptor(descriptor.meta().getId());
        if (previous != null) {
            unloadPlugin(previous.meta().getId());
        }
        if (loadApprovedPlugin(descriptor)) {
            if (previous != null) {
                consoleService.sendNotice(
                        "プラグイン `" + descriptor.meta().getId() + "` を v"
                                + descriptor.meta().getVersion() + " へ更新しました。"
                );
            }
            return;
        }
        if (previous != null && previous.jarFile().isFile()) {
            logger.warn("Plugin update failed; restoring previous plugin {}.", previous.meta().getId());
            if (!loadApprovedPlugin(previous)) {
                logger.error("Failed to restore previous plugin {}.", previous.meta().getId());
            }
        }
    }


    /**
     * In a multi-machine cluster, a plugin approval can be performed on the
     * shard/node that owns the Xross console channel. Other nodes periodically
     * observe the shared approval ledger and activate the same approved artifact.
     */
    private synchronized void startApprovalSync() {
        if (approvalSyncExecutor != null) return;
        approvalSyncExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Xross-Plugin-Approval-Sync");
            thread.setDaemon(true);
            return thread;
        });
        approvalSyncExecutor.scheduleWithFixedDelay(this::refreshPendingApprovals, 2, 3, TimeUnit.SECONDS);
    }

    private synchronized void stopApprovalSync() {
        ScheduledExecutorService executor = approvalSyncExecutor;
        approvalSyncExecutor = null;
        if (executor != null) executor.shutdownNow();
    }

    private void refreshPendingApprovals() {
        for (PendingPlugin pending : getPendingPlugins()) {
            try {
                PluginApprovalService.ApprovalResolution resolution = approvalService.resolve(
                        pending.meta(), pending.fingerprint()
                );
                if (resolution == PluginApprovalService.ApprovalResolution.EXACT_APPROVAL) {
                    synchronized (this) {
                        if (pendingPlugins.remove(pending.meta().getId(), pending)) {
                            activateDescriptor(new PluginDescriptor(pending.jarFile(), pending.meta(), pending.fingerprint()));
                            logger.info("Plugin {} activated after shared cluster approval synchronization.", pending.meta().getId());
                        }
                    }
                } else if (resolution == PluginApprovalService.ApprovalResolution.DENIED) {
                    pendingPlugins.remove(pending.meta().getId(), pending);
                    logger.warn("Plugin {} was denied on another cluster node.", pending.meta().getId());
                }
            } catch (Exception exception) {
                logger.debug("Could not refresh shared plugin approval for {}.", pending.meta().getId(), exception);
            }
        }
    }

    private PluginDescriptor currentDescriptor(String pluginId) {
        XrossPlugin plugin = plugins.get(pluginId);
        File file = loadedFiles.get(pluginId);
        String fingerprint = loadedFingerprints.get(pluginId);
        if (plugin == null || file == null || fingerprint == null) {
            return null;
        }
        return new PluginDescriptor(file, plugin.getMeta(), fingerprint);
    }

    private boolean loadApprovedPlugin(PluginDescriptor descriptor) {
        PluginClassLoader loader = null;
        XrossPlugin plugin = null;
        Path snapshot = null;
        try {
            PluginMeta meta = descriptor.meta();
            snapshot = createVerifiedSnapshot(
                    descriptor.jarFile(),
                    descriptor.meta().getId(),
                    descriptor.fingerprint()
            );
            logger.info("Loading plugin: {} v{}", meta.getName(), meta.getVersion());
            loader = new PluginClassLoader(
                    new URL[]{snapshot.toUri().toURL()},
                    getClass().getClassLoader()
            );
            Class<?> mainClass = Class.forName(meta.getMain(), true, loader);
            if (!XrossPlugin.class.isAssignableFrom(mainClass)) {
                throw new IllegalArgumentException("Main class does not extend XrossPlugin: " + meta.getMain());
            }

            plugin = (XrossPlugin) mainClass.getDeclaredConstructor().newInstance();
            plugin.init(engine, meta);
            plugins.put(meta.getId(), plugin);
            loaders.put(meta.getId(), loader);
            loadedFingerprints.put(meta.getId(), descriptor.fingerprint());
            loadedFiles.put(meta.getId(), descriptor.jarFile());
            loadedSnapshots.put(meta.getId(), snapshot);
            snapshot = null;
            loader = null;
            plugin.onLoad();
            enablePlugin(meta.getId());
            if (!plugin.isEnabled()) {
                throw new IllegalStateException("Plugin did not enable successfully: " + meta.getId());
            }
            return true;
        } catch (Exception exception) {
            logger.error("Failed to load approved plugin: " + descriptor.jarFile().getName(), exception);
            cleanupFailedLoad(plugin, loader, descriptor.meta());
            deleteSnapshot(descriptor.meta().getId(), snapshot);
            return false;
        }
    }

    static Path createVerifiedSnapshot(File jarFile, String pluginId, String fingerprint) throws Exception {
        if (!isPluginJar(jarFile)) {
            throw new SecurityException("Plugin artifact is no longer a safe regular JAR: "
                    + jarFile.getName());
        }

        Path snapshot = Files.createTempFile("xross-plugin-" + pluginId + "-", ".jar");
        boolean complete = false;
        try (InputStream input = Files.newInputStream(jarFile.toPath());
             OutputStream output = Files.newOutputStream(snapshot)) {
            byte[] buffer = new byte[8192];
            long total = 0L;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_PLUGIN_JAR_BYTES) {
                    throw new SecurityException("Plugin JAR exceeds " + MAX_PLUGIN_JAR_BYTES + " bytes.");
                }
                output.write(buffer, 0, read);
            }
            complete = true;
        } finally {
            if (!complete) {
                Files.deleteIfExists(snapshot);
            }
        }

        try {
            String currentFingerprint = calculateFingerprint(snapshot.toFile());
            if (!MessageDigest.isEqual(
                    fingerprint.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    currentFingerprint.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                throw new SecurityException(
                        "Plugin artifact changed after inspection and must be approved again: "
                                + pluginId
                );
            }
            try (JarFile archive = new JarFile(snapshot.toFile())) {
                int manifestCount = 0;
                var entries = archive.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    // JarFile can locate a manifest case-insensitively. Do not let an
                    // alternate spelling or duplicate disagree with the class loader.
                    if ("META-INF/MANIFEST.MF".equalsIgnoreCase(entry.getName())) {
                        if (!"META-INF/MANIFEST.MF".equals(entry.getName()) || ++manifestCount > 1) {
                            throw new SecurityException("Plugin requires a single canonical META-INF/MANIFEST.MF entry.");
                        }
                    }
                    if ("META-INF/INDEX.LIST".equalsIgnoreCase(entry.getName())) {
                        throw new SecurityException("Plugin JAR indexes are forbidden because they can load unapproved archives.");
                    }
                }
                java.util.jar.Manifest manifest = null;
                var manifestEntry = archive.getJarEntry("META-INF/MANIFEST.MF");
                if (manifestEntry != null) {
                    try (InputStream input = archive.getInputStream(manifestEntry)) {
                        byte[] bytes = input.readNBytes(MAX_PLUGIN_METADATA_BYTES + 1);
                        if (bytes.length > MAX_PLUGIN_METADATA_BYTES) {
                            throw new SecurityException("Plugin manifest exceeds the metadata size limit.");
                        }
                        manifest = new java.util.jar.Manifest(new java.io.ByteArrayInputStream(bytes));
                    }
                }
                String classPath = manifest == null ? null
                        : manifest.getMainAttributes().getValue(java.util.jar.Attributes.Name.CLASS_PATH);
                if (classPath != null && !classPath.isBlank()) {
                    throw new SecurityException("Plugin manifest Class-Path is forbidden; package reviewed dependencies inside the approved JAR.");
                }
            }
            return snapshot;
        } catch (Exception exception) {
            Files.deleteIfExists(snapshot);
            throw exception;
        }
    }

    private PluginMeta loadMeta(File jarFile) throws Exception {
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry entry = jar.getJarEntry("plugin.json");
            if (entry == null) {
                throw new IllegalArgumentException("plugin.json not found in " + jarFile.getName());
            }
            try (InputStream input = jar.getInputStream(entry)) {
                byte[] metadata = input.readNBytes(MAX_PLUGIN_METADATA_BYTES + 1);
                if (metadata.length > MAX_PLUGIN_METADATA_BYTES) {
                    throw new IllegalArgumentException("plugin.json exceeds "
                            + MAX_PLUGIN_METADATA_BYTES + " bytes.");
                }
                return jsonMapper.readValue(metadata, PluginMeta.class);
            }
        }
    }

    private static String calculateFingerprint(File jarFile) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(jarFile.toPath())) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean isPluginJar(File file) {
        if (file == null || !file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
            return false;
        }
        Path path = file.toPath();
        long size = file.length();
        return Files.isRegularFile(path)
                && !Files.isSymbolicLink(path)
                && size > 0L
                && size <= MAX_PLUGIN_JAR_BYTES;
    }

    private static void validateMeta(PluginMeta meta, File jarFile) {
        if (meta == null) {
            throw new IllegalArgumentException("Plugin metadata is missing in " + jarFile.getName());
        }
        requireMetaValue(meta.getId(), "id");
        requireMetaValue(meta.getName(), "name");
        requireMetaValue(meta.getVersion(), "version");
        requireMetaValue(meta.getMain(), "main");
        if (!meta.getId().matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Plugin id must use lowercase letters, numbers, '.', '_' or '-'.");
        }
        if (meta.getName().length() > 100) {
            throw new IllegalArgumentException("Plugin name must not exceed 100 characters.");
        }
        if (meta.getVersion().length() > 64) {
            throw new IllegalArgumentException("Plugin version must not exceed 64 characters.");
        }
        if (meta.getMain().length() > 255
                || !meta.getMain().matches("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+")) {
            throw new IllegalArgumentException("Plugin main must be a valid fully-qualified Java class name.");
        }
    }

    private static void requireMetaValue(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Plugin metadata field '" + name + "' is required.");
        }
    }

    private PendingPlugin findPending(String pluginId, String fingerprint) {
        PendingPlugin pending = pendingPlugins.get(pluginId);
        if (pending == null || fingerprint == null || !pending.fingerprint().equals(fingerprint)) {
            return null;
        }
        return pending;
    }

    private void cleanupPluginResources(XrossPlugin plugin) {
        CommandService commandService = engine.getServiceManager().getService(CommandService.class);
        if (commandService != null) {
            commandService.unregisterAll(plugin);
        }
        LocaleService localeService = engine.getServiceManager().getService(LocaleService.class);
        if (localeService != null) {
            localeService.unloadPluginLocales(plugin.getMeta().getId());
        }
        plugin.releaseManagedResources();
    }

    private void cleanupFailedLoad(XrossPlugin plugin, PluginClassLoader loader, PluginMeta meta) {
        Path failedSnapshot = null;
        if (plugin != null) {
            try {
                cleanupPluginResources(plugin);
                plugin.onUnload();
            } catch (Exception cleanupException) {
                logger.error("Failed to clean up plugin after load failure", cleanupException);
            }
        }
        if (meta != null) {
            GuildSettingsService settingsService = engine.getServiceManager().getService(GuildSettingsService.class);
            if (settingsService != null) {
                settingsService.unregisterOwner(meta.getId());
            }
            UserSettingsService userSettingsService = engine.getServiceManager().getService(UserSettingsService.class);
            if (userSettingsService != null) {
                userSettingsService.unregisterOwner(meta.getId());
            }
            plugins.remove(meta.getId());
            loadedFingerprints.remove(meta.getId());
            loadedFiles.remove(meta.getId());
            failedSnapshot = loadedSnapshots.remove(meta.getId());
            PluginClassLoader registeredLoader = loaders.remove(meta.getId());
            if (loader == null) {
                loader = registeredLoader;
            } else {
                closeLoader(meta.getId(), registeredLoader);
            }
        }
        closeLoader(meta != null ? meta.getId() : "unknown", loader);
        // Windows keeps the verified snapshot locked while its class loader is open.
        // Close the loader before deleting the snapshot so failed plugin loads do not
        // leave xross-plugin-*.jar files behind in the temp directory.
        if (meta != null) {
            deleteSnapshot(meta.getId(), failedSnapshot);
        }
    }

    private void closeLoader(String pluginId, PluginClassLoader loader) {
        if (loader == null) {
            return;
        }
        try {
            loader.close();
        } catch (Exception exception) {
            logger.error("Error closing ClassLoader for " + pluginId, exception);
        }
    }

    private static void deleteSnapshot(String pluginId, Path snapshot) {
        if (snapshot == null) {
            return;
        }
        try {
            Files.deleteIfExists(snapshot);
        } catch (IOException exception) {
            logger.warn("Could not delete private plugin snapshot for {}.", pluginId, exception);
        }
    }

    private void watchLoop(WatchService watcher) {
        try {
            while (watchService == watcher) {
                WatchKey key = watcher.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }
                    Object context = event.context();
                    if (context instanceof Path relativePath) {
                        scheduleReload(pluginDirectory.resolve(relativePath).normalize());
                    }
                }
                if (!key.reset()) {
                    break;
                }
            }
        } catch (ClosedWatchServiceException ignored) {
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            logger.error("Plugin directory watcher failed.", exception);
        }
    }

    private void scheduleReload(Path path) {
        if (!path.startsWith(pluginDirectory) || !path.getFileName().toString().toLowerCase().endsWith(".jar")) {
            return;
        }
        ScheduledExecutorService executor = reloadExecutor;
        if (executor == null || executor.isShutdown()) {
            return;
        }
        ScheduledFuture<?> previous = scheduledReloads.put(path, executor.schedule(() -> {
            scheduledReloads.remove(path);
            if (waitUntilStable(path)) {
                loadExternalPlugin(path.toFile());
            }
        }, RELOAD_DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS));
        if (previous != null) {
            previous.cancel(false);
        }
    }

    private boolean waitUntilStable(Path path) {
        try {
            long previousSize = -1L;
            long previousModified = -1L;
            for (int attempt = 0; attempt < 5; attempt++) {
                if (!Files.isRegularFile(path)) {
                    return false;
                }
                long size = Files.size(path);
                long modified = Files.getLastModifiedTime(path).toMillis();
                if (size > 0L && size == previousSize && modified == previousModified) {
                    return true;
                }
                previousSize = size;
                previousModified = modified;
                Thread.sleep(300L);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException exception) {
            logger.warn("Could not inspect changed plugin file: {}", path, exception);
        }
        logger.warn("Plugin file did not become stable in time: {}", path);
        return false;
    }

    private record PluginDescriptor(File jarFile, PluginMeta meta, String fingerprint) {
    }

    public record PendingPlugin(File jarFile, PluginMeta meta, String fingerprint) {
    }

    public record LoadedPlugin(PluginMeta meta, String fingerprint, File jarFile, boolean enabled) {
    }
}
