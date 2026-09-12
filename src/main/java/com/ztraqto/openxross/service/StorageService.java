package com.ztraqto.openxross.service;

import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.api.IService;
import com.ztraqto.openxross.api.database.XrossDbClient;
import com.ztraqto.openxross.api.plugin.XrossPlugin;
import com.ztraqto.openxross.api.storage.IDatabaseOwner;
import com.ztraqto.openxross.core.storage.WriderSQL;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

public class StorageService implements IService {

    private final XrossDbClient client;
    private WriderSQL wrider;

    public StorageService(XrossDbClient client) {
        this.client = client;
    }

    @Override
    public void init(XrossEngine engine) {
        wrider = new WriderSQL(client);
    }

    @Override
    public void shutdown() {
        wrider = null;
    }

    public XrossDbClient getClient() {
        return client;
    }

    public WriderSQL getWrider() {
        if (wrider == null) {
            throw new IllegalStateException("StorageService is not initialized.");
        }
        return wrider;
    }

    @Deprecated
    public Connection getConnection() throws SQLException {
        return unsupportedDirectConnection();
    }

    @Deprecated
    public Connection getConnection(XrossPlugin plugin) throws SQLException {
        return unsupportedDirectConnection();
    }

    @Deprecated
    public Connection getConnection(String contextId) throws SQLException {
        return unsupportedDirectConnection();
    }

    @Deprecated
    public Connection getConnection(IDatabaseOwner owner) throws SQLException {
        return unsupportedDirectConnection();
    }

    @Override
    public String getName() {
        return "StorageService";
    }

    private static Connection unsupportedDirectConnection() throws SQLException {
        throw new SQLFeatureNotSupportedException(
                "Direct JDBC access is unavailable. Use XrossDbClient or WriderSQL so Bot and remote XrossDB modes behave identically."
        );
    }
}
