package com.specdriven.regression.provider.wiremock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.specdriven.regression.contract.v03.V03OutputRedactor;
import java.net.URI;

public final class WireMockJournal {
    public static final String REQUEST_ID_HEADER = "X-Regress-Request-Id";
    private static final V03OutputRedactor REDACTOR = new V03OutputRedactor();

    private WireMockJournal() { }

    static ArrayNode matchedRequests(JsonNode journal, String method, URI applicationUri, String requestId) {
        if (journal == null || !journal.path("requests").isArray() || requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Expected a WireMock request list and runner request ID.");
        }
        String target = applicationUri.getRawPath();
        if (applicationUri.getRawQuery() != null) target += "?" + applicationUri.getRawQuery();
        ArrayNode matched = JsonNodeFactory.instance.arrayNode();
        for (JsonNode event : journal.path("requests")) {
            JsonNode request = event.path("request");
            if (method.equals(request.path("method").textValue()) && target.equals(request.path("url").textValue())
                    && hasRequestId(request.path("headers"), requestId)) {
                matched.add(request);
            }
        }
        return matched;
    }

    private static boolean hasRequestId(JsonNode headers, String requestId) {
        for (var field : headers.properties()) {
            if (REQUEST_ID_HEADER.equalsIgnoreCase(field.getKey())) {
                JsonNode value = field.getValue();
                return requestId.equals(value.textValue())
                        || (value.isArray() && value.size() == 1 && requestId.equals(value.get(0).textValue()));
            }
        }
        return false;
    }

    public static String evidence(JsonNode journal, String method, URI applicationUri, String requestId) {
        var matches = matchedRequests(journal, method, applicationUri, requestId);
        if (matches.isEmpty()) throw new IllegalArgumentException("WireMock did not record this request.");
        var document = JsonNodeFactory.instance.objectNode();
        var events = document.putArray("requests");
        String decodedPath = applicationUri.getPath();
        String safePath = REDACTOR.redactMessage(decodedPath).equals(decodedPath)
                ? applicationUri.getRawPath() : V03OutputRedactor.MASKED;
        for (JsonNode ignored : matches) {
            events.addObject().putObject("request").put("method", method).put("url", safePath);
        }
        return document.toPrettyString() + "\n";
    }
}
