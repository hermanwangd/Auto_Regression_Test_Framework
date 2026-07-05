package com.specdriven.regression.contract;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;

final class FrameworkProviderContractCatalog {

    private static final String INDEX_FILE = "provider-contracts.index";

    private static Path bundledDirectory;

    private FrameworkProviderContractCatalog() {
    }

    static Path resolveDirectory(Path suiteRoot, Path frameworkProviderContracts) {
        Path cwdCandidate = Path.of("").toAbsolutePath().normalize().resolve(frameworkProviderContracts);
        if (Files.isDirectory(cwdCandidate)) {
            return cwdCandidate;
        }
        for (Path cursor = suiteRoot; cursor != null; cursor = cursor.getParent()) {
            Path candidate = cursor.resolve(frameworkProviderContracts).normalize();
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return bundledDirectory(frameworkProviderContracts);
    }

    private static synchronized Path bundledDirectory(Path frameworkProviderContracts) {
        if (bundledDirectory != null && Files.isDirectory(bundledDirectory)) {
            return bundledDirectory;
        }
        try {
            Path target = Files.createTempDirectory("regress-provider-contracts-");
            target.toFile().deleteOnExit();
            ClassLoader loader = FrameworkProviderContractCatalog.class.getClassLoader();
            String resourceRoot = frameworkProviderContracts.toString().replace('\\', '/');
            ArrayList<String> fileNames = bundledContractFiles(loader, resourceRoot);
            if (fileNames.isEmpty()) {
                throw new IllegalStateException("No bundled provider contracts found under `" + resourceRoot
                        + "`. Ensure provider-contracts.index is packaged with the application.");
            }
            for (String fileName : fileNames) {
                Path destination = target.resolve(fileName);
                String resourceName = resourceRoot + "/" + fileName;
                try (InputStream input = loader.getResourceAsStream(resourceName)) {
                    if (input == null) {
                        throw new IllegalStateException("Bundled provider contract index references missing resource `"
                                + resourceName + "`.");
                    }
                    Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
                    destination.toFile().deleteOnExit();
                }
            }
            bundledDirectory = target;
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to materialize bundled provider contracts.", e);
        }
    }

    private static ArrayList<String> bundledContractFiles(ClassLoader loader, String resourceRoot) throws IOException {
        ArrayList<String> indexed = indexedContractFiles(loader, resourceRoot);
        if (!indexed.isEmpty()) {
            return indexed;
        }
        return explodedClasspathContractFiles(loader, resourceRoot);
    }

    private static ArrayList<String> indexedContractFiles(ClassLoader loader, String resourceRoot) throws IOException {
        String indexResource = resourceRoot + "/" + INDEX_FILE;
        try (InputStream input = loader.getResourceAsStream(indexResource)) {
            if (input == null) {
                return new ArrayList<>();
            }
            ArrayList<String> fileNames = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String normalized = line.strip().replace('\\', '/');
                    if (normalized.isBlank() || normalized.startsWith("#")) {
                        continue;
                    }
                    Path path = Path.of(normalized);
                    if (path.getFileName() == null) {
                        continue;
                    }
                    String fileName = path.getFileName().toString();
                    if (fileName.endsWith(".yaml") && !fileNames.contains(fileName)) {
                        fileNames.add(fileName);
                    }
                }
            }
            fileNames.sort(Comparator.naturalOrder());
            return fileNames;
        }
    }

    private static ArrayList<String> explodedClasspathContractFiles(ClassLoader loader, String resourceRoot) throws IOException {
        URL root = loader.getResource(resourceRoot);
        if (root == null || !"file".equals(root.getProtocol())) {
            return new ArrayList<>();
        }
        try {
            ArrayList<String> fileNames = new ArrayList<>();
            try (var paths = Files.list(Path.of(root.toURI()))) {
                paths.filter(path -> path.getFileName().toString().endsWith(".yaml"))
                        .map(path -> path.getFileName().toString())
                        .sorted()
                        .forEach(fileNames::add);
            }
            return fileNames;
        } catch (URISyntaxException e) {
            throw new IOException("Failed to inspect provider contract classpath resources.", e);
        }
    }
}
