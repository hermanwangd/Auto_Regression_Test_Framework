package com.specdriven.regression.provider;

import java.io.IOException;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Path components accepted by the Kubernetes apps/v1 Deployment API. */
enum K8sApiResource {
    NAMESPACE("K8S_NAMESPACE", 63, "[a-z0-9](?:[-a-z0-9]*[a-z0-9])?"),
    DEPLOYMENT("K8S_DEPLOYMENT", 253, "[a-z0-9](?:[-a-z0-9]*[a-z0-9])?(?:\\.[a-z0-9](?:[-a-z0-9]*[a-z0-9])?)*");

    private static final List<String> DEPLOYMENT_PREFIXES = List.of("deployment/", "deploy/", "deployments/");
    private final String binding;
    private final int maxLength;
    private final Pattern names;

    K8sApiResource(String binding, int maxLength, String pattern) {
        this.binding = binding;
        this.maxLength = maxLength;
        this.names = Pattern.compile(pattern);
    }

    String resolve(String reference, Function<String, String> environment) throws IOException {
        String value = reference.equals("env://" + binding) ? environment.apply(binding) : reference;
        String name = resourceName(value);
        if (!validName(name)) throw invalidName();
        return name;
    }

    void checkReference(String reference) throws IOException {
        if (!validReference(reference)) throw invalidName();
    }

    String evidenceRef(String reference) {
        return reference.isEmpty() || validReference(reference) ? reference : "[MASKED]";
    }

    private boolean validReference(String reference) {
        return reference.equals("env://" + binding) || validName(resourceName(reference));
    }

    private IOException invalidName() {
        return new IOException("K8s API requires a valid resource name or its dedicated env://" + binding + " binding.");
    }

    private String resourceName(String value) {
        if (value != null && this == DEPLOYMENT) {
            for (String prefix : DEPLOYMENT_PREFIXES) {
                if (value.startsWith(prefix)) return value.substring(prefix.length());
            }
        }
        return value;
    }

    private boolean validName(String name) {
        return name != null && name.length() <= maxLength && names.matcher(name).matches();
    }
}
