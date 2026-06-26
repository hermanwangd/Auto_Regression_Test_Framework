package com.specdriven.regression.productrepo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ProductRepoService {

    private static final List<Path> REQUIRED_PATHS = List.of(
            Path.of("docs/00-intake-scope"),
            Path.of("docs/01-specs"),
            Path.of("docs/02-architecture"),
            Path.of("docs/03-acceptance"),
            Path.of("docs/04-planning"),
            Path.of("docs/05-decisions-adr"),
            Path.of("docs/06-reviews"),
            Path.of("docs/07-validation-evidence"),
            Path.of("docs/08-release"),
            Path.of("docs/08-release/release-packages"),
            Path.of("docs/09-operations"),
            Path.of("docs/10-change-control"),
            Path.of("docs/99-archive"));

    public ProductRepoResult initialize(Path root) {
        List<Path> created = new ArrayList<>();
        List<Path> skipped = new ArrayList<>();
        for (Path requiredPath : REQUIRED_PATHS) {
            Path absolutePath = root.resolve(requiredPath);
            if (Files.exists(absolutePath)) {
                skipped.add(requiredPath);
                continue;
            }
            try {
                Files.createDirectories(absolutePath);
                created.add(requiredPath);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to create Product Repo path: " + requiredPath, e);
            }
        }
        return new ProductRepoResult(List.copyOf(created), List.copyOf(skipped));
    }

    public ProductRepoReadinessReport checkReadiness(Path root) {
        List<String> checkedItems = new ArrayList<>();
        List<ReadinessGap> gaps = new ArrayList<>();
        for (Path requiredPath : REQUIRED_PATHS) {
            checkedItems.add(requiredPath.toString());
            if (!Files.isDirectory(root.resolve(requiredPath))) {
                gaps.add(new ReadinessGap(
                        requiredPath,
                        "Create required Product Repo path `" + requiredPath + "` before defining RP scope."));
            }
        }
        boolean ready = gaps.isEmpty();
        String nextStep = ready
                ? "Run init-rp to create the first Release Package record."
                : "Create missing Product Repo lifecycle paths, then run check-readiness again.";
        return new ProductRepoReadinessReport(
                ready,
                ready ? "pass" : "fail",
                List.copyOf(checkedItems),
                List.copyOf(gaps),
                nextStep,
                false,
                false);
    }

    public List<Path> requiredPaths() {
        return Collections.unmodifiableList(REQUIRED_PATHS);
    }
}
