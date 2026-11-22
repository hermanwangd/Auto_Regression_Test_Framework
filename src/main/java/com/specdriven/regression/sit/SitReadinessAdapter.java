package com.specdriven.regression.sit;

/**
 * Read-only readiness SPI. Register a public no-arg implementation with Java ServiceLoader.
 * Implementations are trusted code: never seed, deploy, clean up, reserve or execute tests.
 * Honor the request deadline, configure client timeouts, and cooperate with interruption.
 * Metadata methods must be nonblocking; check results must contain no secrets or raw payloads.
 */
public interface SitReadinessAdapter {
    String id();
    String version();
    default boolean simulated() { return false; }
    SitReadinessObservation check(SitReadinessRequest request) throws Exception;
}
