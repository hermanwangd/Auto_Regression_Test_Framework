package com.featuredeliveryintelligence.fdi.application;

import com.featuredeliveryintelligence.fdi.orchestration.CogneeSearchClient;
import java.util.*;
import java.util.function.Function;

/** Separate source-set bridge. Run only in an owned dedicated process with private bounded stdout. */
public final class ProductTestKnowledgePreparationBridge {
    private ProductTestKnowledgePreparationBridge() {}
    /** The ordinary entry point retains actual host's default authorization denial. */
    public static void main(String[] args) {
        try { check(args);Dev204Cli.handles(args); }
        catch(Exception failure) { System.err.println("HOST_AUTHORIZATION_OR_RECEIPT_REJECTED");System.exit(2); }
    }
    /** Trusted Java composition only: editable mission/env/CLI options cannot supply this factory. */
    public static void select(String[] args,Function<Map<String,String>,CogneeSearchClient> independentlyAuthorizedClient) {
        check(args);if(!List.of(args).contains("selection"))throw new IllegalArgumentException("INVALID_PHASE");
        Dev204Cli.handles(args,Objects.requireNonNull(independentlyAuthorizedClient));
    }
    /** Replay consumes the exact original snapshot, no network options and no acquisition/adoption claim. */
    public static void replay(String[] args) {
        check(args);if(!List.of(args).contains("feedback")||List.of(args).contains("--base-url")||List.of(args).contains("--dataset-id"))throw new IllegalArgumentException("INVALID_PHASE");
        Dev204Cli.handles(args);
    }
    private static void check(String[] args) {
        if(args.length<1||!"dev204-knowledge-consume".equals(args[0]))throw new IllegalArgumentException("INVALID_BRIDGE_COMMAND");
    }
}
