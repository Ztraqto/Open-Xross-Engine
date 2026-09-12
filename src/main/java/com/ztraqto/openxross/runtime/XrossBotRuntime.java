package com.ztraqto.openxross.runtime;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.SessionDisconnectEvent;
import net.dv8tion.jda.api.events.session.SessionRecreateEvent;
import net.dv8tion.jda.api.events.session.SessionResumeEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.sharding.DefaultShardManagerBuilder;
import net.dv8tion.jda.api.sharding.ShardManager;
import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.api.database.XrossDbClient;
import com.ztraqto.openxross.api.runtime.XrossRuntime;
import com.ztraqto.openxross.core.EngineEventListener;
import com.ztraqto.openxross.core.PluginManager;
import com.ztraqto.openxross.core.ServiceManager;
import com.ztraqto.openxross.core.bus.PluginBus;
import com.ztraqto.openxross.core.command.LanguageCommand;
import com.ztraqto.openxross.core.command.EditorCommand;
import com.ztraqto.openxross.core.command.ServerSettingsCommand;
import com.ztraqto.openxross.core.command.EditorReloadListener;
import com.ztraqto.openxross.core.command.UserSettingsCommand;
import com.ztraqto.openxross.core.command.ApplySettingsCommand;
import com.ztraqto.openxross.core.command.MasterVolumeCommand;
import com.ztraqto.openxross.core.command.SystemHelpCommand;
import com.ztraqto.openxross.core.command.PrivacyPolicyCommand;
import com.ztraqto.openxross.core.command.HelpHomeListener;
import com.ztraqto.openxross.core.command.VolumeCommand;
import com.ztraqto.openxross.core.command.XrossSystemCommand;
import com.ztraqto.openxross.core.command.XrossConsoleCommand;
import com.ztraqto.openxross.core.command.XrossAdminEditorCommand;
import com.ztraqto.openxross.service.CommandService;
import com.ztraqto.openxross.service.BotStatusService;
import com.ztraqto.openxross.service.XrossConsoleService;
import com.ztraqto.openxross.service.ShardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class XrossBotRuntime implements XrossRuntime {

    private static final Logger logger = LoggerFactory.getLogger(XrossBotRuntime.class);

    private final XrossEngine engine;
    private final String token;
    private final XrossDbClient databaseClient;

    private ShardManager shardManager;
    private ServiceManager serviceManager;
    private PluginManager pluginManager;
    private PluginBus pluginBus;
    private boolean running;

    public XrossBotRuntime(XrossEngine engine, String token, XrossDbClient databaseClient) {
        this.engine = engine;
        this.token = token;
        this.databaseClient = databaseClient;
    }

    @Override
    public synchronized void start() throws Exception {
        if (running) {
            throw new IllegalStateException("Xross Bot runtime is already running.");
        }

        long bootStart = System.currentTimeMillis();
        logger.info("Starting Xross Bot runtime...");

        try {
            pluginBus = new PluginBus();
            serviceManager = new ServiceManager(engine, databaseClient);
            serviceManager.init();
            pluginManager = new PluginManager(engine);

            registerSystemCommands();
            connectDiscord();
            ShardService shardService = serviceManager.getService(ShardService.class);
            if (shardService != null) {
                shardService.attach(shardManager);
            }
            serviceManager.getService(BotStatusService.class).attach(shardManager);
            serviceManager.getService(XrossConsoleService.class).attach(shardManager, pluginManager);
            pluginManager.loadAll();
            startPluginWatcher(pluginManager::startWatching);

            running = true;
            logger.info("Xross Bot runtime started in {} ms.", System.currentTimeMillis() - bootStart);
        } catch (Exception exception) {
            stop();
            throw exception;
        }
    }

    static void startPluginWatcher(PluginWatcher watcher) {
        try {
            watcher.start();
        } catch (IOException exception) {
            logger.warn("Plugin hot reload is unavailable; continuing without directory watching.", exception);
        }
    }

    @FunctionalInterface
    interface PluginWatcher {
        void start() throws IOException;
    }

    @Override
    public synchronized void stop() {
        // Notify stateful plugins before they are unloaded so they can persist
        // ephemeral shard-local state (for example preAd checkpoints) before a
        // reshard or normal shutdown.
        ShardService shardService = serviceManager != null ? serviceManager.getService(ShardService.class) : null;
        if (shardService != null && shardManager != null) {
            shardService.notifyStoppingAll();
        }

        if (pluginManager != null) {
            pluginManager.unloadAll();
            pluginManager = null;
        }

        if (serviceManager != null) {
            XrossConsoleService consoleService = serviceManager.getService(XrossConsoleService.class);
            if (consoleService != null) {
                consoleService.detach();
            }
        }

        if (shardManager != null) {
            List<JDA> shards = List.copyOf(shardManager.getShards());
            shardManager.shutdown();
            for (JDA shard : shards) {
                try {
                    if (!shard.awaitShutdown(Duration.ofSeconds(engine.getConfiguration().shards().shutdownTimeoutSeconds()))) {
                        shard.shutdownNow();
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    shard.shutdownNow();
                }
            }
            shardManager = null;
        }

        if (serviceManager != null) {
            serviceManager.shutdown();
            serviceManager = null;
        }

        if (databaseClient != null) {
            databaseClient.close();
        }
        pluginBus = null;

        if (running) {
            logger.info("Xross Bot runtime stopped.");
        }
        running = false;
    }

    @Override
    public synchronized boolean isRunning() {
        return running;
    }

    public JDA getJda() {
        if (shardManager == null || shardManager.getShards().isEmpty()) {
            return null;
        }
        return shardManager.getShards().get(0);
    }

    public ShardManager getShardManager() {
        return shardManager;
    }

    public ServiceManager getServiceManager() {
        return serviceManager;
    }

    public PluginManager getPluginManager() {
        return pluginManager;
    }

    public PluginBus getPluginBus() {
        return pluginBus;
    }

    public void notifyTopologyChanging(int previousTotal, int newTotal) {
        if (serviceManager == null) return;
        ShardService shardService = serviceManager.getService(ShardService.class);
        if (shardService != null) shardService.notifyTopologyChanging(previousTotal, newTotal);
    }

    public void notifyTopologyChanged(int previousTotal, int newTotal) {
        if (serviceManager == null) return;
        ShardService shardService = serviceManager.getService(ShardService.class);
        if (shardService != null) shardService.notifyTopologyChanged(previousTotal, newTotal);
    }

    private void registerSystemCommands() {
        CommandService commandService = serviceManager.getService(CommandService.class);
        if (commandService == null) {
            throw new IllegalStateException("CommandService was not initialized.");
        }

        commandService.register(null, new XrossSystemCommand());
        commandService.register(null, new SystemHelpCommand());
        commandService.register(null, new PrivacyPolicyCommand(engine));
        commandService.register(null, new LanguageCommand(engine));
        commandService.register(null, new VolumeCommand());
        commandService.register(null, new MasterVolumeCommand());
        commandService.register(null, new EditorCommand());
        commandService.register(null, new ServerSettingsCommand());
        commandService.register(null, new XrossAdminEditorCommand());
        commandService.register(null, new UserSettingsCommand());
        commandService.register(null, new ApplySettingsCommand());
        commandService.register(null, new XrossConsoleCommand());
    }

    private void connectDiscord() throws InterruptedException {
        int expectedShards = engine.getConfiguration().shards().shardIds().size();
        CountDownLatch readyLatch = new CountDownLatch(expectedShards);
        DefaultShardManagerBuilder builder = DefaultShardManagerBuilder.createDefault(token);
        configurePrivacyCaches(builder);
        builder.setBulkDeleteSplittingEnabled(false);
        builder.setShardsTotal(engine.getConfiguration().shards().totalShards());
        builder.setShards(engine.getConfiguration().shards().shardIdArray());
        builder.enableIntents(
                GatewayIntent.GUILD_MESSAGES,
                GatewayIntent.MESSAGE_CONTENT,
                GatewayIntent.GUILD_MEMBERS,
                GatewayIntent.GUILD_VOICE_STATES
        );
        builder.addEventListeners(new EngineEventListener(engine));
        builder.addEventListeners(new HelpHomeListener(engine));
        builder.addEventListeners(new EditorReloadListener(engine));
        builder.addEventListeners(new ShardLifecycleAdapter(readyLatch, serviceManager.getService(ShardService.class)));
        shardManager = builder.build();

        int readyTimeoutSeconds = engine.getConfiguration().shards().readyTimeoutSeconds();
        if (!readyLatch.await(readyTimeoutSeconds, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Discord shards did not become ready within " + readyTimeoutSeconds + " seconds.");
        }
        logger.info(
                "Discord shards ready: {} of {} (configured total: {}).",
                expectedShards,
                expectedShards,
                engine.getConfiguration().shards().totalShards()
        );
    }

    /** Package-visible so the exact production builder path has a network-free test. */
    static DefaultShardManagerBuilder configurePrivacyCaches(DefaultShardManagerBuilder builder) {
        PrivacyCacheConfiguration.apply(builder);
        return builder;
    }

    private static final class ShardLifecycleAdapter extends ListenerAdapter {
        private final CountDownLatch readyLatch;
        private final ShardService shardService;

        private ShardLifecycleAdapter(CountDownLatch readyLatch, ShardService shardService) {
            this.readyLatch = readyLatch;
            this.shardService = shardService;
        }

        @Override
        public void onReady(ReadyEvent event) {
            if (shardService != null) {
                shardService.notifyReady(event.getJDA());
            }
            readyLatch.countDown();
        }

        @Override
        public void onSessionDisconnect(SessionDisconnectEvent event) {
            if (shardService != null) {
                shardService.notifyDisconnected(event.getJDA());
            }
        }

        @Override
        public void onSessionResume(SessionResumeEvent event) {
            if (shardService != null) {
                shardService.notifyResumed(event.getJDA());
            }
        }

        @Override
        public void onSessionRecreate(SessionRecreateEvent event) {
            if (shardService != null) {
                shardService.notifyRecreated(event.getJDA());
            }
        }

        @Override
        public void onShutdown(ShutdownEvent event) {
            if (shardService != null) {
                shardService.notifyShutdown(event.getJDA());
            }
        }
    }
}
