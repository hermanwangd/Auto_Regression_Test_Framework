package com.specdriven.regression.contract.v03;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class V03OutputRedactorTest {
    @Test
    void masksSerializedJsonValuesInNestedDiagnosticEnvelopes() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        String inner = "{\"session\":\"sample-inside\",\"note\":\"公開\"}";
        String once = json.writeValueAsString(Map.of("echo", inner, "trace", "public-trace"));
        String twice = json.writeValueAsString(List.of(once));
        var outer = json.readTree(V03OutputRedactor.redactProviderDiagnostic(twice));
        var echo = json.readTree(outer.get(0).asText());
        assertThat(echo.path("trace").asText()).isEqualTo("public-trace");
        var payload = json.readTree(echo.path("echo").asText());
        assertThat(payload.path("session").asText()).isEqualTo("***MASKED***");
        assertThat(payload.path("note").asText()).isEqualTo("公開");
        assertThat(new V03OutputRedactor().redactValue(Map.of("diagnostic", twice)).toString())
                .doesNotContain("sample-inside").contains("public-trace");
    }

    @Test
    void boundsNestedJsonWorkAndPreservesUnrelatedDiagnostics() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var redactor = new V03OutputRedactor();
        String nested = "{\"session\":\"fixture-deep\"}";
        for (int i = 0; i < 12; i++) nested = json.writeValueAsString(List.of(nested));
        String output = redactor.redactMessage(json.writeValueAsString(Map.of("echo", nested, "trace", "ok")));
        assertThat(output).doesNotContain("fixture-deep");
        assertThat(json.readTree(output).path("trace").asText()).isEqualTo("ok");
        String message = "[WARN] quote \\\"public\\\" status=ok";
        assertThat(redactor.redactMessage(message)).isEqualTo(message);
    }

    @Test
    void decodesEscapedJsonMemberNamesOnlyForComparison() {
        var redactor = new V03OutputRedactor();
        String session = "\\uff33\\uff45\\uff53\\uff53\\uff49\\uff4f\\uff4e";
        String cookie = "Set\\u002dCoo\\u006bie";
        String template = "{\"%s\":\"%s\",\"%s\":%s,\"note\":\"keep \\\\u0061\"}";
        String raw = template.formatted(session, "fixture-A", cookie, "[\"fixture-B\",\"fixture-C\"]");
        assertThat(redactor.redactMessage(raw))
                .isEqualTo(template.formatted(session, "[MASKED]", cookie, "\"[MASKED]\""));
        assertThat(V03OutputRedactor.redactProviderDiagnostic(raw))
                .isEqualTo(template.formatted(session, "***MASKED***", cookie, "\"***MASKED***\""));
        String literalBackslash = "{\"\\\\u0073ession\":\"public\"}";
        assertThat(redactor.redactMessage(literalBackslash)).isEqualTo(literalBackslash);
        for (String scalar : List.of("42", "-1.2e+2", "true", "false", "null")) {
            assertThat(redactor.redactMessage("{\"Cookie\":" + scalar + ",\"note\":true}"))
                    .isEqualTo("{\"Cookie\":\"[MASKED]\",\"note\":true}");
        }
    }

    @Test
    void protectsCookieHeadersAndSessionEvidenceWhileKeepingPublicContext() {
        var redactor = new V03OutputRedactor();
        for (String label : List.of("session", "SessionId", "session_id", "Cookie", "Ｓｅｔ－Ｃｏｏｋｉｅ")) {
            var data = Map.of(label, List.of("sample-a", "sample-b"), "session_count", 2);
            var expected = Map.of(label, "[MASKED]", "session_count", 2);
            assertThat(redactor.redactValue(data)).isEqualTo(expected);
            assertThat(redactor.redactEvidenceValue(data, Set.of())).isEqualTo(expected);
        }
        String header = "Ｃｏｏｋｉｅ: first=sample-a; second=sample-b\r\nTrace: ready";
        assertThat(redactor.redactMessage(header)).isEqualTo("Ｃｏｏｋｉｅ: [MASKED]\r\nTrace: ready");
        assertThat(com.specdriven.regression.provider.ProviderDiagnosticRedactor.maskSensitiveText(header))
                .isEqualTo("Ｃｏｏｋｉｅ: ***MASKED***\r\nTrace: ready");
        assertThat(redactor.redactMessage("{\"set-cookie\":[\"sample-a\",\"sample-b\"],\"ok\":true}"))
                .isEqualTo("{\"set-cookie\":\"[MASKED]\",\"ok\":true}");
        assertThat(redactor.redactMessage("session_id=sample-a status=ready"))
                .isEqualTo("session_id=[MASKED] status=ready");
    }

    @Test
    void masksBasicAuthorizationInDiagnosticsAndNestedTextEvidence() {
        var redactor = new V03OutputRedactor();
        String field = "Proxy-Ａｕｔｈｏｒｉｚａｔｉｏｎ: Ｂａｓｉｃ";
        assertThat(redactor.redactMessage("Authorization: Basic fixtureValue= status=OK"))
                .isEqualTo("Authorization: [MASKED] status=OK");
        String folded = field + "\r\n\tfirstPart\r\n secondPart=\r\nNext: OK";
        assertThat(redactor.redactEvidenceValue(Map.of("message", folded), Set.of()))
                .isEqualTo(Map.of("message", "Proxy-Ａｕｔｈｏｒｉｚａｔｉｏｎ: [MASKED]\r\nNext: OK"));
        assertThat(redactor.redactMessage("basic checks completed")).isEqualTo("basic checks completed");
    }

    @Test
    void keepsPublicDiagnosticsAfterAuthorizationValues() {
        var redactor = new V03OutputRedactor();
        String context = "\n  status=OK\n\tat example.Work.run(Work.java:12)";
        for (String scheme : java.util.List.of("Basic", "Bearer", "Negotiate")) {
            String message = "Authorization: " + scheme + " exampleValue" + context;
            assertThat(redactor.redactMessage(message)).isEqualTo("Authorization: [MASKED]" + context);
            String providerMessage = com.specdriven.regression.provider.ProviderDiagnosticRedactor.maskSensitiveText(message);
            assertThat(providerMessage).isEqualTo("Authorization: ***MASKED***" + context);
            assertThat(redactor.redactMessage(providerMessage)).isEqualTo("Authorization: [MASKED]" + context);
        }
    }

    @Test
    void masksPrivateKeyBlocksInProviderAndFinalDiagnostics() {
        var redactor = new V03OutputRedactor();
        for (String kind : java.util.List.of("PRIVATE KEY", "RSA PRIVATE KEY", "ENCRYPTED PRIVATE KEY")) {
            String header = "-----BEGIN " + kind + "-----";
            String footer = "-----END " + kind + "-----";
            String message = "before\n" + header + "\nordinary test data\n" + footer + "\nafter";
            assertThat(redactor.redactMessage(message)).isEqualTo("before\n[MASKED]\nafter");
            assertThat(com.specdriven.regression.provider.ProviderDiagnosticRedactor.maskSensitiveText(message))
                    .isEqualTo("before\n***MASKED***\nafter");
            assertThat(redactor.redactMessage(header + "\nordinary test data")).isEqualTo("[MASKED]");
        }
    }

    @Test
    void masksAWholeAuthorizationFieldWhenItsSchemeHasParameters() {
        var redactor = new V03OutputRedactor();
        String message = "Authorization: Example a=first, b=second\r\nNext: OK";
        assertThat(redactor.redactMessage(message)).isEqualTo("Authorization: [MASKED]\r\nNext: OK");
    }

    @Test
    void retainsOriginalFieldNamesAndMasksEquivalentCredentialAndContractLabels() {
        var redactor = new V03OutputRedactor();
        Map<String, Object> row = Map.of("ＡＰＩ-Key", "fixture value", "顯示名稱", "正常",
                "Ｏｗｎｅｒ", "fixture owner");
        assertThat(redactor.redactEvidenceValue(row, java.util.Set.of("Owner")))
                .isEqualTo(Map.of("ＡＰＩ-Key", V03OutputRedactor.MASKED, "顯示名稱", "正常",
                        "Ｏｗｎｅｒ", V03OutputRedactor.MASKED));
        assertThat(redactor.redactMessage("ＴＯＫＥＮ=fixture")).isEqualTo("ＴＯＫＥＮ=" + V03OutputRedactor.MASKED);
        assertThat(redactor.redactMessage("品項 Ａ")).isEqualTo("品項 Ａ");
        assertThat(redactor.redactValue(Map.of("privateKey", "fixture value")))
                .isEqualTo(Map.of("privateKey", V03OutputRedactor.MASKED));
    }

    @Test
    void preservesUnicodeOutsideRawCredentialValues() {
        var redactor = new V03OutputRedactor();
        String log = "顯示 Ａ ① e\u0301 ＡＰＩ－Ｋｅｙ＝first，middle\u00a0last; 完成 Ｚ";
        assertThat(redactor.redactMessage(log)).isEqualTo("顯示 Ａ ① e\u0301 ＡＰＩ－Ｋｅｙ＝[MASKED]; 完成 Ｚ");
        assertThat(redactor.redactMessage("Ｂｅａｒｅｒ sample；suffix Ｚ"))
                .isEqualTo("Ｂｅａｒｅｒ [MASKED] Ｚ");
        assertThat(redactor.redactMessage("ＨＴＴＰＳ：／／test:one＠two@example.invalid/路徑 Ａ"))
                .isEqualTo("ＨＴＴＰＳ：／／[MASKED]@example.invalid/路徑 Ａ");
        assertThat(redactor.redactMessage("備註 Ａ ＪＤＢＣ：sample，part ＮＡＴＳ：／／sample\u00a0part 完成"))
                .isEqualTo("備註 Ａ [MASKED] [MASKED] 完成");
    }

    @Test
    void usesCanonicalContractKeysForPlainLogsAndMessagesInsideStructuredEvidence() {
        var redactor = new V03OutputRedactor();
        Set<String> fields = Set.of("request.client_trace", "review owner");
        String log = "說明 Ａ ＲＥＱＵＥＳＴ．ＣＬＩＥＮＴ－ＴＲＡＣＥ＝'one, two' \"Ｒｅｖｉｅｗ Ｏｗｎｅｒ\"＝three";
        String expected = "說明 Ａ ＲＥＱＵＥＳＴ．ＣＬＩＥＮＴ－ＴＲＡＣＥ＝'[MASKED]' \"Ｒｅｖｉｅｗ Ｏｗｎｅｒ\"＝[MASKED]";
        assertThat(redactor.redactMessage(log, fields)).isEqualTo(expected);
        assertThat(redactor.redactEvidenceValue(Map.of("描述", log, "request", Map.of("ＣＬＩＥＮＴ－ＴＲＡＣＥ", "sample")), fields))
                .isEqualTo(Map.of("描述", expected, "request", Map.of("ＣＬＩＥＮＴ－ＴＲＡＣＥ", "[MASKED]")));
    }

    @Test
    void preservesUnquotedContractLabelsWithSpacesAndPunctuation() {
        var redactor = new V03OutputRedactor();
        String log = "公開 Ａ Ｒｅｖｉｅｗ　Ｏｗｎｅｒ=sample；suffix, route/ＩＤ＋＝'one two' review:ID=third";
        assertThat(redactor.redactMessage(log, Set.of("review owner", "route/id+", "review:id")))
                .isEqualTo("公開 Ａ Ｒｅｖｉｅｗ　Ｏｗｎｅｒ=[MASKED], route/ＩＤ＋＝'[MASKED]' review:ID=[MASKED]");
        assertThat(redactor.redactMessage("tokenized=資料Ａ")).isEqualTo("tokenized=資料Ａ");
    }

    @Test
    void respectsQuotedAndEmptyValueBoundaries() {
        var redactor = new V03OutputRedactor();
        assertThat(redactor.redactMessage("token=\"one, \\\"two\\\"; three\" status=ＯＫ"))
                .isEqualTo("token=\"[MASKED]\" status=ＯＫ");
        assertThat(redactor.redactMessage("token='' status=ＯＫ\npassword=\n狀態=Ａ"))
                .isEqualTo("token='' status=ＯＫ\npassword=\n[MASKED]");
        assertThat(redactor.redactMessage("authorization=Bearer 'one two' code=Ａ"))
                .isEqualTo("authorization=[MASKED] code=Ａ");
    }

    @Test
    void masksCredentialsAcrossLineBreaksWithoutSplittingUnicodeValueContent() {
        var redactor = new V03OutputRedactor();
        assertThat(redactor.redactMessage("\"ＴＯＫＥＮ\"\r\n＝\nfirst\u00a0last，tail\r\n完成 Ａ"))
                .isEqualTo("\"ＴＯＫＥＮ\"\r\n＝\n[MASKED]\r\n完成 Ａ");
        assertThat(redactor.redactMessage("authorization:\nBearer\r\n'first second'\n完成 Ａ"))
                .isEqualTo("authorization:\n[MASKED]\n完成 Ａ");
        assertThat(redactor.redactMessage("Ｂｅａｒｅｒ\r\nfirst\u00a0last；tail\n完成 Ａ"))
                .isEqualTo("Ｂｅａｒｅｒ\r\n[MASKED]\n完成 Ａ");
        assertThat(redactor.redactMessage("Review Owner\n:\r\nfixture\n完成 Ａ", Set.of("review owner")))
                .isEqualTo("Review Owner\n:\r\n[MASKED]\n完成 Ａ");
    }

    @Test
    void masksContractSensitiveOutputsAndNestedSecretLikeValues() {
        V03ProviderContract.V03OperationDefinition operation = new V03ProviderContract.V03OperationDefinition(
                Set.of(), Set.of(), Set.of("session", "response"), Map.of(), Map.of(
                        "session", new V03OutputDefinition(V03ValueType.STRING, V03Sensitivity.SECRET, false, false),
                        "response", new V03OutputDefinition(V03ValueType.OBJECT, V03Sensitivity.PUBLIC, false, true)), Set.of());
        V03ProviderContract contract = new V03ProviderContract(
                "test.v0.3", "test", Set.of("native"), Map.of(), Map.of("run", operation), Set.of(), Set.of(), Set.of());
        V03ExecutionStep step = new V03ExecutionStep("TC-1", V03ExecutionStepKind.PROVIDER_OPERATION,
                "execute", "run", "target", "test.v0.3", "test", "local", "native", "run", Map.of(), "");
        V03ExecutionPlan plan = new V03ExecutionPlan("S", "local", Path.of("."),
                new V03SuiteMetadata("v0.3", "S", "local"),
                new V03EnvironmentProfile("local", "local", "per_run", "framework_verification_only", false),
                Map.of(), Map.of("test.v0.3", contract), Map.of(), java.util.List.of(), java.util.List.of(step),
                java.util.List.of(), "digest");

        Map<String, Object> redacted = new V03OutputRedactor().redact(plan, step, Map.of(
                "session", "Bearer actual-token",
                "response", Map.of("authorization", "Bearer actual-token", "status", "OK"),
                "undeclared_output", "must-not-persist"));

        assertThat(redacted).containsEntry("session", V03OutputRedactor.MASKED);
        assertThat(redacted).doesNotContainKey("undeclared_output");
        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) redacted.get("response");
        assertThat(response)
                .containsEntry("authorization", V03OutputRedactor.MASKED)
                .containsEntry("status", "OK");
        assertThat(new V03OutputRedactor().redactMessage("jdbc:oracle:thin:@secret-host password=actual"))
                .doesNotContain("secret-host")
                .doesNotContain("actual");
    }

    @Test
    void masksNonPublicStepOutputsInAssertionEvidence() {
        V03ProviderContract.V03OperationDefinition operation = new V03ProviderContract.V03OperationDefinition(
                Set.of(), Set.of(), Set.of("workspace_ref"), Map.of(), Map.of(
                        "workspace_ref", new V03OutputDefinition(V03ValueType.STRING, V03Sensitivity.MASKED, true, false)), Set.of());
        V03ProviderContract contract = new V03ProviderContract(
                "test.v0.3", "test", Set.of("native"), Map.of(), Map.of("setup", operation), Set.of(), Set.of(), Set.of());
        V03ExecutionStep producer = new V03ExecutionStep("TC-1", V03ExecutionStepKind.PROVIDER_OPERATION,
                "setup", "prepare", "target", "test.v0.3", "test", "local", "native", "setup", Map.of(), "");
        V03ExecutionStep assertion = new V03ExecutionStep("TC-1", V03ExecutionStepKind.ASSERTION,
                "verify", "assert", "", "", "", "local", "", "equals",
                Map.of("actual_ref", "step://prepare/workspace_ref"), "");
        V03ExecutionPlan plan = new V03ExecutionPlan("S", "local", Path.of("."),
                new V03SuiteMetadata("v0.3", "S", "local"),
                new V03EnvironmentProfile("local", "local", "per_run", "framework_verification_only", false),
                Map.of(), Map.of("test.v0.3", contract), Map.of(), java.util.List.of(),
                java.util.List.of(producer, assertion), java.util.List.of(), "digest");

        assertThat(new V03OutputRedactor().redactAssertionValue(
                plan, assertion, "step://prepare/workspace_ref", "/private/workspace",
                Map.of(new V03StepOutputKey("TC-1", "prepare", "workspace_ref"), output("prepare", "target", "workspace_ref",
                        V03ValueType.STRING, V03Sensitivity.MASKED, true, "/private/workspace"))))
                .isEqualTo(V03OutputRedactor.MASKED);
    }

    @Test
    void masksExactDottedSecretOutputInAssertionEvidence() {
        V03ProviderContract.V03OperationDefinition operation = new V03ProviderContract.V03OperationDefinition(
                Set.of(), Set.of(), Set.of("auth.token"), Map.of(), Map.of(
                        "auth.token", new V03OutputDefinition(V03ValueType.STRING, V03Sensitivity.SECRET, false, false)), Set.of());
        V03ProviderContract contract = new V03ProviderContract(
                "test.v0.3", "test", Set.of("native"), Map.of(), Map.of("login", operation), Set.of(), Set.of(), Set.of());
        V03ExecutionStep producer = new V03ExecutionStep("TC-1", V03ExecutionStepKind.PROVIDER_OPERATION,
                "execute", "login", "target", "test.v0.3", "test", "local", "native", "login", Map.of(), "");
        V03ExecutionStep assertion = new V03ExecutionStep("TC-1", V03ExecutionStepKind.ASSERTION,
                "verify", "assert", "", "", "", "local", "", "equals", Map.of(), "");
        V03ExecutionPlan plan = new V03ExecutionPlan("S", "local", Path.of("."),
                new V03SuiteMetadata("v0.3", "S", "local"),
                new V03EnvironmentProfile("local", "local", "per_run", "framework_verification_only", false),
                Map.of(), Map.of("test.v0.3", contract), Map.of(), java.util.List.of(),
                java.util.List.of(producer, assertion), java.util.List.of(), "digest");

        assertThat(new V03OutputRedactor().redactAssertionValue(
                plan, assertion, "step://login/auth.token", "raw-token",
                Map.of(new V03StepOutputKey("TC-1", "login", "auth.token"), output("login", "target", "auth.token",
                        V03ValueType.STRING, V03Sensitivity.SECRET, false, "raw-token"))))
                .isEqualTo(V03OutputRedactor.MASKED);
    }

    @Test
    void appliesContractRedactionToNestedEvidencePaths() {
        Object redacted = new V03OutputRedactor().redactEvidenceValue(
                Map.of("request", Map.of("headers", Map.of("client_trace", "internal-trace"))),
                Set.of("request.headers.client_trace"));

        @SuppressWarnings("unchecked")
        Map<String, Object> request = (Map<String, Object>) ((Map<String, Object>) redacted).get("request");
        @SuppressWarnings("unchecked")
        Map<String, Object> headers = (Map<String, Object>) request.get("headers");
        assertThat(headers).containsEntry("client_trace", V03OutputRedactor.MASKED);
    }

    private V03ProducedOutput output(
            String step, String target, String name, V03ValueType type, V03Sensitivity sensitivity,
            boolean bindable, Object value) {
        return new V03ProducedOutput("TC-1", step, target, "test.v0.3", "operation", name,
                type, sensitivity, bindable, value);
    }
}
