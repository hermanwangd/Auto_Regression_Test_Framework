package com.specdriven.regression.contract.v03.adapter;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.specdriven.regression.contract.v03.V03ExecutionContext;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/** All remote authority, including the host key, is supplied by the runner environment. */
final class SftpRunnerConnection implements AutoCloseable {
    static final Map<String, Object> BINDINGS = Map.of(
            "endpoint_ref", "env://REGRESS_SFTP_ENDPOINT", "username_ref", "env://REGRESS_SFTP_USERNAME",
            "private_key_ref", "env://REGRESS_SFTP_PRIVATE_KEY", "known_hosts_ref", "env://REGRESS_SFTP_KNOWN_HOSTS",
            "root_ref", "env://REGRESS_SFTP_ROOT");
    private static final int CONNECT_MS = 3_000;
    final ChannelSftp channel;
    final String root;
    private final Session session;
    private final Timer deadline;

    private SftpRunnerConnection(Session session, ChannelSftp channel, String root, Timer deadline) {
        this.session = session;
        this.channel = channel;
        this.root = root;
        this.deadline = deadline;
    }

    static SftpRunnerConnection open(V03ExecutionContext context, String target, int timeoutMs)
            throws JSchException, SftpException {
        if (context.targets().get(target) == null || !BINDINGS.equals(context.targets().get(target).bindings())) {
            throw new IllegalArgumentException("Expected SFTP runner bindings.");
        }
        Map<String, String> env = context.environment();
        URI endpoint = URI.create(required(env, "REGRESS_SFTP_ENDPOINT", 300));
        if (!"sftp".equals(endpoint.getScheme()) || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !endpoint.getPath().isEmpty() || endpoint.getPort() == 0 || endpoint.getPort() > 65535) {
            throw new IllegalArgumentException("Expected an SFTP origin.");
        }
        String root = required(env, "REGRESS_SFTP_ROOT", 512);
        if (!root.matches("(/[A-Za-z0-9._-]+)+") || Arrays.stream(root.substring(1).split("/"))
                .anyMatch(segment -> segment.equals(".") || segment.equals(".."))) {
            throw new IllegalArgumentException("Expected an absolute SFTP fixture directory.");
        }
        String user = required(env, "REGRESS_SFTP_USERNAME", 128);
        String hosts = required(env, "REGRESS_SFTP_KNOWN_HOSTS", 32 * 1024);
        byte[] key = required(env, "REGRESS_SFTP_PRIVATE_KEY", 32 * 1024).getBytes(StandardCharsets.UTF_8);
        JSch client = new JSch();
        client.setKnownHosts(new ByteArrayInputStream(hosts.getBytes(StandardCharsets.UTF_8)));
        try {
            client.addIdentity("runner-key", key, null, null);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
        Session session = client.getSession(user, endpoint.getHost(), endpoint.getPort() < 0 ? 22 : endpoint.getPort());
        session.setConfig("StrictHostKeyChecking", "yes");
        session.setConfig("PreferredAuthentications", "publickey");
        session.setDaemonThread(true);
        session.setTimeout(CONNECT_MS);
        // A peer can keep a TCP connection alive while withholding an SFTP response.
        Timer deadline = new Timer("sftp-operation-deadline", true);
        deadline.schedule(new TimerTask() {
            @Override public void run() { session.disconnect(); }
        }, timeoutMs);
        try {
            session.connect(CONNECT_MS);
            ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(CONNECT_MS);
            if (!root.equals(channel.realpath(root)) || !channel.lstat(root).isDir()) {
                throw new IllegalArgumentException("The runner root must be an existing canonical directory.");
            }
            return new SftpRunnerConnection(session, channel, root, deadline);
        } catch (JSchException | SftpException | RuntimeException error) {
            deadline.cancel();
            session.disconnect();
            throw error;
        }
    }

    private static String required(Map<String, String> env, String variable, int maxLength) {
        String value = env.get(variable);
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException("Missing or invalid SFTP configuration.");
        }
        return value;
    }

    @Override public void close() {
        try { session.disconnect(); } finally { deadline.cancel(); }
    }
}
