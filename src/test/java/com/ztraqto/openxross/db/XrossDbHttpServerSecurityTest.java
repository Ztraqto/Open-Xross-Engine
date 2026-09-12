package com.ztraqto.openxross.db;

import com.ztraqto.openxross.config.XrossDbBackend;
import com.ztraqto.openxross.config.XrossDbConfiguration;
import org.junit.jupiter.api.Test;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class XrossDbHttpServerSecurityTest {
    @Test void directServerApiRejectsPlaintextExternalBindingEvenWithAuthentication() {
        var config = XrossDbConfiguration.builder().backend(XrossDbBackend.MEMORY)
                .bindAddress("0.0.0.0").authToken("x".repeat(32)).build();
        var server = new XrossDbHttpServer(config, new XrossDbGateway(MapXrossDbRepository.memory()));
        assertThrows(IllegalArgumentException.class, server::start);
    }

    @Test void explicitPrivateNetworkOverrideStillRequiresAuthentication() {
        var config = XrossDbConfiguration.builder().backend(XrossDbBackend.MEMORY)
                .bindAddress("0.0.0.0").allowInsecureExternalHttp(true).authToken(null).build();
        var server = new XrossDbHttpServer(config, new XrossDbGateway(MapXrossDbRepository.memory()));
        assertThrows(IllegalArgumentException.class, server::start);
    }

    @Test void closesStalledBodiesAndKeepsHealthEndpointAuthenticated() throws Exception {
        int port;
        try (ServerSocket reservation = new ServerSocket(0)) { port = reservation.getLocalPort(); }
        String token = "test-only-" + "x".repeat(32);
        var config = XrossDbConfiguration.builder().backend(XrossDbBackend.MEMORY)
                .bindAddress("127.0.0.1").port(port).authToken(token).requestTimeout(Duration.ofMillis(500)).build();
        var server = new XrossDbHttpServer(config, new XrossDbGateway(MapXrossDbRepository.memory()));
        server.start();
        try {
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3000);
                String partial = "POST /v1/records/read HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "
                        + token + "\r\nContent-Length: 100\r\n\r\n{";
                socket.getOutputStream().write(partial.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                try {
                    assertEquals(-1, socket.getInputStream().read(), "Server must close an incomplete body at its deadline");
                } catch (java.net.SocketException resetByPeer) {
                    // Windows may report a TCP reset when the server closes an unread body.
                }
            }
            var client = HttpClient.newHttpClient();
            URI health = URI.create("http://127.0.0.1:" + port + "/health");
            assertEquals(401, client.send(HttpRequest.newBuilder(health).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(200, client.send(HttpRequest.newBuilder(health).header("Authorization", "Bearer " + token)
                    .GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        } finally {
            server.stop();
        }
    }
}
