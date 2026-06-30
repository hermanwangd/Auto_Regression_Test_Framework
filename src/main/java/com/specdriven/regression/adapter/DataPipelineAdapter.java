package com.specdriven.regression.adapter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class DataPipelineAdapter implements ExecutionAdapter {

    @Override
    public AdapterExecutionResult execute(AdapterExecutionRequest request) {
        try {
            Files.createDirectories(request.stdoutLog().getParent());
            Files.createDirectories(request.stderrLog().getParent());
            Files.createDirectories(request.actualOutput().getParent());
            Process process = new ProcessBuilder("/bin/sh", "-c", request.command())
                    .directory(request.workingDirectory().toFile())
                    .redirectOutput(request.stdoutLog().toFile())
                    .redirectError(request.stderrLog().toFile())
                    .start();

            boolean completed = process.waitFor(timeoutSeconds(request), TimeUnit.SECONDS);
            int exitCode;
            boolean timeout = !completed;
            if (timeout) {
                process.destroyForcibly();
                process.waitFor(Duration.ofSeconds(1).toMillis(), TimeUnit.MILLISECONDS);
                exitCode = -1;
            } else {
                exitCode = process.exitValue();
            }
            if (Files.exists(request.stdoutLog())) {
                Files.copy(request.stdoutLog(), request.actualOutput(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return new AdapterExecutionResult(
                    exitCode,
                    timeout,
                    request.stdoutLog(),
                    request.stderrLog(),
                    request.actualOutput());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to execute provider command.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Provider execution interrupted.", e);
        }
    }

    private int timeoutSeconds(AdapterExecutionRequest request) {
        return request.timeoutSeconds() <= 0 ? 300 : request.timeoutSeconds();
    }
}
