package com.ztraqto.openxross.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.ApplicationInfo;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.ApplicationTeam;
import net.dv8tion.jda.api.entities.TeamMember;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.sharding.ShardManager;
import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.api.IService;
import com.ztraqto.openxross.api.console.XrossConsoleScope;
import com.ztraqto.openxross.api.database.XrossDbClient;
import com.ztraqto.openxross.api.database.XrossDbKey;
import com.ztraqto.openxross.api.plugin.PluginMeta;
import com.ztraqto.openxross.core.PluginManager;
import com.ztraqto.openxross.core.console.DiscordConsoleAppender;
import com.ztraqto.openxross.core.security.SecureJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class XrossConsoleService implements IService {

    private static final Logger logger = LoggerFactory.getLogger(XrossConsoleService.class);
    private static final XrossDbKey SETTINGS_KEY = new XrossDbKey("xross-console", "settings", "primary");
    private static final int MAX_QUEUED_LINES = 1000;
    /** Keep the title, footer, and Component V2 action row below Discord's 4,000-character message limit. */
    private static final int MAX_APPROVAL_DESCRIPTION_LENGTH = 3200;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);

    private final XrossDbClient databaseClient;
    private final ObjectMapper mapper = SecureJson.newMapper();
    private final ConcurrentLinkedQueue<String> logQueue = new ConcurrentLinkedQueue<>();
    private final PluginApprovalQueue approvalQueue = new PluginApprovalQueue();
    private final Map<String, ApprovalEntry> approvalEntries = new ConcurrentHashMap<>();

    private XrossEngine engine;
    private ShardManager shardManager;
    private PluginManager pluginManager;
    private volatile long channelId;
    private volatile XrossConsoleScope scope = XrossConsoleScope.APPROVALS_ONLY;
    private final Set<Long> applicationAdministratorIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService flushExecutor;
    private DiscordConsoleAppender appender;
    private volatile Message approvalMessage;
    private boolean approvalMessageCreating;

    public XrossConsoleService(XrossDbClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    @Override
    public void init(XrossEngine engine) {
        this.engine = engine;
        databaseClient.read(SETTINGS_KEY).ifPresent(record -> {
            ConsoleSettings settings = mapper.convertValue(record.payload(), ConsoleSettings.class);
            channelId = settings.channelId();
            scope = settings.scope() == null ? XrossConsoleScope.APPROVALS_ONLY : settings.scope();
        });
    }

    public void attach(ShardManager shardManager, PluginManager pluginManager) {
        this.shardManager = shardManager;
        this.pluginManager = pluginManager;
        try {
            loadApplicationAdministrators(shardManager.retrieveApplicationInfo().complete());
        } catch (Exception exception) {
            logger.warn("Could not resolve Discord application owner.", exception);
        }

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        appender = new DiscordConsoleAppender(this::acceptLog);
        appender.setContext(context);
        appender.setName("XrossDiscordConsole");
        appender.start();
        context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(appender);

        flushExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Xross-Console-Flush");
            thread.setDaemon(true);
            return thread;
        });
        flushExecutor.scheduleAtFixedRate(this::flushLogs, 2, 2, TimeUnit.SECONDS);
        if (channelId != 0L) {
            sendNotice("Xross Engineコンソールに接続しました。ログ範囲: `" + scope + "`");
        }
    }

    @Override
    public void shutdown() {
        detach();
        shardManager = null;
        pluginManager = null;
        applicationAdministratorIds.clear();
    }

    public void detach() {
        if (appender != null) {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).detachAppender(appender);
            appender.stop();
            appender = null;
        }
        if (flushExecutor != null) {
            flushExecutor.shutdownNow();
            flushExecutor = null;
        }
        logQueue.clear();
        approvalQueue.clear();
    }

    @Override
    public String getName() {
        return "XrossConsoleService";
    }

    public boolean isAdministrator(long userId) {
        return applicationAdministratorIds.contains(userId)
                || engine.getConfiguration().botAdministratorIds().contains(userId);
    }

    private void loadApplicationAdministrators(ApplicationInfo applicationInfo) {
        applicationAdministratorIds.clear();
        if (applicationInfo.getOwner() != null) {
            applicationAdministratorIds.add(applicationInfo.getOwner().getIdLong());
        }
        ApplicationTeam team = applicationInfo.getTeam();
        if (team != null) {
            team.getMembers().stream()
                    .filter(member -> member.getMembershipState() == TeamMember.MembershipState.ACCEPTED)
                    .filter(member -> member.getRoleType() == TeamMember.RoleType.OWNER
                            || member.getRoleType() == TeamMember.RoleType.ADMIN)
                    .map(TeamMember::getUser)
                    .forEach(user -> applicationAdministratorIds.add(user.getIdLong()));
        }
        logger.info("Resolved {} Discord application administrator(s).", applicationAdministratorIds.size());
    }

    public synchronized void bind(long channelId, XrossConsoleScope scope) {
        if (channelId <= 0L) {
            throw new IllegalArgumentException("A valid Discord text channel is required.");
        }
        this.channelId = channelId;
        this.scope = scope == null ? XrossConsoleScope.APPROVALS_ONLY : scope;
        persistSettings();
        sendNotice("このチャンネルをXross Engineコンソールに設定しました。ログ範囲: `" + this.scope + "`");
        if (pluginManager != null) {
            pluginManager.notifyPendingApprovals();
        }
    }

    public synchronized void unbind() {
        if (channelId != 0L) {
            sendNotice("Xross Engineコンソールの接続を解除しました。");
        }
        channelId = 0L;
        persistSettings();
    }

    public synchronized void setScope(XrossConsoleScope scope) {
        this.scope = scope;
        persistSettings();
        sendNotice("Xross Engineコンソールのログ範囲を `" + scope + "` に変更しました。");
    }

    public long getChannelId() {
        return channelId;
    }

    public XrossConsoleScope getScope() {
        return scope;
    }

    public PluginManager getPluginManager() {
        return pluginManager;
    }

    public void requestPluginApproval(PluginMeta meta, String fingerprint, java.io.File jarFile) {
        TextChannel channel = getChannel();
        if (channel == null) {
            logger.warn(
                    "Plugin {} requires approval, but no Xross console channel is configured. Run /xross-console bind.",
                    meta.getId()
            );
            return;
        }

        String key = approvalKey(meta.getId(), fingerprint);
        if (approvalEntries.containsKey(key)) {
            refreshApprovalEmbed();
            return;
        }

        PluginApprovalQueue.Request request = new PluginApprovalQueue.Request(meta, fingerprint, jarFile);
        ApprovalEntry entry = new ApprovalEntry(meta, fingerprint, request.requestId(), ApprovalState.PENDING);
        if (approvalEntries.putIfAbsent(key, entry) == null && approvalQueue.offer(request)) {
            refreshApprovalEmbed();
            sendNextPluginApproval();
        }
    }

    public void completePluginApproval(String pluginId, String fingerprint) {
        completePluginEntry(pluginId, fingerprint, ApprovalState.APPROVED);
    }

    public void completePluginDenial(String pluginId, String fingerprint) {
        completePluginEntry(pluginId, fingerprint, ApprovalState.DENIED);
    }

    private void completePluginEntry(String pluginId, String fingerprint, ApprovalState state) {
        String key = approvalKey(pluginId, fingerprint);
        approvalEntries.computeIfPresent(key, (ignored, entry) ->
                new ApprovalEntry(entry.meta(), entry.fingerprint(), entry.requestId(), state));
        refreshApprovalEmbed();
    }

    /** Bulk approval was removed from the Discord UI in 1.4.1. */
    @Deprecated(since = "1.4.1")
    public void completeAllPluginApprovals() {
        throw new UnsupportedOperationException("Bulk plugin approval is disabled. Review each plugin individually.");
    }

    public ApprovalTarget resolveApprovalRequest(String requestId) {
        PluginApprovalQueue.Request request = approvalQueue.find(requestId);
        if (request == null || !isPending(request)) return null;
        return new ApprovalTarget(request.meta().getId(), request.fingerprint());
    }

    public void completeApprovalRequest(String requestId, boolean approved) {
        PluginApprovalQueue.Request request = approvalQueue.find(requestId);
        if (request == null) return;
        if (!approvalQueue.complete(requestId)) return;
        completePluginEntry(
                request.meta().getId(),
                request.fingerprint(),
                approved ? ApprovalState.APPROVED : ApprovalState.DENIED
        );
        sendNextPluginApproval();
    }

    private synchronized void refreshApprovalEmbed() {
        TextChannel channel = getChannel();
        if (channel == null || approvalEntries.isEmpty()) return;

        String lines = approvalDescription();
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Plugin approval queue")
                .setColor(new Color(255, 170, 0))
                .setDescription(lines)
                .setFooter("Each plugin must be reviewed individually. Plugin permissions are not a JVM/OS sandbox.");
        boolean hasPending = approvalEntries.values().stream().anyMatch(entry -> entry.state() == ApprovalState.PENDING);
        Button statusButton = Button.secondary(
                "xpa:done",
                hasPending ? "Review pending plugins individually" : "No pending plugins"
        ).asDisabled();
        if (approvalMessage == null) {
            if (approvalMessageCreating) return;
            approvalMessageCreating = true;
            channel.sendMessageComponents(com.ztraqto.openxross.api.components.EmbedContainer.of(
                            embed.build(), ActionRow.of(statusButton)))
                    .useComponentsV2(true)
                    .queue(message -> {
                        approvalMessage = message;
                        approvalMessageCreating = false;
                        refreshApprovalEmbed();
                    }, failure -> {
                        approvalMessageCreating = false;
                        logger.warn("Could not send plugin approval queue.", failure);
                    });
            return;
        }
        approvalMessage.editMessageComponents(com.ztraqto.openxross.api.components.EmbedContainer.of(
                        embed.build(), ActionRow.of(statusButton)))
                .useComponentsV2(true)
                .queue(ignored -> { }, failure -> {
                    approvalMessage = null;
                    logger.warn("Could not update plugin approval queue.", failure);
                });
    }

    private String approvalLine(ApprovalEntry entry) {
        String permissions = entry.meta().requestedPermissions().stream()
                .map(permission -> permission.name())
                .reduce((left, right) -> left + ", " + right)
                .orElse("none");
        String state = switch (entry.state()) {
            case PENDING -> "pending";
            case APPROVED -> "approved";
            case DENIED -> "denied";
        };
        return "`" + entry.meta().getName() + "` — `" + permissions + "` — `" + state + "`";
    }

    private String approvalDescription() {
        java.util.List<ApprovalEntry> entries = approvalEntries.values().stream()
                .sorted(java.util.Comparator.comparing(entry -> entry.meta().getId()))
                .toList();
        if (entries.isEmpty()) return "No plugin approvals are pending.";

        StringBuilder description = new StringBuilder();
        int shown = 0;
        for (ApprovalEntry entry : entries) {
            String line = approvalLine(entry);
            int separatorLength = description.isEmpty() ? 0 : 1;
            if (description.length() + separatorLength + line.length() > MAX_APPROVAL_DESCRIPTION_LENGTH) break;
            if (separatorLength > 0) description.append('\n');
            description.append(line);
            shown++;
        }
        int omitted = entries.size() - shown;
        if (omitted > 0) {
            String summary = "\n… " + omitted + " more plugin approval(s) are hidden. Use /xross-console action:pending.";
            int available = Math.max(0, MAX_APPROVAL_DESCRIPTION_LENGTH - description.length());
            if (available > 0) description.append(summary, 0, Math.min(summary.length(), available));
        }
        return description.toString();
    }

    private static String approvalKey(String pluginId, String fingerprint) {
        return pluginId + ":" + fingerprint;
    }

    private synchronized void sendNextPluginApproval() {
        PluginApprovalQueue.Request request = approvalQueue.activateNext(this::isPending);
        if (request == null) return;

        TextChannel channel = getChannel();
        if (channel == null) {
            approvalQueue.release(request);
            return;
        }

        PluginMeta meta = request.meta();
        String fingerprint = request.fingerprint();
        java.io.File jarFile = request.jarFile();
        String permissions = meta.requestedPermissions().stream()
                .map(permission -> "• `" + permission.name() + "` — " + permission.description())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("追加権限なし");
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("プラグインの承認が必要です")
                .setColor(new Color(255, 170, 0))
                .addField("プラグイン", meta.getName() + " (`" + meta.getId() + "`) v" + meta.getVersion(), false)
                .addField("ファイル", jarFile.getName(), false)
                .addField("要求されたアクセス権", permissions, false)
                .addField("SHA-256", "`" + fingerprint + "`", false)
                .setFooter("承認済みPluginはBot JVM内でコードを実行できます。発行元とSHA-256を確認してください。");
        channel.sendMessageComponents(com.ztraqto.openxross.api.components.EmbedContainer.of(embed.build(), ActionRow.of(
                        Button.success("xpa:a:" + request.requestId(), "承認"),
                        Button.danger("xpa:d:" + request.requestId(), "拒否")
                )))
                .useComponentsV2(true)
                .queue(
                        ignored -> { },
                        failure -> {
                            approvalQueue.release(request);
                            approvalQueue.offer(request);
                            logger.warn("Could not send plugin approval request for {}.", meta.getId(), failure);
                            ScheduledExecutorService executor = flushExecutor;
                            if (executor != null && !executor.isShutdown()) {
                                executor.schedule(this::sendNextPluginApproval, 5, TimeUnit.SECONDS);
                            }
                        }
                );
    }

    private boolean isPending(PluginApprovalQueue.Request request) {
        PluginManager current = pluginManager;
        if (current == null) return false;
        return current.getPendingPlugins().stream().anyMatch(pending ->
                pending.meta().getId().equals(request.meta().getId())
                        && pending.fingerprint().equals(request.fingerprint())
        );
    }

    public record ApprovalTarget(String pluginId, String fingerprint) { }

    public void sendNotice(String message) {
        TextChannel channel = getChannel();
        if (channel != null) {
            channel.sendMessage(message).queue();
        }
    }

    private void acceptLog(ILoggingEvent event) {
        if (channelId == 0L || shardManager == null || scope == XrossConsoleScope.APPROVALS_ONLY) {
            return;
        }
        if (scope == XrossConsoleScope.WARNINGS_AND_APPROVALS && !event.getLevel().isGreaterOrEqual(Level.WARN)) {
            return;
        }

        String loggerName = event.getLoggerName();
        int separator = loggerName.lastIndexOf('.');
        if (separator >= 0) {
            loggerName = loggerName.substring(separator + 1);
        }
        String line = TIME_FORMAT.format(Instant.ofEpochMilli(event.getTimeStamp()))
                + " " + event.getLevel() + " " + loggerName + " - " + event.getFormattedMessage();
        if (event.getThrowableProxy() != null) {
            line += " (" + event.getThrowableProxy().getClassName() + ": " + event.getThrowableProxy().getMessage() + ")";
        }
        line = line.replace("```", "''' ");
        if (line.length() > 900) {
            line = line.substring(0, 897) + "...";
        }
        while (logQueue.size() >= MAX_QUEUED_LINES) {
            logQueue.poll();
        }
        logQueue.offer(line);
    }

    private void flushLogs() {
        TextChannel channel = getChannel();
        if (channel == null || logQueue.isEmpty()) {
            return;
        }

        StringBuilder batch = new StringBuilder();
        while (true) {
            String line = logQueue.peek();
            if (line == null || batch.length() + line.length() + 1 > 1850) {
                break;
            }
            logQueue.poll();
            batch.append(line).append('\n');
        }
        if (!batch.isEmpty()) {
            channel.sendMessage("```text\n" + batch + "```").queue();
        }
    }

    private TextChannel getChannel() {
        if (channelId == 0L || shardManager == null) {
            return null;
        }
        return shardManager.getTextChannelById(channelId);
    }

    private void persistSettings() {
        databaseClient.write(SETTINGS_KEY, mapper.valueToTree(new ConsoleSettings(channelId, scope)));
    }

    public record ConsoleSettings(long channelId, XrossConsoleScope scope) {
    }

    private enum ApprovalState { PENDING, APPROVED, DENIED }
    private record ApprovalEntry(PluginMeta meta, String fingerprint, String requestId, ApprovalState state) { }
}
