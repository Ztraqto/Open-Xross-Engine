package com.ztraqto.openxross.core;

import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.api.IService;
import com.ztraqto.openxross.api.database.XrossDbClient;
import com.ztraqto.openxross.service.BotBanService;
import com.ztraqto.openxross.service.BotStatusService;
import com.ztraqto.openxross.service.AttachmentArchiveService;
import com.ztraqto.openxross.service.AITerminalService;
import com.ztraqto.openxross.service.CommandService;
import com.ztraqto.openxross.service.CertificationService;
import com.ztraqto.openxross.service.EditorSessionService;
import com.ztraqto.openxross.service.EditorSensitiveDataService;
import com.ztraqto.openxross.service.LocaleService;
import com.ztraqto.openxross.service.ManagementLogService;
import com.ztraqto.openxross.service.GuildSettingsService;
import com.ztraqto.openxross.service.UserSettingsService;
import com.ztraqto.openxross.service.PauseService;
import com.ztraqto.openxross.service.PluginApprovalService;
import com.ztraqto.openxross.service.StorageService;
import com.ztraqto.openxross.service.ShardService;
import com.ztraqto.openxross.service.VoiceService;
import com.ztraqto.openxross.service.XrossConsoleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class ServiceManager {

    private static final Logger logger = LoggerFactory.getLogger(ServiceManager.class);

    private final XrossEngine engine;
    private final Map<Class<? extends IService>, IService> services = new LinkedHashMap<>();
    private final ArrayList<IService> initializedServices = new ArrayList<>();

    public ServiceManager(XrossEngine engine, XrossDbClient databaseClient) {
        this.engine = engine;
        registerServices(databaseClient);
    }

    public void init() {
        logger.info("Initializing internal services...");
        initializedServices.clear();
        for (IService service : services.values()) {
            logger.debug("Starting service: {}", service.getName());
            try {
                service.init(engine);
                initializedServices.add(service);
            } catch (Exception exception) {
                logger.error("Failed to initialize service: {}", service.getName(), exception);
                shutdownInitializedServices();
                throw new IllegalStateException("Critical service failure: " + service.getName(), exception);
            }
        }
    }

    public void shutdown() {
        var reverseList = new ArrayList<>(initializedServices);
        Collections.reverse(reverseList);

        for (IService service : reverseList) {
            try {
                service.shutdown();
            } catch (Exception exception) {
                logger.error("Error shutting down service: {}", service.getName(), exception);
            }
        }
        initializedServices.clear();
    }

    @SuppressWarnings("unchecked")
    public <T extends IService> T getService(Class<T> type) {
        return (T) services.get(type);
    }

    private void registerServices(XrossDbClient databaseClient) {
        StorageService storageService = new StorageService(databaseClient);
        GuildSettingsService guildSettingsService = new GuildSettingsService(databaseClient);
        UserSettingsService userSettingsService = new UserSettingsService(databaseClient);
        EditorSensitiveDataService editorSensitiveDataService = new EditorSensitiveDataService(engine.getConfiguration().editor());
        EditorSessionService editorSessionService = new EditorSessionService(
                databaseClient,
                guildSettingsService,
                userSettingsService,
                engine.getConfiguration().editor(),
                editorSensitiveDataService
        );
        PluginApprovalService pluginApprovalService = new PluginApprovalService(databaseClient);
        XrossConsoleService xrossConsoleService = new XrossConsoleService(databaseClient);
        BotBanService botBanService = new BotBanService(storageService);
        BotStatusService botStatusService = new BotStatusService(databaseClient);
        PauseService pauseService = new PauseService(botBanService, databaseClient);
        LocaleService localeService = new LocaleService(guildSettingsService, userSettingsService);
        CommandService commandService = new CommandService(pauseService, localeService);
        CertificationService certificationService = new CertificationService(databaseClient);
        ShardService shardService = new ShardService(engine.getConfiguration().shards());

        services.put(ShardService.class, shardService);
        services.put(StorageService.class, storageService);
        services.put(GuildSettingsService.class, guildSettingsService);
        services.put(UserSettingsService.class, userSettingsService);
        services.put(EditorSensitiveDataService.class, editorSensitiveDataService);
        services.put(AttachmentArchiveService.class, new AttachmentArchiveService(
                editorSensitiveDataService, engine.getConfiguration().editor()));
        services.put(EditorSessionService.class, editorSessionService);
        services.put(PluginApprovalService.class, pluginApprovalService);
        services.put(XrossConsoleService.class, xrossConsoleService);
        services.put(LocaleService.class, localeService);
        services.put(ManagementLogService.class, new ManagementLogService(guildSettingsService));
        services.put(BotBanService.class, botBanService);
        services.put(BotStatusService.class, botStatusService);
        services.put(PauseService.class, pauseService);
        services.put(CommandService.class, commandService);
        services.put(CertificationService.class, certificationService);
        services.put(VoiceService.class, new VoiceService(guildSettingsService));
        services.put(AITerminalService.class, new AITerminalService());
    }

    private void shutdownInitializedServices() {
        Collections.reverse(initializedServices);
        for (IService service : initializedServices) {
            try {
                service.shutdown();
            } catch (Exception exception) {
                logger.error("Error rolling back service: {}", service.getName(), exception);
            }
        }
        initializedServices.clear();
    }
}
