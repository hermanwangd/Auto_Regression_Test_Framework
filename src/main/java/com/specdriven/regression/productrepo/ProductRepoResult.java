package com.specdriven.regression.productrepo;

import java.nio.file.Path;
import java.util.List;

public record ProductRepoResult(List<Path> createdPaths, List<Path> skippedExistingPaths) {
}
