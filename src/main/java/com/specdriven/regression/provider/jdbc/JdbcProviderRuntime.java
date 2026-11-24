package com.specdriven.regression.provider.jdbc;

import com.specdriven.regression.contract.SecurityFieldNames;
import com.specdriven.regression.contract.v03.V03OutputRedactor;
import com.specdriven.regression.provider.ProviderDataYaml;
import com.specdriven.regression.provider.SuiteAssetReader;
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
import org.yaml.snakeyaml.error.YAMLException;

public class JdbcProviderRuntime implements ProviderRuntime {

    // H2 keeps each run-scoped in-memory database across provider phases in this JVM.
    private static final String LOCAL_PASSWORD = java.util.UUID.randomUUID().toString();
    private static final Set<String> SUPPORTED_DIALECTS = Set.of("oracle", "db2");
    private static final Pattern MEMORY_URL = Pattern.compile(
            "jdbc:h2:mem:[a-zA-Z0-9._-]+(?:;(?:MODE=(?:Oracle|DB2)|DB_CLOSE_DELAY=-1|DATABASE_TO_UPPER=true|USER=sa))*",
            Pattern.CASE_INSENSITIVE);
    private static final String SQL_APPROVALS = "REGRESS_JDBC_APPROVED_SQL_SHA256";
    private static final Pattern GENERATED_REF = Pattern.compile("generated://provider-capability/", Pattern.CASE_INSENSITIVE);
    private static final Pattern APPROVAL_ENTRY = Pattern.compile(
            "(oracle|db2):[A-Z_][A-Z0-9_]*:(db_seed|db_cleanup|db_query|db_record_exists):[0-9a-f]{64}");
    private static final Pattern NAMED_PARAM = Pattern.compile(":([A-Za-z][A-Za-z0-9_]*)");

    private final Yaml yaml = ProviderDataYaml.create();
    private final V03OutputRedactor redactor = new V03OutputRedactor();
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
        try {
            SecretConnection connection = resolveConnection(context, dialect);
            if (connection.failure() != null) {
                return failed(context, request, Map.of(), connection.failure());
            }
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
        } catch (JdbcRuntimeFailure e) {
            return failed(context, request, Map.of(), e.failure());
        } catch (RuntimeException ignored) {
            return failed(
                    context,
                    request,
                    Map.of(),
                    "OPERATION_FAILED",
                    classificationFor(request.operation()),
                    "JDBC provider could not complete the operation.",
                    "Review the JDBC driver, checked-in SQL and parameter refs, and expected row counts.");
        }
    }

    /** Separate protected branch; generic Oracle/DB2 dialect and env resolution are unchanged. */
    public ProviderOperationResult executeReviewedQuery(ProviderExecutionContext context,ProviderOperationRequest request,ReviewedJdbcInvocation invocation) {
        return ReviewedJdbcExecution.protectedChild().execute(context,request,invocation);
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
        try {
            List<PreparedSql> preparedStatements = statements(readApprovedSql(secretConnection, context.suiteRoot(), sqlRef, request.operation(), "SQL_REF_MISSING")).stream()
                    .map(this::prepareSql)
                    .toList();
            validateScriptParams(request, preparedStatements, params);
            try (Connection connection = open(secretConnection)) {
                for (PreparedSql preparedSql : preparedStatements) {
                    try (PreparedStatement preparedStatement = connection.prepareStatement(preparedSql.sql())) {
                        bindParams(preparedStatement, preparedSql.params(), params);
                        affectedRows += Math.max(preparedStatement.executeUpdate(), 0);
                    }
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
        } catch (SQLException ignored) {
            ProviderFailure failure = failureForSqlException(request.operation());
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
        try {
            PreparedSql preparedSql = prepareSql(readApprovedSql(secretConnection, context.suiteRoot(), queryRef, request.operation(), "QUERY_REF_MISSING"));
            validateParams(request, preparedSql, params);
            try (Connection connection = open(secretConnection);
                    PreparedStatement statement = connection.prepareStatement(preparedSql.sql())) {
                bindParams(statement, preparedSql.params(), params);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<Map<String, Object>> sample = new ArrayList<>(5);
                    ResultSetMetaData metadata = resultSet.getMetaData();
                    while (resultSet.next()) {
                        rowCount++;
                        if (sample.size() < 5) sample.add(readRow(resultSet, metadata));
                    }
                    sampleRows = List.copyOf(sample);
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
            outputs.put("sample_rows", sampleRows.stream().map(row -> maskFields(row, false)).toList());
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
        } catch (SQLException ignored) {
            ProviderFailure failure = failureForSqlException(request.operation());
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

    private Connection open(SecretConnection secretConnection) {
        if (secretConnection.external()) {
            var loaded = driverLoader.load(secretConnection.dialect(), driverDiscovery);
            if (!loaded.loaded()) throw new JdbcRuntimeFailure(ProviderFailure.of(loaded.failureCode(),
                    "CONFIGURATION_ERROR", loaded.message(), loaded.ownerAction()));
        }
        try {
            if (secretConnection.username().isBlank() && secretConnection.password().isBlank()) {
                return DriverManager.getConnection(secretConnection.jdbcUrl());
            }
            return DriverManager.getConnection(secretConnection.jdbcUrl(), secretConnection.username(), secretConnection.password());
        } catch (SQLException ignored) {
            throw new JdbcRuntimeFailure(ProviderFailure.of(
                    "DB_CONNECTION_FAILED",
                    "CONFIGURATION_ERROR",
                    "JDBC driver could not open the configured connection.",
                    "Verify the JDBC connection secret_ref, vendor driver, endpoint, and network access."));
        }
    }

    private SecretConnection resolveConnection(ProviderExecutionContext context, String dialect) {
        String localRef = stringValue(valueAtPath(context.bindingValues(), "connection.local_ref"));
        if (!localRef.isBlank()) {
            if (!approvedLocalRef(localRef, dialect)) {
                return SecretConnection.failed(ProviderFailure.of(
                        "SECRET_RESOLUTION_ERROR",
                        "SECRET_RESOLUTION_ERROR",
                        "JDBC connection.local_ref is not approved for local provider capability runtime.",
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
        if (GENERATED_REF.matcher(secretRef).lookingAt()) {
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
        String jdbcUrl = resolvedSecret.value();
        if (MEMORY_URL.matcher(jdbcUrl).matches()) return new SecretConnection(jdbcUrl, "", "", "", "", null);
        String prefix = dialect.equals("db2") ? "jdbc:db2:" : "jdbc:oracle:";
        if (!jdbcUrl.startsWith(prefix)) {
            return SecretConnection.failed(ProviderFailure.of("DB_CONNECTION_FAILED", "CONFIGURATION_ERROR",
                    "JDBC driver could not open the configured connection.",
                    "Use the JDBC URL of the selected dialect or a supported in-memory H2 profile."));
        }
        return new SecretConnection(jdbcUrl, "", "", dialect, secretRef.substring("env://".length()), null);
    }

    private SecretConnection localH2Connection(ProviderExecutionContext context, String dialect) {
        String identity = context.runDir().toAbsolutePath().normalize() + "\n" + dialect + "\n" + context.providerId();
        String dbName = sha256(identity);
        String mode = "db2".equals(dialect) ? "DB2" : "Oracle";
        String jdbcUrl = "jdbc:h2:mem:" + dbName + ";MODE=" + mode + ";DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=true";
        return new SecretConnection(jdbcUrl, "framework_local", LOCAL_PASSWORD, "", "", null);
    }

    private static String sha256(String value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable.", unavailable);
        }
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
            try {
                Object value = resolveValue(context.suiteRoot(), stringValue(parameter.get("ref")));
                values.put(bindAs.substring("params.".length()), value);
            } catch (IOException | IllegalArgumentException | YAMLException ignored) {
                throw new JdbcRuntimeFailure(ProviderFailure.of("OPERATION_FAILED", classificationFor(request.operation()),
                        "JDBC parameter data ref could not be read as a suite data document.",
                        "Use a readable YAML or JSON parameter ref under the suite root."));
            }
        }
        return values;
    }

    private Object resolveValue(Path suiteRoot, String ref) throws IOException {
        if (!ref.contains("#")) {
            return ref;
        }
        String[] parts = ref.split("#", 2);
        String document = SuiteAssetReader.read(suiteRoot, parts[0], "JDBC parameter ref must stay under the suite root.");
        Object current = yaml.load(document);
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

    private Map<String, Object> readRow(ResultSet resultSet, ResultSetMetaData metadata) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int column = 1; column <= metadata.getColumnCount(); column++) {
            row.put(metadata.getColumnLabel(column), resultSet.getObject(column));
        }
        return row;
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
        Map<String, Object> evidence = evidenceHeader(context, evidenceKind + "_evidence");
        evidence.put("sql_ref", redactor.redactMessage(sqlRef));
        evidence.put("masked_params", maskFields(params, true));
        evidence.put("affected_rows", affectedRows);
        evidence.put("duration_ms", durationMs);
        evidence.put("status", failure == null ? "passed" : "failed");
        writeEvidence(context.runDir().resolve(evidenceRef), evidence, failure);
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
        Map<String, Object> evidence = evidenceHeader(context, "query_evidence");
        evidence.put("query_ref", redactor.redactMessage(queryRef));
        evidence.put("masked_params", maskFields(params, true));
        evidence.put("row_count", rowCount);
        evidence.put("duration_ms", durationMs);
        evidence.put("masked_sample_result", sampleRows.stream().map(row -> maskFields(row, true)).toList());
        evidence.put("status", failure == null ? "passed" : "failed");
        writeEvidence(context.runDir().resolve(evidenceRef), evidence, failure);
        return evidenceRef;
    }

    private Map<String, Object> evidenceHeader(ProviderExecutionContext context, String type) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("evidence_type", type);
        evidence.put("evidence_classification", redactor.redactMessage(evidenceClassification(context)));
        evidence.put("downstream_release_evidence", false);
        evidence.put("provider_type", redactor.redactMessage(context.providerType()));
        evidence.put("provider_id", redactor.redactMessage(context.providerId()));
        evidence.put("profile", redactor.redactMessage(context.profile()));
        evidence.put("runtime_mode", redactor.redactMessage(context.runtimeMode()));
        evidence.put("dialect", redactor.redactMessage(normalizedDialect(context)));
        return evidence;
    }

    private Map<String, Object> maskFields(Map<?, ?> fields, boolean forEvidence) {
        Map<String, Object> masked = new LinkedHashMap<>();
        fields.forEach((key, value) -> {
            String label = String.valueOf(key);
            boolean sensitive = SecurityFieldNames.isCredential(label) || "connection".equals(SecurityFieldNames.canonical(label));
            masked.put(label, sensitive ? "***" : maskValue(value, forEvidence));
        });
        return masked;
    }

    private Object maskValue(Object value, boolean forEvidence) {
        if (value instanceof Map<?, ?> fields) return maskFields(fields, forEvidence);
        if (value instanceof List<?> list) return list.stream().map(item -> maskValue(item, forEvidence)).toList();
        if (value instanceof String text) return redactor.redactMessage(text);
        // Do not let the YAML serializer inspect vendor-specific JDBC objects.
        if (forEvidence && value != null && !(value instanceof Number) && !(value instanceof Boolean)) {
            return redactor.redactMessage(String.valueOf(value));
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

    private ProviderFailure failureForSqlException(String operation) {
        String code = classificationFor(operation);
        return ProviderFailure.of(
                code,
                code,
                "JDBC driver could not complete the SQL operation.",
                "Review JDBC SQL ref, params, dialect, and connection secret_ref.");
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
        Map<String, Object> evidence = evidenceHeader(context, "failure_detail");
        evidence.put("operation", redactor.redactMessage(request.operation()));
        writeEvidence(context.runDir().resolve(evidenceRef), evidence, failure);
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

    private String readApprovedSql(SecretConnection connection, Path suiteRoot, String ref, String operation, String missingCode) {
        try {
            String sql = SqlSource.read(suiteRoot, ref, "SQL ref must stay under the suite root.");
            if (connection.external()) {
                String entry = connection.dialect() + ":" + connection.envName() + ":" + operation + ":" + sha256(sql);
                String approvals = Objects.toString(environment.apply(SQL_APPROVALS), "");
                boolean approved = false;
                for (String configured : approvals.split(",", -1)) {
                    String candidate = configured.strip();
                    if (!APPROVAL_ENTRY.matcher(candidate).matches()) {
                        approved = false;
                        break;
                    }
                    if (entry.equals(candidate)) approved = true;
                }
                if (!approved) throw new JdbcRuntimeFailure(ProviderFailure.of("JDBC_SQL_NOT_APPROVED",
                        "CONFIGURATION_ERROR", "The runner has not approved this SQL asset for the selected external database operation.",
                        "Configure " + SQL_APPROVALS + " from the independently reviewed SQL bytes."));
            }
            return sql;
        } catch (IllegalArgumentException ignored) {
            throw new JdbcRuntimeFailure(ProviderFailure.of(missingCode, classificationFor(operation),
                    "Invalid SQL ref for this suite.", "Use a checked-in SQL ref under the suite."));
        } catch (java.nio.file.NoSuchFileException error) {
            throw new JdbcRuntimeFailure(ProviderFailure.of(missingCode, classificationFor(operation),
                    "SQL ref not found: " + redactor.redactMessage(ref), "Restore the checked-in SQL ref."));
        } catch (IOException e) {
            throw new JdbcRuntimeFailure(ProviderFailure.of(
                    missingCode,
                    classificationFor(operation),
                    "SQL ref could not be read: " + redactor.redactMessage(ref),
                    "Restore readable permissions for the checked-in SQL ref."));
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

    private void writeEvidence(Path path, Map<String, Object> evidence, ProviderFailure failure) {
        if (failure != null) {
            evidence.put("failure_code", failure.code());
            evidence.put("classification", failure.classification());
            evidence.put("reason", failure.reason());
            evidence.put("owner_action", failure.ownerAction());
        }
        evidence.put("masking", Map.of("raw_secret_found", false));
        String content = yaml.dumpAsMap(evidence);
        try {
            ProviderDataYaml.createParent(path);
            Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write JDBC provider evidence: " + path, e);
        }
    }

    private record PreparedSql(String sql, List<String> params) {
    }

    private record SecretConnection(String jdbcUrl, String username, String password, String dialect,
            String envName, ProviderFailure failure) {
        boolean external() { return !envName.isEmpty(); }


        @Override
        public String toString() {
            return "SecretConnection[redacted]";
        }

        static SecretConnection failed(ProviderFailure failure) {
            return new SecretConnection("", "", "", "", "", failure);
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
