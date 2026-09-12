package com.ztraqto.openxross.service;

import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.api.IService;
import com.ztraqto.openxross.api.storage.IDatabaseOwner;
import com.ztraqto.openxross.core.security.BotBanData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class BotBanService implements IService, IDatabaseOwner {

    private static final Logger logger = LoggerFactory.getLogger(BotBanService.class);

    private final StorageService storageService;
    private final Set<Long> bannedUserIds = ConcurrentHashMap.newKeySet();
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile long lastRefresh;

    public BotBanService(StorageService storageService) {
        this.storageService = storageService;
    }

    @Override
    public String getDatabaseContextId() {
        return "security";
    }

    @Override
    public void init(XrossEngine engine) {
        refresh();
        logger.info("Loaded {} banned users from XrossDB.", bannedUserIds.size());
    }

    @Override
    public void shutdown() {
        bannedUserIds.clear();
        lastRefresh = 0L;
    }

    @Override
    public String getName() {
        return "BotBanService";
    }

    public boolean isBanned(long userId) {
        if (System.currentTimeMillis() - lastRefresh >= 2000L) {
            refresh();
        }
        return bannedUserIds.contains(userId);
    }

    public void banUser(long userId, String reason, String by) {
        if (bannedUserIds.contains(userId)) {
            return;
        }

        BotBanData data = new BotBanData(userId, reason, System.currentTimeMillis(), by);
        if (storageService.getWrider().saveQL(this, data)) {
            bannedUserIds.add(userId);
            logger.info("User {} has been banned. Reason: {}", userId, reason);
        }
    }

    public void unbanUser(long userId) {
        if (!bannedUserIds.contains(userId)) {
            return;
        }

        if (storageService.getWrider().deleteQL(this, BotBanData.class, userId)) {
            bannedUserIds.remove(userId);
            logger.info("User {} has been unbanned.", userId);
        }
    }

    private synchronized void refresh() {
        if (System.currentTimeMillis() - lastRefresh < 2000L && lastRefresh != 0L) {
            return;
        }
        try {
            Set<Long> loaded = ConcurrentHashMap.newKeySet();
            storageService.getClient().scan("security", "wrider_botbandata")
                    .forEach(record -> loaded.add(mapper.convertValue(record.payload(), BotBanData.class).userId));
            bannedUserIds.clear();
            bannedUserIds.addAll(loaded);
            lastRefresh = System.currentTimeMillis();
        } catch (Exception exception) {
            logger.warn("Could not refresh Bot bans from XrossDB; keeping the previous snapshot.", exception);
        }
    }
}
