package com.specdriven.regression.provider;

import java.io.IOException;

interface DeploymentReadinessProbe {

    DeploymentReadinessProbeResult check(DeploymentReadinessProbeRequest request)
            throws IOException, InterruptedException;
}
