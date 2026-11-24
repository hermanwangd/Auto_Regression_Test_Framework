package com.specdriven.regression.provider;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.StandardProtocolFamily;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeUnit;

/** Direct, plaintext TCP between numeric loopback addresses in the same IP family. */
public final class LoopbackTcpTransport {
    private LoopbackTcpTransport() { }

    private enum Loopback {
        IPV4(StandardProtocolFamily.INET), IPV6(StandardProtocolFamily.INET6);

        private final StandardProtocolFamily family;

        Loopback(StandardProtocolFamily family) {
            this.family = family;
        }

        static Loopback forHost(String host) throws IOException {
            if ("127.0.0.1".equals(host)) return IPV4;
            if ("::1".equals(host) || "[::1]".equals(host)) return IPV6;
            throw new IOException("TCP connection requires numeric loopback.");
        }

        InetSocketAddress atPort(int port) {
            // Numeric IP literals are parsed directly. No name or address from a binding reaches this API.
            return switch (this) {
                case IPV4 -> new InetSocketAddress("127.0.0.1", port);
                case IPV6 -> new InetSocketAddress("::1", port);
            };
        }
    }

    /** Open and close a local connection without exchanging application bytes. */
    public static void checkConnection(String host, int port, int timeoutMs) throws IOException {
        try (Socket connection = connect(host, port, timeoutMs, timeoutMs)) {
            // A completed TCP handshake is the entire readiness check.
        }
    }

    /** Returns a connected blocking socket with a bounded read timeout. The caller owns it. */
    public static Socket connect(String host, int port, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        Loopback loopback = Loopback.forHost(host);
        if (port < 1 || port > 65535 || connectTimeoutMs < 1 || readTimeoutMs < 1) {
            throw new IOException("TCP connection requires a port in 1..65535 and positive connect and read timeouts.");
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(connectTimeoutMs);
        SocketChannel channel;
        try {
            channel = SocketChannel.open(loopback.family);
        } catch (UnsupportedOperationException error) {
            throw new IOException("Requested loopback address family is unavailable.", error);
        }
        boolean handedOff = false;
        try {
            channel.bind(loopback.atPort(0));
            channel.configureBlocking(false);
            if (!channel.connect(loopback.atPort(port))) {
                try (Selector selector = Selector.open()) {
                    channel.register(selector, SelectionKey.OP_CONNECT);
                    while (!channel.finishConnect()) {
                        selector.select(remainingMillis(deadline));
                        selector.selectedKeys().clear();
                    }
                }
            }
            channel.configureBlocking(true);
            Socket connection = channel.socket();
            connection.setSoTimeout(readTimeoutMs);
            remainingMillis(deadline);
            handedOff = true;
            return connection;
        } finally {
            if (!handedOff) channel.close();
        }
    }

    static long remainingMillis(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Loopback TCP connection interrupted.");
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new SocketTimeoutException("Loopback TCP connect deadline exceeded.");
        }
        // Selector.select(0) waits indefinitely, so round any positive remainder up.
        return 1 + TimeUnit.NANOSECONDS.toMillis(remaining - 1);
    }
}
