package com.ztraqto.openxross.launcher;

import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.config.XrossArguments;
import com.ztraqto.openxross.config.XrossLocalConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Minimal standalone launcher for OpenXrossEngine. */
public final class OpenXrossLauncher {
    private static final Logger logger = LoggerFactory.getLogger(OpenXrossLauncher.class);

    private OpenXrossLauncher() {
    }

    public static void main(String[] args) {
        Thread.currentThread().setName("OpenXross-Main");
        try {
            List<String> engineArguments = new ArrayList<>(Arrays.asList(args));
            boolean checkConfiguration = engineArguments.remove("--check-config");
            var configuration = XrossArguments.parse(engineArguments.toArray(String[]::new));
            if (checkConfiguration) {
                logger.info("Xross configuration is valid: {}", XrossLocalConfiguration.sourceFile());
                return;
            }

            XrossEngine engine = new XrossEngine();
            Runtime.getRuntime().addShutdownHook(new Thread(engine::stop, "OpenXross-Shutdown-Hook"));
            engine.start(configuration);
        } catch (Exception exception) {
            logger.error("OpenXrossEngine failed to start.", exception);
            System.exit(1);
        }
    }
}
