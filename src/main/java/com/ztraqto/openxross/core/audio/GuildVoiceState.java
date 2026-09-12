package com.ztraqto.openxross.core.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import net.dv8tion.jda.api.entities.Guild;

public class GuildVoiceState extends AudioEventAdapter {

    private final AudioPlayer player;
    
    // 割り込み前のトラック情報を保存する場所
    private AudioTrack suspendedTrack;
    private long suspendedPosition;

    public GuildVoiceState(AudioPlayerManager manager, Guild guild) {
        this.player = manager.createPlayer();
        this.player.addListener(this);
        
        // JDAにハンドラをセット
        guild.getAudioManager().setSendingHandler(new AudioPlayerSendHandler(player));
    }

    public AudioPlayer getPlayer() {
        return player;
    }

    /**
     * 通常再生 (BGMなど)
     */
    public void play(AudioTrack track) {
        // 簡易実装: 既に再生中なら止めて再生 (本格的にはキューイングが必要)
        player.startTrack(track, false);
    }

    /**
     * 割り込み再生 (SEなど)
     * 現在の曲を一時停止し、SE再生後に再開する
     */
    public void playInterrupt(AudioTrack track) {
        AudioTrack current = player.getPlayingTrack();
        if (current != null) {
            // 現在の曲を退避
            this.suspendedTrack = current.makeClone();
            this.suspendedPosition = current.getPosition();
        }
        
        // SEを強制再生
        player.startTrack(track, false);
    }

    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        // 曲が終わった時、もし退避していた曲があれば復帰させる
        if (endReason.mayStartNext && suspendedTrack != null) {
            suspendedTrack.setPosition(suspendedPosition);
            player.startTrack(suspendedTrack, false);
            
            // 退避情報をクリア
            suspendedTrack = null;
            suspendedPosition = 0;
        }
    }
    
    public void destroy() {
        player.destroy();
    }

    // 現在のサーバー音量をメモリに保持しておく
    private int currentGuildVolume = 100;

    /**
     * 音量を適用する
     * @param guildVol サーバー設定 (0-100)
     * @param masterVol マスター設定 (0-100)
     */
    public void applyVolume(int guildVol, int masterVol) {
        this.currentGuildVolume = guildVol;

        // Lavaplayerの音量は通常 0-100 (最大1000までいけるが100を基準とする)
        // 計算式: (Guild * Master) / 100
        int finalVolume = (int) ((guildVol * masterVol) / 100.0);

        // 念のため範囲制限
        finalVolume = Math.max(0, Math.min(1000, finalVolume));

        player.setVolume(finalVolume);
    }

    public int getCurrentGuildVolume() {
        return currentGuildVolume;
    }
}