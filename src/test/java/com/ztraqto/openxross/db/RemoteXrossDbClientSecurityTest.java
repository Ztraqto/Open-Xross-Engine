package com.ztraqto.openxross.db;

import com.sun.net.httpserver.HttpServer;
import com.ztraqto.openxross.api.database.XrossDbException;
import com.ztraqto.openxross.config.XrossDbBackend;
import com.ztraqto.openxross.config.XrossDbConfiguration;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertThrows;

class RemoteXrossDbClientSecurityTest {

    @Test
    void rejectsChunkedResponseLargerThanMaximum() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] oversized = new byte[RemoteXrossDbClient.MAX_RESPONSE_BYTES + 1];
        server.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(200, 0); // chunked, no trusted Content-Length bound
            exchange.getResponseBody().write(oversized);
            exchange.close();
        });
        server.start();
        try {
            var config = XrossDbConfiguration.builder()
                    .backend(XrossDbBackend.MEMORY)
                    .serverUrl("http://127.0.0.1")
                    .port(server.getAddress().getPort())
                    .authToken("x".repeat(32))
                    .readRetryCount(0)
                    .build();
            var client = new RemoteXrossDbClient(config);
            assertThrows(XrossDbException.class, client::verifyConnection);
        } finally {
            server.stop(0);
        }
    }
}
