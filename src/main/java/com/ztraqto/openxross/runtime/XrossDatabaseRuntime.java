package com.ztraqto.openxross.runtime;

import com.ztraqto.openxross.api.database.XrossDbClient;
import com.ztraqto.openxross.api.runtime.XrossRuntime;
import com.ztraqto.openxross.config.XrossDbConfiguration;
import com.ztraqto.openxross.db.InProcessXrossDbClient;
import com.ztraqto.openxross.db.MapXrossDbRepository;
import com.ztraqto.openxross.db.LocalToPostgresMigrator;
import com.ztraqto.openxross.db.PluginSplitXrossDbRepository;
import com.ztraqto.openxross.db.PostgresAutoSetup;
import com.ztraqto.openxross.db.XrossDbRepository;
import com.ztraqto.openxross.db.XrossDbGateway;
import com.ztraqto.openxross.db.XrossDbHttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class XrossDatabaseRuntime implements XrossRuntime {

    private static final Logger logger = LoggerFactory.getLogger(XrossDatabaseRuntime.class);

    private final XrossDbConfiguration configuration;
    private XrossDbRepository repository;
    private XrossDbGateway gateway;
    private XrossDbHttpServer server;
    private boolean running;

    public XrossDatabaseRuntime(XrossDbConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public synchronized void start() throws Exception {
        if (running) {
            throw new IllegalStateException("XrossDB runtime is already running.");
        }

        configuration.validateServerSecurity();
        try {
            repository = switch (configuration.backend()) {
                case POSTGRES -> {
                    PostgresAutoSetup.run(configuration);
                    PluginSplitXrossDbRepository postgres = new PluginSplitXrossDbRepository(
                            configuration.postgresUrl(),
                            configuration.postgresUser(),
                            configuration.postgresPassword());
                    try {
                        if (configuration.migrateLocalToPostgres()) {
                            LocalToPostgresMigrator.run(Path.of(configuration.localPath()), postgres);
                        }
                    } catch (RuntimeException exception) {
                        postgres.close();
                        throw exception;
                    }
                    yield postgres;
                }
                case LOCAL -> MapXrossDbRepository.local(Path.of(configuration.localPath()));
                case MEMORY -> MapXrossDbRepository.memory();
            };
            gateway = new XrossDbGateway(repository);
            server = new XrossDbHttpServer(configuration, gateway);
            server.start();
            running = true;
            logger.info("XrossDB runtime started with {} backend.", configuration.backend().name().toLowerCase());
        } catch (Exception exception) {
            stop();
            throw exception;
        }
    }

    public synchronized XrossDbClient createInProcessClient() {
        if (!running || gateway == null) {
            throw new IllegalStateException("XrossDB runtime must be running before creating a client.");
        }
        return new InProcessXrossDbClient(gateway);
    }

    @Override
    public synchronized void stop() {
        if (server != null) {
            server.stop();
            server = null;
        }
        if (repository != null) {
            repository.close();
            repository = null;
        }
        gateway = null;

        if (running) {
            logger.info("XrossDB runtime stopped.");
        }
        running = false;
    }

    @Override
    public synchronized boolean isRunning() {
        return running;
    }
}
