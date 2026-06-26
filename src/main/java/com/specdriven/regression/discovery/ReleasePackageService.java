package com.specdriven.regression.discovery;

import com.specdriven.regression.mapping.RpRuMappingGap;
import com.specdriven.regression.mapping.RpRuMappingService;
import com.specdriven.regression.mapping.RpRuMappingValidationReport;
import com.specdriven.regression.schema.ArtifactSchemaValidator;
import com.specdriven.regression.schema.ArtifactValidationError;
import com.specdriven.regression.schema.ArtifactValidationReport;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ReleasePackageService {

    private static final Path RELEASE_PACKAGES_ROOT = Path.of("docs/08-release/release-packages");
    private static final List<Path> REQUIRED_ARTIFACTS = List.of(
            Path.of("package.yaml"),
            Path.of("rp_feature_spec.md"),
            Path.of("rp_ru_mapping.yaml"),
            Path.of("acceptance_criteria.md"),
            Path.of("tests"),
            Path.of("expected-results"),
            Path.of("traceability.md"),
            Path.of("evidence_index.md"));

    private final ArtifactSchemaValidator artifactSchemaValidator;
    private final RpRuMappingService rpRuMappingService;

    public ReleasePackageService() {
        this(new ArtifactSchemaValidator(), new RpRuMappingService());
    }

    public ReleasePackageService(
            ArtifactSchemaValidator artifactSchemaValidator,
            RpRuMappingService rpRuMappingService) {
        this.artifactSchemaValidator = artifactSchemaValidator;
        this.rpRuMappingService = rpRuMappingService;
    }

    public ReleasePackageResult initialize(Path root, String rpId, String packageType) {
        Path packageRoot = packageRoot(root, rpId);
        try {
            Files.createDirectories(packageRoot);
            for (Path artifact : REQUIRED_ARTIFACTS) {
                Path absoluteArtifact = packageRoot.resolve(artifact);
                if (artifact.toString().equals("tests") || artifact.toString().equals("expected-results")) {
                    Files.createDirectories(absoluteArtifact);
                } else if (!Files.exists(absoluteArtifact)) {
                    Files.createDirectories(absoluteArtifact.getParent());
                    Files.writeString(absoluteArtifact, starterContent(artifact, rpId, packageType),
                            StandardOpenOption.CREATE_NEW);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to initialize Release Package: " + rpId, e);
        }
        return new ReleasePackageResult(packageRoot);
    }

    public ReleasePackageCompletenessReport checkCompleteness(Path root, String rpId) {
        return checkCompleteness(root, rpId, false);
    }

    public ReleasePackageCompletenessReport checkCompleteness(Path root, String rpId, boolean strictSchema) {
        Path packageRoot = packageRoot(root, rpId);
        List<ReleasePackageGap> gaps = new ArrayList<>();
        for (Path artifact : REQUIRED_ARTIFACTS) {
            Path absoluteArtifact = packageRoot.resolve(artifact);
            boolean present = artifact.toString().equals("tests") || artifact.toString().equals("expected-results")
                    ? Files.isDirectory(absoluteArtifact)
                    : Files.isRegularFile(absoluteArtifact);
            if (!present) {
                gaps.add(new ReleasePackageGap(
                        RELEASE_PACKAGES_ROOT.resolve(rpId).resolve(artifact),
                        "Create required RP artifact `" + artifact + "` for Release Package `" + rpId + "`."));
            }
        }
        List<ArtifactValidationError> packageSchemaErrors = new ArrayList<>();
        List<RpRuMappingGap> mappingGaps = new ArrayList<>();
        if (strictSchema && Files.isRegularFile(packageRoot.resolve("package.yaml"))) {
            ArtifactValidationReport packageReport =
                    artifactSchemaValidator.validatePackageYaml(packageRoot.resolve("package.yaml"));
            packageSchemaErrors.addAll(packageReport.errors());
        }
        if (strictSchema && Files.isRegularFile(packageRoot.resolve("rp_ru_mapping.yaml"))) {
            RpRuMappingValidationReport mappingReport =
                    rpRuMappingService.validate(packageRoot.resolve("rp_ru_mapping.yaml"));
            mappingGaps.addAll(mappingReport.gaps());
        }
        boolean complete = gaps.isEmpty() && packageSchemaErrors.isEmpty() && mappingGaps.isEmpty();
        return new ReleasePackageCompletenessReport(
                complete,
                complete ? "pass" : "fail",
                REQUIRED_ARTIFACTS,
                List.copyOf(gaps),
                List.copyOf(packageSchemaErrors),
                List.copyOf(mappingGaps));
    }

    private Path packageRoot(Path root, String rpId) {
        return root.resolve(RELEASE_PACKAGES_ROOT).resolve(rpId);
    }

    private String starterContent(Path artifact, String rpId, String packageType) {
        String name = artifact.toString();
        if (name.equals("package.yaml")) {
            return "rp_id: " + rpId + "\npackage_type: " + packageType + "\nstatus: draft\n";
        }
        if (name.endsWith(".md")) {
            return "# " + rpId + " " + name.replace('_', ' ').replace(".md", "") + "\n";
        }
        if (name.equals("rp_ru_mapping.yaml")) {
            return "rp_id: " + rpId + "\nrelease_units: []\n";
        }
        return "";
    }
}
