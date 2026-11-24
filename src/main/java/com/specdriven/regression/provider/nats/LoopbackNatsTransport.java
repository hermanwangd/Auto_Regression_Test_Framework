package com.specdriven.regression.provider.nats;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.specdriven.regression.provider.LoopbackTcpTransport;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;

/** The plaintext NATS transport is restricted to a runner-local broker or tunnel. */
public final class LoopbackNatsTransport {
    private static final ObjectReader SERVER_INFO = new ObjectMapper().reader()
            .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private LoopbackNatsTransport() { }

    public static Socket connect(URI uri, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        if (!"nats".equals(uri.getScheme())) {
            throw new IOException("Loopback NATS requires the nats URI scheme.");
        }
        int port = uri.getPort() == -1 ? 4222 : uri.getPort();
        return LoopbackTcpTransport.connect(uri.getHost(), port, connectTimeoutMs, readTimeoutMs);
    }

    public static void requirePlaintextServerInfo(String line) throws IOException {
        if (!line.startsWith("INFO ")) {
            throw new IOException("NATS server did not send INFO before command.");
        }
        JsonNode info;
        try {
            info = SERVER_INFO.readTree(line.substring(5));
        } catch (JsonProcessingException error) {
            throw new IOException("Invalid NATS server INFO.");
        }
        if (info == null || !info.isObject()) {
            throw new IOException("Invalid NATS server INFO.");
        }
        if ((info.has("tls_required") && !BooleanNode.FALSE.equals(info.get("tls_required")))
                || (info.has("tls_verify") && !BooleanNode.FALSE.equals(info.get("tls_verify")))) {
            throw new IOException("NATS server requires TLS. Configure a secure local tunnel.");
        }
    }
}
