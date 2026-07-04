package com.specdriven.regression;

import com.specdriven.regression.cli.RegressionCommand;
import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.PrintStream;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RegressionApplication {

    public static void main(String[] args) {
        int exitCode = runCli(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int runCli(String[] args, PrintStream out, PrintStream err) {
        return new RegressionCommand(new ProductRepoService(), new ReleasePackageService()).execute(args, out, err);
    }
}
