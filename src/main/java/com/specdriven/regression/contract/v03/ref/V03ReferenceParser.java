package com.specdriven.regression.contract.v03.ref;

import java.util.regex.Pattern;

public final class V03ReferenceParser {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*");
    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public V03Reference parse(Object value) {
        if (!(value instanceof String text)) {
            return new V03Reference.Literal(value);
        }
        if (text.startsWith("artifact://")) {
            return artifact(text.substring("artifact://".length()));
        }
        if (text.startsWith("step://")) {
            return step(text.substring("step://".length()));
        }
        if (text.startsWith("generated://")) {
            return generated(text.substring("generated://".length()));
        }
        if (text.startsWith("env://")) {
            String name = text.substring("env://".length());
            if (!ENVIRONMENT_NAME.matcher(name).matches()) {
                throw invalid("invalid_environment_ref");
            }
            return new V03Reference.Environment(name);
        }
        return new V03Reference.Literal(value);
    }

    private V03Reference.Artifact artifact(String body) {
        Fragment fragment = fragment(body);
        int slash = fragment.path().indexOf('/');
        if (slash < 1 || slash == fragment.path().length() - 1) {
            throw invalid("invalid_artifact_ref");
        }
        String root = fragment.path().substring(0, slash);
        if (!IDENTIFIER.matcher(root).matches()) {
            throw invalid("invalid_artifact_ref");
        }
        return new V03Reference.Artifact(root, fragment.path().substring(slash + 1), fragment.jsonPointer());
    }

    private V03Reference.Step step(String body) {
        Fragment fragment = fragment(body);
        int slash = fragment.path().indexOf('/');
        if (slash < 1 || slash == fragment.path().length() - 1) {
            throw invalid("invalid_step_ref");
        }
        String stepId = fragment.path().substring(0, slash);
        if (!IDENTIFIER.matcher(stepId).matches()) {
            throw invalid("invalid_step_ref");
        }
        return new V03Reference.Step(stepId, fragment.path().substring(slash + 1), fragment.jsonPointer());
    }

    private V03Reference.Generated generated(String body) {
        Fragment fragment = fragment(body);
        int slash = fragment.path().indexOf('/');
        if (slash < 1 || slash == fragment.path().length() - 1
                || fragment.path().indexOf('/', slash + 1) >= 0) {
            throw invalid("invalid_generated_ref");
        }
        String target = fragment.path().substring(0, slash);
        if (!IDENTIFIER.matcher(target).matches()) {
            throw invalid("invalid_generated_ref");
        }
        return new V03Reference.Generated(target, fragment.path().substring(slash + 1), fragment.jsonPointer());
    }

    private Fragment fragment(String body) {
        String[] fragments = body.split("#", 2);
        String pointer = fragments.length == 2 ? fragments[1] : "";
        if (!pointer.isEmpty() && !pointer.startsWith("/")) {
            throw invalid("invalid_json_pointer");
        }
        return new Fragment(fragments[0], pointer);
    }

    private IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code + ": use the v0.3 public reference form.");
    }

    private record Fragment(String path, String jsonPointer) {
    }
}
