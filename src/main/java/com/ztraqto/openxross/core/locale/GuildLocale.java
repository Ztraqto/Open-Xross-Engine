package com.ztraqto.openxross.core.locale;

import com.ztraqto.openxross.api.wrider.WriderId;

public class GuildLocale {

    @WriderId
    public long guildId;
    public String lang;

    public GuildLocale() {
    }

    public GuildLocale(long guildId, String lang) {
        this.guildId = guildId;
        this.lang = lang;
    }
}
