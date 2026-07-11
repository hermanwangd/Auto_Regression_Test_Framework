package com.specdriven.regression.contract.v03.ref;

public final class V03ReferenceParser {

    public V03Reference parse(Object value) {
        if (!(value instanceof String text)) {
            return new V03Reference.Literal(value);
        }
        if (text.startsWith("artifact://")) {
            return artifact(text.substring("artifact://".length()));
        }
        if (text.startsWith("step://")) {
            return stepReference(text);
        }
        if (text.startsWith("generated://")) {
            return pathReference(text, "generated://", "invalid_generated_ref", V03Reference.Generated::new);
        }
        if (text.startsWith("env://")) {
            String name = text.substring("env://".length());
            if (name.isBlank() || name.contains("/") || name.contains(".")) {
                throw invalid("invalid_environment_ref");
            }
            return new V03Reference.Environment(name);
        }
        return new V03Reference.Literal(value);
    }

    private V03Reference.Artifact artifact(String body) {
        String[] fragments = body.split("#", 2);
        int slash = fragments[0].indexOf('/');
        if (slash < 1 || slash == fragments[0].length() - 1) {
            throw invalid("invalid_artifact_ref");
        }
        String pointer = fragments.length == 2 ? fragments[1] : "";
        if (!pointer.isEmpty() && !pointer.startsWith("/")) {
            throw invalid("invalid_json_pointer");
        }
        return new V03Reference.Artifact(fragments[0].substring(0, slash), fragments[0].substring(slash + 1), pointer);
    }

    private <T extends V03Reference> T pathReference(
            String text, String prefix, String errorCode, ReferenceFactory<T> factory) {
        String body = text.substring(prefix.length());
        int slash = body.indexOf('/');
        if (slash < 1 || slash == body.length() - 1 || body.indexOf('/', slash + 1) != -1 || body.contains(".")) {
            throw invalid(errorCode);
        }
        return factory.create(body.substring(0, slash), body.substring(slash + 1));
    }

    private V03Reference.Step stepReference(String text) {
        String body = text.substring("step://".length());
        int slash = body.indexOf('/');
        if (slash < 1 || slash == body.length() - 1) {
            throw invalid("invalid_step_ref");
        }
        return new V03Reference.Step(body.substring(0, slash), body.substring(slash + 1));
    }

    private IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code + ": use the v0.3 public reference form.");
    }

    @FunctionalInterface
    private interface ReferenceFactory<T extends V03Reference> {
        T create(String left, String right);
    }
}
