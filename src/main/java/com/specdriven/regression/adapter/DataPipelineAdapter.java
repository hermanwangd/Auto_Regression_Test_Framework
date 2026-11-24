package com.specdriven.regression.adapter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class DataPipelineAdapter implements ExecutionAdapter {
    private static final String WRAPPER = "/bin/sh -c '";

    // These are the literal fixture operations used by legacy mappings. Product executables use cli_process.
    private sealed interface Operation permits Output, Pause, Exit {}
    private record Output(String literal, boolean stderr) implements Operation {}
    private record Pause(int seconds) implements Operation {}
    private record Exit(int code) implements Operation {}

    private static IllegalArgumentException unsupported() {
        return new IllegalArgumentException("Unsupported legacy fixture command. Use cli_process with a pinned executable and literal argv for product commands.");
    }

    private static List<Operation> parse(String command) {
        if (command == null || command.length() > 4096 || command.chars().anyMatch(Character::isISOControl)) {
            throw unsupported();
        }
        String body = command.strip();
        if (body.startsWith(WRAPPER) && body.endsWith("'") && body.length() > WRAPPER.length()) {
            body = body.substring(WRAPPER.length(), body.length() - 1);
            if (body.contains("'")) throw unsupported();
        }
        String[] statements = body.split(";", -1);
        if (statements.length > 16) throw unsupported();
        List<Operation> operations = new ArrayList<>();
        for (String statement : statements) {
            String text = statement.strip();
            boolean stderr = text.endsWith(" >&2");
            if (stderr) text = text.substring(0, text.length() - " >&2".length()).stripTrailing();
            if (text.startsWith("echo ")) {
                String literal = text.substring(5).stripLeading();
                if (literal.startsWith("-") || !literal.replace("\\\"", "").matches("[a-zA-Z0-9_ .,:/{}=@+-]+")) throw unsupported();
                literal = literal.replace("\\\"", "\"").replaceAll(" +", " ");
                operations.add(new Output(literal + "\n", stderr));
            } else if (text.startsWith("printf '") && text.endsWith("'") && text.length() >= 9) {
                String literal = text.substring(8, text.length() - 1);
                if (!literal.replace("\\n", "").matches("[a-zA-Z0-9_ .,:/{}=@+-]*")) throw unsupported();
                operations.add(new Output(literal.replace("\\n", "\n"), stderr));
            } else if (!stderr && text.matches("sleep [0-9]{1,3}")) {
                int seconds = Integer.parseInt(text.substring(6));
                if (seconds > 300) throw unsupported();
                operations.add(new Pause(seconds));
            } else if (!stderr && text.matches("exit [0-9]{1,3}")) {
                int code = Integer.parseInt(text.substring(5));
                if (code > 255) throw unsupported();
                operations.add(new Exit(code));
            } else {
                throw unsupported();
            }
        }
        return operations;
    }

    @Override
    public AdapterExecutionResult execute(AdapterExecutionRequest request) {
        List<Operation> operations = parse(request.command());
        try {
            if (!Files.isDirectory(request.workingDirectory())) throw new IOException("Missing fixture working directory.");
            Files.createDirectories(request.stdoutLog().getParent());
            Files.createDirectories(request.stderrLog().getParent());
            Files.createDirectories(request.actualOutput().getParent());
            Files.write(request.stdoutLog(), new byte[0]);
            Files.write(request.stderrLog(), new byte[0]);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(request.timeoutSeconds() <= 0 ? 300 : request.timeoutSeconds());
            int exitCode = 0;
            for (Operation operation : operations) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    exitCode = -1;
                    break;
                }
                if (operation instanceof Exit exit) {
                    exitCode = exit.code();
                    break;
                }
                if (operation instanceof Output output) {
                    Files.writeString(output.stderr() ? request.stderrLog() : request.stdoutLog(),
                            output.literal(), StandardOpenOption.APPEND);
                } else if (operation instanceof Pause pause) {
                    long delay = TimeUnit.SECONDS.toNanos(pause.seconds());
                    TimeUnit.NANOSECONDS.sleep(Math.min(delay, remaining));
                    if (deadline - System.nanoTime() <= 0) {
                        exitCode = -1;
                        break;
                    }
                }
            }
            Files.copy(request.stdoutLog(), request.actualOutput(), StandardCopyOption.REPLACE_EXISTING);
            return new AdapterExecutionResult(exitCode, exitCode == -1, request.stdoutLog(), request.stderrLog(), request.actualOutput());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to execute provider command.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Provider execution interrupted.", e);
        }
    }
}
