package com.ztraqto.openxross.service;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.fasterxml.jackson.databind.node.IntNode;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.api.IService;
import com.ztraqto.openxross.api.storage.IDatabaseOwner;
import com.ztraqto.openxross.core.audio.GuildVoiceState;
import com.ztraqto.openxross.core.audio.data.GuildVoiceConfig;
import com.ztraqto.openxross.core.audio.data.SystemVoiceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class VoiceService implements IService, IDatabaseOwner {

    private static final Logger logger = LoggerFactory.getLogger(VoiceService.class);
    private AudioPlayerManager playerManager;
    private final Map<Long, GuildVoiceState> guildStates = new ConcurrentHashMap<>();
    private XrossEngine engine;
    private final GuildSettingsService guildSettingsService;


    private volatile int masterVolume = 100;
    private ScheduledExecutorService configurationPoller;

    public VoiceService(GuildSettingsService guildSettingsService) {
        this.guildSettingsService = guildSettingsService;
    }

    @Override
    public String getDatabaseContextId() {
        return "voice_config"; // data/voice_config.db に保存
    }

    private StorageService getStorage() {
        return engine.getServiceManager().getService(StorageService.class);
    }

    @Override
    public void init(XrossEngine engine) {
        this.engine = engine;
        this.playerManager = new DefaultAudioPlayerManager();
        AudioSourceManagers.registerRemoteSources(playerManager); // YouTube等
        AudioSourceManagers.registerLocalSource(playerManager);  // ローカルファイル

        // マスター音量のロード
        loadMasterVolume();
        guildSettingsService.registerListener(
                "xross-voice",
                GuildSettingsService.VOICE_VOLUME_KEY,
                (guildId, value) -> {
                    GuildVoiceState state = guildStates.get(guildId);
                    if (state != null) {
                        state.applyVolume(value.intValue(), masterVolume);
                    }
                }
        );
        configurationPoller = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Xross-Voice-Config");
            thread.setDaemon(true);
            return thread;
        });
        configurationPoller.scheduleAtFixedRate(this::loadMasterVolume, 5, 5, TimeUnit.SECONDS);
    }

    private void loadMasterVolume() {
        try {
            StorageService storage = getStorage();
            if (storage != null) {
                storage.getWrider().initTable(this, SystemVoiceConfig.class);
                SystemVoiceConfig cfg = storage.getWrider().loadQL(this, SystemVoiceConfig.class, "MASTER");
                if (cfg != null && cfg.volume != this.masterVolume) {
                    this.masterVolume = cfg.volume;
                    guildStates.values().forEach(state ->
                            state.applyVolume(state.getCurrentGuildVolume(), this.masterVolume)
                    );
                }
            }
        } catch (Exception e) {
            logger.error("Failed to load master volume", e);
        }
    }

    private int loadGuildVolume(long guildId) {
        try {
            return guildSettingsService.getInteger(guildId, GuildSettingsService.VOICE_VOLUME_KEY);
        } catch (Exception e) {
            logger.warn("Failed to load guild volume for {}", guildId);
        }
        return 100; // デフォルト
    }

    /**
     * マスター音量を設定する (Bot管理者用)
     * 全サーバーに即時適用される
     */
    public void setMasterVolume(int volume) {
        this.masterVolume = Math.max(0, Math.min(100, volume));

        // DB保存
        if (getStorage() != null) {
            getStorage().getWrider().saveQL(this, new SystemVoiceConfig(this.masterVolume));
        }

        // 全アクティブプレイヤーに適用
        guildStates.values().forEach(state ->
                state.applyVolume(state.getCurrentGuildVolume(), this.masterVolume)
        );
        logger.info("Master volume set to: {}", this.masterVolume);
    }

    /**
     * サーバー音量を設定する (サーバー管理者用)
     */
    public void setGuildVolume(long guildId, int volume) {
        int vol = Math.max(0, Math.min(100, volume)); // 0-100制限

        // DB保存
        guildSettingsService.setValue(guildId, GuildSettingsService.VOICE_VOLUME_KEY, IntNode.valueOf(vol));

        // アクティブなら即時適用
        GuildVoiceState state = guildStates.get(guildId);
        if (state != null) {
            state.applyVolume(vol, this.masterVolume);
        }
    }

    public int getMasterVolume() { return masterVolume; }

    public int getGuildVolume(long guildId) {
        // メモリにあればそれを、なければDBから、それもなければ100
        if (guildStates.containsKey(guildId)) {
            return guildStates.get(guildId).getCurrentGuildVolume();
        }
        return loadGuildVolume(guildId);
    }

    @Override
    public void shutdown() {
        guildStates.values().forEach(GuildVoiceState::destroy);
        guildStates.clear();
        guildSettingsService.unregisterListeners("xross-voice");
        if (configurationPoller != null) {
            configurationPoller.shutdownNow();
            configurationPoller = null;
        }
        playerManager.shutdown();
    }

    @Override
    public String getName() { return "VoiceService"; }

    private GuildVoiceState getState(Guild guild) {
        return guildStates.computeIfAbsent(guild.getIdLong(), k -> {
            GuildVoiceState state = new GuildVoiceState(playerManager, guild);
            // 作成時に初期音量を適用する
            int gVol = loadGuildVolume(guild.getIdLong());
            state.applyVolume(gVol, this.masterVolume);
            return state;
        });
    }

    /**
     * VCに参加させる
     */
    public void connect(AudioChannel channel) {
        channel.getGuild().getAudioManager().openAudioConnection(channel);
    }

    /**
     * 音声を再生する
     * @param guild 対象ギルド
     * @param identifier URLまたはファイルパス
     * @param interrupt trueなら割り込み再生(SE)、falseなら通常再生(BGM)
     */
    public void play(Guild guild, String identifier, boolean interrupt) {
        GuildVoiceState state = getState(guild);

        playerManager.loadItem(identifier, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                if (interrupt) {
                    state.playInterrupt(track);
                } else {
                    state.play(track);
                }
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                // プレイリストの場合は最初の曲だけ再生（簡易実装）
                if (!playlist.getTracks().isEmpty()) {
                    trackLoaded(playlist.getTracks().get(0));
                }
            }

            @Override
            public void noMatches() { logger.warn("Audio source not found: " + identifier); }
            @Override
            public void loadFailed(FriendlyException exception) { logger.error("Audio load failed", exception); }
        });
    }
}
