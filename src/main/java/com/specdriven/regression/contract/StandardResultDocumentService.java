package com.specdriven.regression.contract;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

public final class StandardResultDocumentService {

    private static final String RESULT_FILE_NAME = "result.json";

    private final ObjectMapper mapper;
    private final MoveStrategy moveStrategy;

    public StandardResultDocumentService() {
        this(new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT), Files::move);
    }

    StandardResultDocumentService(ObjectMapper mapper) {
        this(mapper, Files::move);
    }

    StandardResultDocumentService(ObjectMapper mapper, MoveStrategy moveStrategy) {
        this.mapper = mapper;
        this.moveStrategy = moveStrategy;
    }

    public Path augmentLeafResult(
            Path resultJson,
            Path summaryJson,
            String completionStatus,
            String terminationReason) {
        try {
            Path resultPath = requireExisting(resultJson, "result");
            Path resultDirectory = resultPath.getParent();
            Path summaryPath = requireExisting(summaryJson, "summary");
            if (resultDirectory == null || !summaryPath.startsWith(resultDirectory)) {
                throw new IllegalArgumentException(
                        "suite summary real path must be inside the result directory real path");
            }
            Map<String, Object> result = mapper.readValue(resultPath.toFile(), new TypeReference<>() {});
            writeAtomicallyText(resultPath, augmentJsonText(
                    Files.readString(resultPath), completionStatus, terminationReason,
                    resultDirectory.relativize(summaryPath).toString().replace('\\', '/')));
            return resultJson;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path requireExisting(Path path, String artifact) throws IOException {
        Path absolutePath = path.toAbsolutePath().normalize();
        if (!Files.exists(absolutePath)) {
            throw new IllegalArgumentException("augmentLeafResult requires an existing " + artifact + " file");
        }
        Path realPath = absolutePath.toRealPath();
        Path lexicalParent = absolutePath.getParent();
        if (lexicalParent == null || !realPath.startsWith(lexicalParent.toRealPath())) {
            throw new IllegalArgumentException(
                    artifact + " real path must be inside the result directory real path");
        }
        return realPath;
    }

    public Path writeAggregationResult(Path runDir, Map<String, Object> completeResult) {
        Path resultJson = runDir.toAbsolutePath().normalize().resolve(RESULT_FILE_NAME);
        try {
            Files.createDirectories(resultJson.getParent());
            return writeAtomically(resultJson, completeResult);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path writeAtomically(Path destination, Map<String, Object> document) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), destination.getFileName() + ".", ".tmp");
        try {
            mapper.writer(new ResultPrettyPrinter()).writeValue(temporary.toFile(), document);
            try {
                moveStrategy.move(
                        temporary,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                moveStrategy.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            return destination;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private String augmentJsonText(
            String original,
            String completionStatus,
            String terminationReason,
            String summaryRef) throws IOException {
        String trimmed = original.stripTrailing();
        if (!trimmed.endsWith("}")) {
            throw new IllegalArgumentException("result JSON must be an object");
        }
        String body = trimmed.substring(0, trimmed.length() - 1).stripTrailing();
        String comma = body.endsWith("{") ? "" : ",";
        return body + comma + "\n"
                + "  \"result_contract_version\": \"v0.3\",\n"
                + "  \"completion_status\": " + mapper.writeValueAsString(completionStatus) + ",\n"
                + "  \"termination_reason\": " + mapper.writeValueAsString(terminationReason) + ",\n"
                + "  \"suite_summary_ref\": " + mapper.writeValueAsString(summaryRef) + "\n}\n";
    }

    private Path writeAtomicallyText(Path destination, String document) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), destination.getFileName() + ".", ".tmp");
        try {
            Files.writeString(temporary, document);
            try {
                moveStrategy.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                moveStrategy.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            return destination;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @FunctionalInterface
    interface MoveStrategy {
        void move(Path source, Path target, CopyOption... options) throws IOException;
    }

    private static final class ResultPrettyPrinter extends DefaultPrettyPrinter {
        ResultPrettyPrinter() {
            super();
        }

        private ResultPrettyPrinter(ResultPrettyPrinter base) {
            super(base);
        }

        @Override
        public DefaultPrettyPrinter createInstance() {
            return new ResultPrettyPrinter(this);
        }

        @Override
        public void writeObjectFieldValueSeparator(JsonGenerator generator) throws IOException {
            generator.writeRaw(": ");
        }
    }
}
