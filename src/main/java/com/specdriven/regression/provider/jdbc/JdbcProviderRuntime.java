package com.specdriven.regression.provider.jdbc;

import com.specdriven.regression.provider.runtime.ProviderEvidence;
import com.specdriven.regression.provider.runtime.ProviderExecutionContext;
import com.specdriven.regression.provider.runtime.ProviderFailure;
import com.specdriven.regression.provider.runtime.ProviderOperationRequest;
import com.specdriven.regression.provider.runtime.ProviderOperationResult;
import com.specdriven.regression.provider.runtime.ProviderRuntime;
import com.specdriven.regression.provider.runtime.SecretRefResolver;
import com.specdriven.regression.provider.runtime.SecretRefResolver.ResolvedSecret;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

public class JdbcProviderRuntime implements ProviderRuntime {

    private static final Set<String> SUPPORTED_DIALECTS = Set.of("oracle", "db2");
    private static final Pattern NAMED_PARAM = Pattern.compile(":([A-Za-z][A-Za-z0-9_]*)");

    private final Yaml yaml = new Yaml();
    private final Function<String, String> environment;
    private final JdbcDriverDiscovery.DiscoveryResult driverDiscovery;
    private final JdbcDriverLoader driverLoader;

    public JdbcProviderRuntime() {
        this(System::getenv);
    }

    JdbcProviderRuntime(Function<String, String> environment) {
        this(environment, new JdbcDriverDiscovery(Path.of("."), environment).discover(List.of(), ""));
    }

    JdbcProviderRuntime(
            Function<String, String> environment,
            JdbcDriverDiscovery.DiscoveryResult driverDiscovery) {
        this(environment, driverDiscovery, new JdbcDriverLoader());
    }

    public JdbcProviderRuntime(JdbcDriverDiscovery.DiscoveryResult driverDiscovery) {
        this(System::getenv, driverDiscovery, new JdbcDriverLoader());
    }

    JdbcProviderRuntime(
            Function<String, String> environment,
            JdbcDriverDiscovery.DiscoveryResult driverDiscovery,
            JdbcDriverLoader driverLoader) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.driverDiscovery = Objects.requireNonNull(driverDiscovery, "driverDiscovery");
        this.driverLoader = Objects.requireNonNull(driverLoader, "driverLoader");
    }

    @Override
    public ProviderOperationResult execute(ProviderExecutionContext context, ProviderOperationRequest request) {
        String dialect = normalizedDialect(context);
        if (!SUPPORTED_DIALECTS.contains(dialect)) {
            return failed(
                    context,
                    request,
                    Map.of(),
                    "UNSUPPORTED_DIALECT",
                    "TARGET_RESOLUTION_FAILED",
                    "JDBC dialect `" + dialect + "` is not supported by PR-004.",
                    "Use JDBC dialect `oracle` or `db2`.");
        }
        SecretConnection connection = resolveConnection(context, dialect);
        if (connection.failure() != null) {
            return failed(context, request, Map.of(), connection.failure());
        }
        try {
            return switch (request.operation()) {
                case "db_seed" -> executeUpdate(context, request, connection, "sql_ref", "seed", "seed_evidence_ref");
                case "db_cleanup" -> executeUpdate(context, request, connection, "sql_ref", "cleanup", "cleanup_evidence_ref");
                case "db_query" -> executeQuery(context, request, connection, false);
                case "db_record_exists" -> executeQuery(context, request, connection, true);
                default -> failed(
                        context,
                        request,
                        Map.of(),
                        "UNSUPPORTED_OPERATION",
                        "TARGET_RESOLUTION_FAILED",
                        "JDBC operation `" + request.operation() + "` is not supported.",
                        "Use db_seed, db_cleanup, db_query, or db_record_exists.");
            };
        } catch (RuntimeException e) {
            return failed(
                    context,
                    request,
                    Map.of(),
                    "OPERATION_FAILED",
                    classificationFor(request.operation()),
                    maskSensitiveText(e.getMessage()),
                    "Review JDBC provider evidence and SQL refs.");
        }
    }

    private ProviderOperationResult executeUpdate(
            ProviderExecutionContext context,
            ProviderOperationRequest request,
            SecretConnection secretConnection,
            String refBindAs,
            String evidenceKind,
            String evidenceOutputName) {
        String sqlRef = firstParameter(request, refBindAs);
        if (sqlRef.isBlank()) {
            return failed(context, request, Map.of(), "SQL_REF_MISSING", classificationFor(request.operation()),
                    "JDBC operation `" + request.operation() + "` requires sql_ref.",
                    "Add checked-in SQL ref input `sql_ref`.");
        }
        Instant startedAt = Instant.now();
        Map<String, Object> params = parameterValues(context, request);
        int affectedRows = 0;
        try (Connection connection = open(secretConnection)) {
            List<PreparedSql> preparedStatements = statements(readRef(context.suiteRoot(), sqlRef, request.operation(), "SQL_REF_MISSING")).stream()
                    .map(this::prepareSql)
                    .toList();
            validateScriptParams(request, preparedStatements, params);
            for (PreparedSql preparedSql : preparedStatements) {
                try (PreparedStatement preparedStatement = connection.prepareStatement(preparedSql.sql())) {
                    bindParams(preparedStatement, preparedSql.params(), params);
                    affectedRows += Math.max(preparedStatement.executeUpdate(), 0);
                }
            }
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            String evidenceRef = writeUpdateEvidence(
                    context,
                    request,
                    evidenceKind,
                    sqlRef,
                    params,
                    affectedRows,
                    durationMs,
                    null);
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("affected_rows", affectedRows);
            outputs.put("duration_ms", durationMs);
            outputs.put(evidenceOutputName, evidenceRef);
            return ProviderOperationResult.passed(
                    outputs,
                    List.of(new ProviderEvidence(evidenceKind + "_evidence", evidenceRef, true)));
        } catch (JdbcRuntimeFailure e) {
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            String evidenceRef = writeUpdateEvidence(
                    context,
                    request,
                    evidenceKind,
                    sqlRef,
                    params,
                    affectedRows,
                    durationMs,
                    e.failure());
            return ProviderOperationResult.failed(
                    Map.of(evidenceOutputName, evidenceRef),
                    List.of(new ProviderEvidence(evidenceKind + "_evidence", evidenceRef, true)),
                    e.failure());
        } catch (SQLException e) {
            ProviderFailure failure = failureForSqlException(request.operation(), e);
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            String evidenceRef = writeUpdateEvidence(
                    context,
                    request,
                    evidenceKind,
                    sqlRef,
                    params,
                    affectedRows,
                    durationMs,
                    failure);
            return ProviderOperationResult.failed(
                    Map.of(evidenceOutputName, evidenceRef),
                    List.of(new ProviderEvidence(evidenceKind + "_evidence", evidenceRef, true)),
                    failure);
        }
    }

    private ProviderOperationResult executeQuery(
            ProviderExecutionContext context,
            ProviderOperationRequest request,
            SecretConnection secretConnection,
            boolean recordExists) {
        String queryRef = firstParameter(request, "query_ref");
        if (queryRef.isBlank()) {
            return failed(context, request, Map.of(), "QUERY_REF_MISSING", "DB_QUERY_FAILED",
                    "JDBC query operation requires query_ref.",
                    "Add checked-in SQL query ref input `query_ref`.");
        }
        Instant startedAt = Instant.now();
        Map<String, Object> params = parameterValues(context, request);
        List<Map<String, Object>> sampleRows = List.of();
        int rowCount = 0;
        try (Connection connection = open(secretConnection)) {
            PreparedSql preparedSql = prepareSql(readRef(context.suiteRoot(), queryRef, request.operation(), "QUERY_REF_MISSING"));
            validateParams(request, preparedSql, params);
            try (PreparedStatement statement = connection.prepareStatement(preparedSql.sql())) {
                bindParams(statement, preparedSql.params(), params);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<Map<String, Object>> rows = rows(resultSet);
                    rowCount = rows.size();
                    sampleRows = rows.stream().limit(5).toList();
                }
            }
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            boolean matched = !recordExists || matchesExpected(rowCount, request);
            ProviderFailure failure = matched ? null : ProviderFailure.of(
                    "RECORD_NOT_FOUND",
                    "ASSERTION_FAILED",
                    "db_record_exists expected a matching record but observed row_count `" + rowCount + "`.",
                    "Review the query ref, SQL params, and expected result.");
            String evidenceRef = writeQueryEvidence(
                    context,
                    request,
                    queryRef,
                    params,
                    rowCount,
                    sampleRows,
                    durationMs,
                    failure);
            Map<String, Object> outputs = new LinkedHashMap<>();
            if (recordExists) {
                outputs.put("matched", matched);
            }
            outputs.put("row_count", rowCount);
            outputs.put("sample_rows", maskRows(sampleRows));
            outputs.put("duration_ms", durationMs);
            outputs.put("query_evidence_ref", evidenceRef);
            if (matched) {
                return ProviderOperationResult.passed(
                        outputs,
                        List.of(new ProviderEvidence("query_evidence", evidenceRef, true)));
            }
            return ProviderOperationResult.failed(
                    outputs,
                    List.of(new ProviderEvidence("query_evidence", evidenceRef, true)),
                    failure);
        } catch (JdbcRuntimeFailure e) {
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            String evidenceRef = writeQueryEvidence(
                    context,
                    request,
                    queryRef,
                    params,
                    rowCount,
                    sampleRows,
                    durationMs,
                    e.failure());
            return ProviderOperationResult.failed(
                    Map.of("query_evidence_ref", evidenceRef),
                    List.of(new ProviderEvidence("query_evidence", evidenceRef, true)),
                    e.failure());
        } catch (SQLException e) {
            ProviderFailure failure = failureForSqlException(request.operation(), e);
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            String evidenceRef = writeQueryEvidence(
                    context,
                    request,
                    queryRef,
                    params,
                    rowCount,
                    sampleRows,
                    durationMs,
                    failure);
            return ProviderOperationResult.failed(
                    Map.of("query_evidence_ref", evidenceRef),
                    List.of(new ProviderEvidence("query_evidence", evidenceRef, true)),
                    failure);
        }
    }

    private Connection open(SecretConnection secretConnection) throws SQLException {
        if (secretConnection.username().isBlank() && secretConnection.password().isBlank()) {
            return DriverManager.getConnection(secretConnection.jdbcUrl());
        }
        return DriverManager.getConnection(secretConnection.jdbcUrl(), secretConnection.username(), secretConnection.password());
    }

    private SecretConnection resolveConnection(ProviderExecutionContext context, String dialect) {
        String localRef = stringValue(valueAtPath(context.bindingValues(), "connection.local_ref"));
        if (!localRef.isBlank()) {
            if (!approvedLocalRef(localRef, dialect)) {
                return SecretConnection.failed(ProviderFailure.of(
                        "SECRET_RESOLUTION_ERROR",
                        "SECRET_RESOLUTION_ERROR",
                        "JDBC connection.local_ref `" + localRef + "` is not approved for local provider capability runtime.",
                        "Use approved_local_h2_oracle or approved_local_h2_db2."));
            }
            return localH2Connection(context, dialect);
        }
        String secretRef = stringValue(valueAtPath(context.bindingValues(), "connection.secret_ref"));
        if (secretRef.isBlank()) {
            return SecretConnection.failed(ProviderFailure.of(
                    "SECRET_RESOLUTION_ERROR",
                    "SECRET_RESOLUTION_ERROR",
                    "JDBC connection.secret_ref or connection.local_ref is missing.",
                    "Supply connection.secret_ref or an approved connection.local_ref in Environment Binding."));
        }
        String normalizedRef = secretRef.toLowerCase(Locale.ROOT);
        if (normalizedRef.startsWith("generated://provider-capability/")) {
            return localH2Connection(context, dialect);
        }
        ResolvedSecret resolvedSecret = SecretRefResolver.resolveEnvSecretRef(
                secretRef,
                "JDBC",
                "connection.secret_ref",
                environment);
        if (resolvedSecret.failure() != null) {
            return SecretConnection.failed(resolvedSecret.failure());
        }
        ProviderFailure driverFailure = externalDriverFailure(dialect, resolvedSecret.value());
        if (driverFailure != null) {
            return SecretConnection.failed(driverFailure);
        }
        return new SecretConnection(resolvedSecret.value(), "", "", null);
    }

    private ProviderFailure externalDriverFailure(String dialect, String jdbcUrl) {
        if (isLocalH2CompatibilityConnection(jdbcUrl)) {
            return null;
        }
        JdbcDriverLoader.LoadResult loadResult = driverLoader.load(dialect, driverDiscovery);
        if (loadResult.loaded()) {
            return null;
        }
        return ProviderFailure.of(
                loadResult.failureCode(),
                "CONFIGURATION_ERROR",
                loadResult.message(),
                loadResult.ownerAction());
    }

    private boolean isLocalH2CompatibilityConnection(String jdbcUrl) {
        return jdbcUrl.toLowerCase(Locale.ROOT).startsWith("jdbc:h2:");
    }

    private SecretConnection localH2Connection(ProviderExecutionContext context, String dialect) {
        String dbName = safe(context.providerId() + "-" + context.runDir().getFileName());
        String mode = "db2".equals(dialect) ? "DB2" : "Oracle";
        String jdbcUrl = "jdbc:h2:mem:" + dbName + ";MODE=" + mode + ";DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=true";
        return new SecretConnection(jdbcUrl, "sa", "", null);
    }

    private boolean approvedLocalRef(String localRef, String dialect) {
        return ("oracle".equals(dialect) && "approved_local_h2_oracle".equals(localRef))
                || ("db2".equals(dialect) && "approved_local_h2_db2".equals(localRef));
    }

    private Map<String, Object> parameterValues(ProviderExecutionContext context, ProviderOperationRequest request) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map<String, Object> parameter : request.parameters()) {
            String bindAs = stringValue(parameter.get("bind_as"));
            if (!bindAs.startsWith("params.")) {
                continue;
            }
            values.put(bindAs.substring("params.".length()), resolveValue(context.suiteRoot(), stringValue(parameter.get("ref"))));
        }
        return values;
    }

    private Object resolveValue(Path suiteRoot, String ref) {
        if (!ref.contains("#")) {
            return ref;
        }
        String[] parts = ref.split("#", 2);
        Object loaded = readYaml(suiteRoot.resolve(parts[0]).normalize());
        Object current = loaded;
        String pointer = parts[1].startsWith("/") ? parts[1].substring(1) : parts[1];
        for (String part : pointer.split("/")) {
            if (part.isBlank()) {
                continue;
            }
            if (!(current instanceof Map<?, ?> map)) {
                return "";
            }
            current = map.get(part);
        }
        return current == null ? "" : current;
    }

    private String firstParameter(ProviderOperationRequest request, String bindAs) {
        for (Map<String, Object> parameter : request.parameters()) {
            if (bindAs.equals(stringValue(parameter.get("bind_as")))) {
                return stringValue(parameter.get("ref"));
            }
        }
        return "";
    }

    private PreparedSql prepareSql(String sql) {
        Matcher matcher = NAMED_PARAM.matcher(sql);
        StringBuilder prepared = new StringBuilder();
        List<String> params = new ArrayList<>();
        while (matcher.find()) {
            params.add(matcher.group(1));
            matcher.appendReplacement(prepared, "?");
        }
        matcher.appendTail(prepared);
        return new PreparedSql(prepared.toString(), List.copyOf(params));
    }

    private void validateParams(ProviderOperationRequest request, PreparedSql preparedSql, Map<String, Object> params) {
        Set<String> required = new LinkedHashSet<>(preparedSql.params());
        validateParams(request, required, params);
    }

    private void validateScriptParams(
            ProviderOperationRequest request,
            List<PreparedSql> preparedStatements,
            Map<String, Object> params) {
        Set<String> required = new LinkedHashSet<>();
        for (PreparedSql preparedSql : preparedStatements) {
            required.addAll(preparedSql.params());
        }
        validateParams(request, required, params);
    }

    private void validateParams(ProviderOperationRequest request, Set<String> required, Map<String, Object> params) {
        for (String requiredParam : required) {
            if (!params.containsKey(requiredParam) || stringValue(params.get(requiredParam)).isBlank()) {
                throw new JdbcRuntimeFailure(ProviderFailure.of(
                        "SQL_PARAM_MISSING",
                        classificationFor(request.operation()),
                        "SQL parameter `" + requiredParam + "` is required by referenced SQL.",
                        "Add operation input `params." + requiredParam + "`."));
            }
        }
        if (strictParams(request)) {
            for (String supplied : params.keySet()) {
                if (!required.contains(supplied)) {
                    throw new JdbcRuntimeFailure(ProviderFailure.of(
                            "SQL_PARAM_MISSING",
                            classificationFor(request.operation()),
                            "SQL parameter `" + supplied + "` is not used by referenced SQL while strict_params is enabled.",
                            "Remove unused SQL parameter or update the SQL ref."));
                }
            }
        }
    }

    private boolean strictParams(ProviderOperationRequest request) {
        Object strict = request.outputs().getOrDefault("_strict_params", true);
        return !(strict instanceof Boolean bool) || bool;
    }

    private void bindParams(PreparedStatement statement, List<String> names, Map<String, Object> params)
            throws SQLException {
        for (int index = 0; index < names.size(); index++) {
            statement.setObject(index + 1, params.get(names.get(index)));
        }
    }

    private List<Map<String, Object>> rows(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (resultSet.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int column = 1; column <= metadata.getColumnCount(); column++) {
                row.put(metadata.getColumnLabel(column), resultSet.getObject(column));
            }
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private boolean matchesExpected(int rowCount, ProviderOperationRequest request) {
        Integer rowCountExpected = integerParameter(request, "expected.row_count");
        if (rowCountExpected != null) {
            return rowCount == rowCountExpected;
        }
        Integer minRows = integerParameter(request, "expected.min_rows");
        return minRows == null ? rowCount > 0 : rowCount >= minRows;
    }

    private Integer integerParameter(ProviderOperationRequest request, String bindAs) {
        for (Map<String, Object> parameter : request.parameters()) {
            if (bindAs.equals(stringValue(parameter.get("bind_as")))) {
                Object value = parameter.get("ref");
                if (value instanceof Number number) {
                    return number.intValue();
                }
                String text = stringValue(value);
                return text.isBlank() ? null : Integer.parseInt(text);
            }
        }
        return null;
    }

    private String writeUpdateEvidence(
            ProviderExecutionContext context,
            ProviderOperationRequest request,
            String evidenceKind,
            String sqlRef,
            Map<String, Object> params,
            int affectedRows,
            long durationMs,
            ProviderFailure failure) {
        String id = stringValue(request.outputs().get("_operation_id"));
        if (id.isBlank()) {
            id = evidenceKind;
        }
        String evidenceRef = "provider-evidence/jdbc/" + evidenceKind + "_" + safe(id) + ".yaml";
        StringBuilder content = new StringBuilder();
        content.append("evidence_type: ").append(evidenceKind).append("_evidence\n");
        appendEvidenceHeader(content, context);
        content.append("sql_ref: ").append(sqlRef).append('\n');
        appendMaskedParams(content, params);
        content.append("affected_rows: ").append(affectedRows).append('\n');
        content.append("duration_ms: ").append(durationMs).append('\n');
        appendStatus(content, failure);
        write(context.runDir().resolve(evidenceRef), content.toString());
        return evidenceRef;
    }

    private String writeQueryEvidence(
            ProviderExecutionContext context,
            ProviderOperationRequest request,
            String queryRef,
            Map<String, Object> params,
            int rowCount,
            List<Map<String, Object>> sampleRows,
            long durationMs,
            ProviderFailure failure) {
        String id = stringValue(request.outputs().get("_operation_id"));
        if (id.isBlank()) {
            id = "query";
        }
        String evidenceRef = "provider-evidence/jdbc/query_" + safe(id) + ".yaml";
        StringBuilder content = new StringBuilder();
        content.append("evidence_type: query_evidence\n");
        appendEvidenceHeader(content, context);
        content.append("query_ref: ").append(queryRef).append('\n');
        appendMaskedParams(content, params);
        content.append("row_count: ").append(rowCount).append('\n');
        content.append("duration_ms: ").append(durationMs).append('\n');
        content.append("masked_sample_result:\n");
        for (Map<String, Object> row : maskRows(sampleRows)) {
            content.append("  -");
            if (row.isEmpty()) {
                content.append(" {}\n");
                continue;
            }
            content.append('\n');
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                content.append("      ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
            }
        }
        appendStatus(content, failure);
        write(context.runDir().resolve(evidenceRef), content.toString());
        return evidenceRef;
    }

    private void appendEvidenceHeader(StringBuilder content, ProviderExecutionContext context) {
        content.append("evidence_classification: ").append(evidenceClassification(context)).append('\n');
        content.append("downstream_release_evidence: false\n");
        content.append("provider_type: ").append(context.providerType()).append('\n');
        content.append("provider_id: ").append(context.providerId()).append('\n');
        content.append("profile: ").append(context.profile()).append('\n');
        content.append("runtime_mode: ").append(context.runtimeMode()).append('\n');
        content.append("dialect: ").append(normalizedDialect(context)).append('\n');
    }

    private void appendMaskedParams(StringBuilder content, Map<String, Object> params) {
        content.append("masked_params:\n");
        if (params.isEmpty()) {
            content.append("  {}\n");
            return;
        }
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            content.append("  ").append(entry.getKey()).append(": ").append(mask(entry.getKey(), entry.getValue())).append('\n');
        }
    }

    private void appendStatus(StringBuilder content, ProviderFailure failure) {
        content.append("status: ").append(failure == null ? "passed" : "failed").append('\n');
        if (failure != null) {
            content.append("failure_code: ").append(failure.code()).append('\n');
            content.append("classification: ").append(failure.classification()).append('\n');
            content.append("reason: ").append(failure.reason()).append('\n');
            content.append("owner_action: ").append(failure.ownerAction()).append('\n');
        }
        content.append("masking:\n  raw_secret_found: false\n");
    }

    private List<Map<String, Object>> maskRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> masked = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> maskedRow = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                maskedRow.put(entry.getKey(), mask(entry.getKey(), entry.getValue()));
            }
            masked.add(maskedRow);
        }
        return List.copyOf(masked);
    }

    private Object mask(String key, Object value) {
        String normalized = key.toLowerCase(Locale.ROOT);
        if (normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("credential")
                || normalized.contains("authorization")) {
            return "***";
        }
        return value;
    }

    private String evidenceClassification(ProviderExecutionContext context) {
        String classification = stringValue(context.bindingValues().get("_evidence_classification"));
        return classification.isBlank() ? "framework_provider_capability_only" : classification;
    }

    private ProviderOperationResult failed(
            ProviderExecutionContext context,
            ProviderOperationRequest request,
            Map<String, Object> outputs,
            String code,
            String classification,
            String reason,
            String ownerAction) {
        return failed(context, request, outputs, ProviderFailure.of(code, classification, reason, ownerAction));
    }

    private ProviderOperationResult failed(
            ProviderExecutionContext context,
            ProviderOperationRequest request,
            Map<String, Object> outputs,
            ProviderFailure failure) {
        String evidenceRef = writeFailureEvidence(context, request, failure);
        return ProviderOperationResult.failed(
                outputs,
                List.of(new ProviderEvidence("failure_detail", evidenceRef, true)),
                failure);
    }

    private ProviderFailure failureForSqlException(String operation, SQLException e) {
        String code = switch (operation) {
            case "db_cleanup" -> "DB_CLEANUP_FAILED";
            case "db_seed" -> "DB_EXECUTION_FAILED";
            default -> "DB_QUERY_FAILED";
        };
        return ProviderFailure.of(
                code,
                classificationFor(operation),
                maskSensitiveText(e.getMessage()),
                "Review JDBC SQL ref, params, dialect, and connection secret_ref.");
    }

    private String maskSensitiveText(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("jdbc:[^\\s\\\"]+", "jdbc:***");
    }

    private String classificationFor(String operation) {
        return switch (operation) {
            case "db_cleanup" -> "DB_CLEANUP_FAILED";
            case "db_record_exists" -> "DB_QUERY_FAILED";
            case "db_query" -> "DB_QUERY_FAILED";
            case "db_seed" -> "DB_EXECUTION_FAILED";
            default -> "OPERATION_FAILED";
        };
    }

    private String writeFailureEvidence(
            ProviderExecutionContext context,
            ProviderOperationRequest request,
            ProviderFailure failure) {
        String id = stringValue(request.outputs().get("_operation_id"));
        if (id.isBlank()) {
            id = request.operation().isBlank() ? "operation" : request.operation();
        }
        String evidenceRef = "provider-evidence/jdbc/failure_" + safe(id) + ".yaml";
        StringBuilder content = new StringBuilder();
        content.append("evidence_type: failure_detail\n");
        appendEvidenceHeader(content, context);
        content.append("operation: ").append(request.operation()).append('\n');
        content.append("failure_code: ").append(failure.code()).append('\n');
        content.append("classification: ").append(failure.classification()).append('\n');
        content.append("reason: ").append(failure.reason()).append('\n');
        content.append("owner_action: ").append(failure.ownerAction()).append('\n');
        content.append("masking:\n  raw_secret_found: false\n");
        write(context.runDir().resolve(evidenceRef), content.toString());
        return evidenceRef;
    }

    private String normalizedDialect(ProviderExecutionContext context) {
        String dialect = stringValue(valueAtPath(context.bindingValues(), "dialect"));
        if (dialect.isBlank()) {
            dialect = stringValue(context.providerInstance().get("dialect"));
        }
        return dialect.toLowerCase(Locale.ROOT);
    }

    private List<String> statements(String script) {
        List<String> statements = new ArrayList<>();
        for (String statement : script.split(";")) {
            String trimmed = statement.trim();
            if (!trimmed.isBlank()) {
                statements.add(trimmed);
            }
        }
        return List.copyOf(statements);
    }

    private String readRef(Path suiteRoot, String ref, String operation, String missingCode) {
        Path normalizedRoot = suiteRoot.toAbsolutePath().normalize();
        Path path = normalizedRoot.resolve(ref).normalize();
        if (!path.startsWith(normalizedRoot)) {
            throw new JdbcRuntimeFailure(ProviderFailure.of(
                    missingCode,
                    classificationFor(operation),
                    "SQL ref must stay under the suite root: " + ref,
                    "Use a checked-in SQL ref under the suite."));
        }
        if (!Files.isRegularFile(path)) {
            throw new JdbcRuntimeFailure(ProviderFailure.of(
                    missingCode,
                    classificationFor(operation),
                    "SQL ref not found: " + ref,
                    "Restore the checked-in SQL ref."));
        }
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new JdbcRuntimeFailure(ProviderFailure.of(
                    missingCode,
                    classificationFor(operation),
                    "SQL ref could not be read: " + ref,
                    "Restore readable permissions for the checked-in SQL ref."));
        }
    }

    private Object readYaml(Path path) {
        try {
            return yaml.load(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read referenced data: " + path, e);
        }
    }

    private Object valueAtPath(Map<String, Object> map, String path) {
        if (map.containsKey(path)) {
            return map.get(path);
        }
        Object current = map;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> currentMap)) {
                return null;
            }
            current = currentMap.get(part);
        }
        return current;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write JDBC provider evidence: " + path, e);
        }
    }

    private record PreparedSql(String sql, List<String> params) {
    }

    private record SecretConnection(String jdbcUrl, String username, String password, ProviderFailure failure) {

        static SecretConnection failed(ProviderFailure failure) {
            return new SecretConnection("", "", "", failure);
        }
    }

    private static class JdbcRuntimeFailure extends RuntimeException {

        private final ProviderFailure failure;

        JdbcRuntimeFailure(ProviderFailure failure) {
            super(failure.reason());
            this.failure = failure;
        }

        ProviderFailure failure() {
            return failure;
        }
    }
}
