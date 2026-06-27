package com.specdriven.regression.provider;

import java.util.Map;

record DeploymentReadinessProbeRequest(
        String providerName,
        String providerType,
        String readinessProbe,
        String kubeContextRef,
        String namespaceRef,
        String deploymentRef,
        String serviceRef,
        String targetSelector,
        String hostRef,
        int port,
        String deployedVersionRef,
        int timeoutSeconds,
        Map<String, Object> contract) {
}
