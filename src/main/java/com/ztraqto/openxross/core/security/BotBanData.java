package com.ztraqto.openxross.core.security;

import com.ztraqto.openxross.api.wrider.WriderId;

public class BotBanData {

    @WriderId
    public long userId;
    public String reason;
    public long bannedAt;
    public String bannedBy;

    public BotBanData() {
    }

    public BotBanData(long userId, String reason, long bannedAt, String bannedBy) {
        this.userId = userId;
        this.reason = reason;
        this.bannedAt = bannedAt;
        this.bannedBy = bannedBy;
    }
}
