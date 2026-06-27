package com.specdriven.regression.provider;

record DeploymentReadinessProbeResult(String stdout, String actualOutput, int checkCount) {
}
