package com.specdriven.regression.provider;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.StandardProtocolFamily;
import java.nio.channels.ServerSocketChannel;

/** A single-family, loopback-only fixture with a bounded accept. */
final class LoopbackTestListener {
    private LoopbackTestListener() { }

    static ServerSocket open(boolean ipv6) throws IOException {
        var channel = ServerSocketChannel.open(ipv6 ? StandardProtocolFamily.INET6 : StandardProtocolFamily.INET);
        try {
            channel.bind(ipv6 ? new InetSocketAddress("::1", 0) : new InetSocketAddress("127.0.0.1", 0), 1);
            ServerSocket socket = channel.socket();
            socket.setSoTimeout(2000);
            return socket;
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }
}
