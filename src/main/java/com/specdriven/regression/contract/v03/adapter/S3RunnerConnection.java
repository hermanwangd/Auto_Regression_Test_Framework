package com.specdriven.regression.contract.v03.adapter;

import com.specdriven.regression.contract.v03.V03ExecutionContext;
import com.specdriven.regression.contract.v03.V03ResolvedTarget;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** Only the runner selects the S3 destination and signing credentials. */
final class S3RunnerConnection implements AutoCloseable {
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(12);
    private static final Map<String, Object> BINDINGS = Map.of(
            "endpoint_ref", "env://REGRESS_S3_ENDPOINT",
            "bucket_ref", "env://REGRESS_S3_BUCKET",
            "access_key_ref", "env://REGRESS_S3_ACCESS_KEY",
            "secret_key_ref", "env://REGRESS_S3_SECRET_KEY");

    final String bucket;
    final S3Client client;

    private S3RunnerConnection(String bucket, S3Client client) {
        this.bucket = bucket;
        this.client = client;
    }

    static S3RunnerConnection open(V03ExecutionContext context, String targetName) {
        V03ResolvedTarget target = context.targets().get(targetName);
        if (target == null || !BINDINGS.equals(target.bindings())) {
            throw new IllegalArgumentException("Use the declared S3 runner environment bindings.");
        }
        Map<String, String> env = context.environment();
        URI endpoint = URI.create(required(env, "REGRESS_S3_ENDPOINT"));
        if (!("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
                || endpoint.getHost() == null || endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                || !(endpoint.getRawPath().isEmpty() || "/".equals(endpoint.getRawPath()))
                || endpoint.getPort() == 0 || endpoint.getPort() > 65535) {
            throw new IllegalArgumentException("Invalid S3 endpoint.");
        }
        String bucket = required(env, "REGRESS_S3_BUCKET");
        if (!bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]") || bucket.contains("..")
                || bucket.matches("[0-9]+(\\.[0-9]+){3}")) {
            throw new IllegalArgumentException("Invalid S3 bucket.");
        }
        String region = env.getOrDefault("REGRESS_S3_REGION", "us-east-1");
        if (!region.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("Invalid S3 region.");
        }
        var credentials = AwsBasicCredentials.create(required(env, "REGRESS_S3_ACCESS_KEY"),
                required(env, "REGRESS_S3_SECRET_KEY"));
        S3Client client = S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .serviceConfiguration(config -> config.pathStyleAccessEnabled(true).chunkedEncodingEnabled(false))
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(5)))
                .overrideConfiguration(config -> config.apiCallAttemptTimeout(Duration.ofSeconds(8))
                        .apiCallTimeout(CALL_TIMEOUT))
                .build();
        return new S3RunnerConnection(bucket, client);
    }

    private static String required(Map<String, String> env, String variable) {
        String value = env.get(variable);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing S3 configuration.");
        return value;
    }

    @Override public void close() { client.close(); }
}
