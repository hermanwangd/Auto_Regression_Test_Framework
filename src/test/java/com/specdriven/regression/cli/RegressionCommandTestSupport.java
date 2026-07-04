package com.specdriven.regression.cli;

import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;

public final class RegressionCommandTestSupport {

    private RegressionCommandTestSupport() {
    }

    public static RegressionCommand legacyRpModeCommand() {
        RegressionCommand command = new RegressionCommand(new ProductRepoService(), new ReleasePackageService());
        command.enableLegacyRpModeForCompatibilityTests();
        return command;
    }
}
