package com.specdriven.regression.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LoopbackTcpTransportTest {
    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "[::1]"})
    void pinsBothEndsAndHandsOffBlockingStreamsWithReadTimeout(String host) throws Exception {
        byte[] addressBytes = host.equals("127.0.0.1") ? new byte[]{127, 0, 0, 1}
                : new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
        InetAddress loopback = InetAddress.getByAddress(addressBytes);
        assumeTrue(NetworkInterface.getByInetAddress(loopback) != null, "Loopback family unavailable");
        try (ServerSocket listener = LoopbackTestListener.open(addressBytes.length == 16);
                Socket client = LoopbackTcpTransport.connect(host, listener.getLocalPort(), 2000, 50);
                Socket peer = listener.accept()) {
            assertThat(client.getLocalAddress()).isEqualTo(loopback);
            assertThat(client.getInetAddress()).isEqualTo(loopback);
            assertThat(peer.getLocalAddress()).isEqualTo(loopback);
            assertThat(client.getChannel().isBlocking()).isTrue();
            assertThatThrownBy(() -> client.getInputStream().read()).isInstanceOf(SocketTimeoutException.class);
        }
    }

    @Test
    void readinessClosesAfterTheHandshakeWithoutWriting() throws Exception {
        try (ServerSocket listener = LoopbackTestListener.open(false)) {
            LoopbackTcpTransport.checkConnection("127.0.0.1", listener.getLocalPort(), 2000);
            try (Socket peer = listener.accept()) {
                peer.setSoTimeout(1000);
                assertThat(peer.getInputStream().read()).isEqualTo(-1);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "192.0.2.1", "0.0.0.0", "[::]", "127.0.0.2"})
    void rejectsEverythingOutsideTheFixedLocalAddresses(String host) {
        assertThatThrownBy(() -> LoopbackTcpTransport.checkConnection(host, 4222, 100))
                .isInstanceOf(IOException.class).hasMessageContaining("numeric loopback");
    }

    @Test
    void rejectsInvalidPortsAndUnboundedTimeoutsBeforeConnecting() {
        assertThatThrownBy(() -> LoopbackTcpTransport.checkConnection("127.0.0.1", 65536, 100))
                .isInstanceOf(IOException.class).hasMessageContaining("port");
        assertThatThrownBy(() -> LoopbackTcpTransport.connect("127.0.0.1", 4222, 0, 100))
                .isInstanceOf(IOException.class).hasMessageContaining("positive");
        assertThatThrownBy(() -> LoopbackTcpTransport.connect("127.0.0.1", 4222, 100, 0))
                .isInstanceOf(IOException.class).hasMessageContaining("positive");
    }

    @Test
    void expiredDeadlineFailsBeforeAnotherSelectorWait() {
        assertThatThrownBy(() -> LoopbackTcpTransport.remainingMillis(System.nanoTime() - 1))
                .isInstanceOf(SocketTimeoutException.class);
    }
}
