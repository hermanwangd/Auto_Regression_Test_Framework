package com.specdriven.regression.evidence;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.specdriven.regression.report.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Frozen PUBLIC_SYNTHETIC metadata controls; no source lookup, oracle evaluation or provider dispatch. */
class OracleCoverageTraceTest {
    @TempDir Path root;
    static final ObjectMapper JSON=new ObjectMapper();
    ReviewedGrpcProofIdentityTest fixture(String name,boolean v2)throws Exception {
        var f=new ReviewedGrpcProofIdentityTest();f.root=Files.createDirectories(root.resolve(name));f.validHistoricalFixture();
        if(v2)KnowledgeV2Fixture.augment(f);
        // Complete the reused EHS-only control using the existing canonical summary model/calculator.
        var calculator=new com.specdriven.regression.summary.SuiteSummaryCalculator();
        var counts=calculator.counts(List.of("passed"),"complete");
        var summary=new com.specdriven.regression.summary.SuiteSummaryDocument("v0.3","HISTORICAL-GRPC","BATCH-1","RUN-1","local","passed","complete",null,
                "2000-01-01T00:00:01Z","2000-01-01T00:00:02Z",1000,"2000-01-01T00:00:02Z","0.3.2","v0.3","fixture-suite.yaml","sha256:"+"c".repeat(64),
                counts,calculator.childCounts(List.of(),0),counts,
                new com.specdriven.regression.summary.SuiteSummaryDocument.FailureSummary(0,0,0,0,Map.of(),List.of(),List.of()),
                new com.specdriven.regression.summary.SuiteSummaryDocument.EvidenceSummary(5,true,"evidence_index.yaml"),List.of(),List.of());
        Files.writeString(f.root.resolve("fixture-suite.yaml"),"PUBLIC_SYNTHETIC_SUMMARY_REFERENCE_ONLY\n");
        f.write("suite_summary.json",new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).valueToTree(summary));f.save();
        var canonical=new com.specdriven.regression.contract.ContractBaselineService().report(f.resultFile);
        assertThat(canonical.valid()).as("canonical fixture findings: %s",canonical.findings()).isTrue();
        assertThat(f.validate().valid()).as("reviewed fixture findings: %s",f.validate().findings()).isTrue();return f;
    }
    CanonicalCoverageReportResult project(ReviewedGrpcProofIdentityTest f,String name,Path catalog,Path selection){return new CoverageReportService().projectCanonical(f.resultFile,root.resolve(name),catalog,selection);}
    JsonNode row(CanonicalCoverageReportResult report){assertThat(report.document().path("oracle_trace").size()).isEqualTo(1);return report.document().path("oracle_trace").get(0);}
    @Test void exactReviewedCasePointerOracleJoinRetainsVersionsEvidenceAndUnknownIndependence()throws Exception {
        var f=fixture("run",true);var report=project(f,"report",null,null);var row=row(report);
        assertThat(row.path("test_case_id").asText()).isEqualTo("TC-1");assertThat(row.path("qualified_case_ref").asText()).isEqualTo(KnowledgeV2Fixture.CASE);
        assertThat(row.path("expected_ref").asText()).isEqualTo("expected_results/oracle.json");assertThat(row.path("expected_ref_state").asText()).isEqualTo("BOUND_DECLARED_REFERENCE");
        assertThat(row.path("binding_state").asText()).isEqualTo("SOURCE_BOUND");assertThat(row.at("/oracle/qualified_ref").asText()).isEqualTo(KnowledgeV2Fixture.ORACLE);
        assertThat(row.at("/oracle/sha256").asText()).isEqualTo("9".repeat(64));assertThat(row.at("/oracle/source_commit").asText()).isEqualTo(KnowledgeV2Fixture.A);
        assertThat(row.at("/oracle/source_testrepo_version").asText()).isEqualTo(KnowledgeV2Fixture.A).isNotEqualTo(KnowledgeV2Fixture.X);
        assertThat(row.path("source_result_sha256").asText()).isEqualTo(sha(f.resultFile));assertThat(row.path("run_id").asText()).isEqualTo("RUN-1");
        assertThat(row.at("/independence/state").asText()).isEqualTo("UNKNOWN");assertThat(row.at("/independence/reason").asText()).isEqualTo("independence_attestation_not_in_contract");
        assertThat(report.document().at("/cases/0/oracle_trace_refs/0").asText()).isEqualTo(row.path("trace_id").asText());
        assertThat(report.document().at("/metrics/requirements/denominator").isNull()).isTrue();assertThat(report.document().at("/risk_inventory/denominator").isNull()).isTrue();
        assertThat(Files.readString(report.markdown())).contains("oracle_trace","SOURCE_BOUND","UNKNOWN","declared assertion reference","does not prove");
    }
    @Test void missingOracleDeclarationIsExplicitWithoutInventingAPointer()throws Exception {
        var f=fixture("run",true);((ObjectNode)f.provenance.path("requirement_mapping").get(0)).putArray("oracle_refs");f.save();var row=row(project(f,"report",null,null));
        assertThat(row.path("expected_ref").isNull()).isTrue();assertThat(row.path("expected_ref_state").asText()).isEqualTo("UNKNOWN");assertThat(row.path("expected_ref_reason").asText()).isEqualTo("oracle_reference_missing");
    }
    @Test void singletonUnrelatedPointerIsNotJoinedToAnOracleByCardinality()throws Exception {
        var f=fixture("run",true);((ObjectNode)f.provenance.path("requirement_mapping").get(0)).putArray("oracle_refs").add("test_cases/case.yaml#verify-1");f.save();var row=row(project(f,"report",null,null));
        assertThat(row.path("expected_ref").isNull()).isTrue();assertThat(row.path("expected_ref_state").asText()).isEqualTo("UNKNOWN");assertThat(row.path("expected_ref_reason").asText()).isEqualTo("declared_assertion_relation_not_exported");
        assertThat(row.path("declared_oracle_refs").get(0).asText()).isEqualTo("test_cases/case.yaml#verify-1");
    }
    @Test void multipleDeclaredPointersRemainAmbiguous()throws Exception {
        var f=fixture("run",true);((ObjectNode)f.provenance.path("requirement_mapping").get(0)).withArray("oracle_refs").add("test_cases/case.yaml#verify-1");f.save();var report=project(f,"report",null,null);
        assertThat(report.document().path("oracle_trace").size()).isEqualTo(2);for(JsonNode row:report.document().path("oracle_trace")){assertThat(row.path("expected_ref").isNull()).isTrue();assertThat(row.path("expected_ref_reason").asText()).isEqualTo("mapping_case_oracle_join_ambiguous");}
    }
    @Test void multiCaseMappingCannotCreatePerCaseExpectedAssertionClaims()throws Exception {
        var f=fixture("run",false);f.provenance.putArray("requirement_mapping").add(ReviewedGrpcProofIdentityTest.obj("requirement_ref","fixture:req","test_case_ids",List.of("TC-1","TC-2"),"oracle_refs",List.of("oracle.json"),"review_ref","fixture:review"));f.provenance.withArray("reviewed_files").add(ReviewedGrpcProofIdentityTest.obj("path","oracle.json","role","oracle","sha256","9".repeat(64)));f.save();var row=row(project(f,"report",null,null));
        assertThat(row.path("expected_ref").isNull()).isTrue();assertThat(row.path("expected_ref_reason").asText()).isEqualTo("mapping_case_oracle_join_ambiguous");
    }
    @Test void legalLegacyMissingMappingIsUnknownAndDoesNotInventPKOrRepositoryIdentity()throws Exception {
        var f=fixture("run",false);var row=row(project(f,"report",null,null));assertThat(row.path("binding_state").asText()).isEqualTo("MISSING");assertThat(row.path("expected_ref").isNull()).isTrue();assertThat(row.at("/oracle/qualified_ref").isNull()).isTrue();assertThat(row.path("knowledge_refs").isEmpty()).isTrue();
    }
    @Test void legacyReviewedFilePointerBindsOnlyItsExistingReviewedHash()throws Exception {
        var f=fixture("run",false);f.provenance.putArray("requirement_mapping").add(ReviewedGrpcProofIdentityTest.obj("requirement_ref","fixture:req","test_case_ids",List.of("TC-1"),"oracle_refs",List.of("oracle.json"),"review_ref","fixture:review"));f.provenance.withArray("reviewed_files").add(ReviewedGrpcProofIdentityTest.obj("path","oracle.json","role","oracle","sha256","9".repeat(64)));f.save();var row=row(project(f,"report",null,null));
        assertThat(row.path("expected_ref").asText()).isEqualTo("oracle.json");assertThat(row.at("/oracle/sha256").asText()).isEqualTo("9".repeat(64));assertThat(row.at("/oracle/source_commit").isNull()).isTrue();assertThat(row.at("/oracle/qualified_ref").isNull()).isTrue();assertThat(row.at("/independence/state").asText()).isEqualTo("UNKNOWN");
    }
    @Test void conflictingModernOracleHashFailsClosed()throws Exception {
        var f=fixture("run",true);((ObjectNode)f.provenance.at("/knowledge_trace/0/oracle")).put("sha256","0".repeat(64));f.save();var report=project(f,"report",null,null);assertThat(report.status()).isEqualTo("INVALID");assertThat(row(report).path("binding_state").asText()).isEqualTo("INVALID");assertThat(row(report).path("expected_ref").isNull()).isTrue();
    }
    @Test void wrongQualifiedCaseJoinNeverCreditsAnOracle()throws Exception {
        var f=fixture("run",true);((ObjectNode)f.provenance.path("knowledge_trace").get(0)).put("qualified_case_ref",KnowledgeV2Fixture.CASE.replace("#TC-1","#OTHER"));f.save();var report=project(f,"report",null,null);assertThat(report.status()).isEqualTo("INVALID");assertThat(row(report).path("binding_state").asText()).isEqualTo("INVALID");
    }
    @Test void duplicateSourceOracleBindingFailsClosed()throws Exception {
        var f=fixture("run",true);f.provenance.withArray("executed_source_bindings").add(f.provenance.path("executed_source_bindings").get(4).deepCopy());f.save();var report=project(f,"report",null,null);assertThat(report.status()).isEqualTo("INVALID");assertThat(row(report).path("binding_state").asText()).isEqualTo("INVALID");
    }
    Path selection(ReviewedGrpcProofIdentityTest a,ReviewedGrpcProofIdentityTest newer,long age)throws Exception {
        var s=ReviewedGrpcProofIdentityTest.obj("schema_version","coverage-selection.v1","selection_id","fixture:selection","policy","latest_compatible_per_case","as_of","2000-01-01T00:00:10Z","max_age_seconds",age,"expected_scope",Map.of("domain","product","project_ref","fixture-project","workspace_ref","fixture-workspace","repository_revisions",JSON.convertValue(a.provenance.path("repository_revisions"),Map.class)));
        s.putArray("results").add(ReviewedGrpcProofIdentityTest.obj("path",a.resultFile.toString(),"sha256",sha(a.resultFile))).add(ReviewedGrpcProofIdentityTest.obj("path",newer.resultFile.toString(),"sha256",sha(newer.resultFile)));Path p=root.resolve("selection.json");JSON.writeValue(p.toFile(),s);return p;
    }
    @Test void newerFailOwnsItsTraceInsteadOfOlderPass()throws Exception {
        var a=fixture("older",true);var b=fixture("newer",true);b.result.put("end_time","2000-01-01T00:00:03Z");b.failed("UNAVAILABLE",true,true);var row=row(project(b,"report",null,selection(a,b,60)));
        assertThat(row.path("canonical_outcome").asText()).isEqualTo("FAIL");assertThat(row.path("source_result_sha256").asText()).isEqualTo(sha(b.resultFile));assertThat(row.at("/independence/state").asText()).isEqualTo("UNKNOWN");
    }
    @Test void staleSelectedRunCannotClaimCurrentBoundPointer()throws Exception {
        var a=fixture("older",true);var b=fixture("newer",true);b.result.put("end_time","2000-01-01T00:00:03Z");b.save();var row=row(project(b,"report",null,selection(a,b,1)));
        assertThat(row.path("binding_state").asText()).isEqualTo("STALE");assertThat(row.path("expected_ref").isNull()).isTrue();assertThat(row.path("source_result_sha256").asText()).isEqualTo(sha(b.resultFile));
    }
    @Test void publicPKLinksAreExactAndContainNoPrivateBodies()throws Exception {
        var f=fixture("run",true);var row=row(project(f,"report",null,null));assertThat(row.at("/knowledge_refs/0/context_sha256").asText()).isEqualTo("2".repeat(64));assertThat(row.at("/knowledge_refs/0/qualified_source_ref").asText()).isEqualTo(KnowledgeV2Fixture.SOURCE);assertThat(row.toString()).doesNotContain("private_original_receipt","raw_context","proposal_body","access_token");
    }
    @Test void canonicalTreeAndTraceIdentityRemainStableAcrossProjection()throws Exception {
        var f=fixture("run",true);String before=sha(f.resultFile),provenance=sha(f.root.resolve(ReviewedGrpcProofIdentityTest.HANDOFF));var a=project(f,"a",null,null);var b=project(f,"b",null,null);
        assertThat(row(a).path("trace_id")).isEqualTo(row(b).path("trace_id"));assertThat(sha(f.resultFile)).isEqualTo(before);assertThat(sha(f.root.resolve(ReviewedGrpcProofIdentityTest.HANDOFF))).isEqualTo(provenance);
    }
    @Test void authoritativeEntityLinksUseExactQualifiedCaseAndRequirementWithoutChangingMetrics()throws Exception {
        var f=fixture("run",true);var catalog=ReviewedGrpcProofIdentityTest.obj("schema_version","coverage-catalog.v1","catalog_revision","fixture:catalog",
                "scope",Map.of("domain","product","project_ref","fixture-project","workspace_ref","fixture-workspace","repository_revisions",JSON.convertValue(f.provenance.path("repository_revisions"),Map.class)),
                "authority",Map.of("kind","reviewed_inventory","source_ref","fixture:inventory","source_sha256","a".repeat(64),"review_ref","fixture:review","approved_by","fixture:owner","approved_at","1999-12-31T23:59:59Z"));
        String ac=KnowledgeV2Fixture.REPO+"@"+KnowledgeV2Fixture.A+":spec.md#AC1";
        catalog.putArray("requirements").add(ReviewedGrpcProofIdentityTest.obj("id",KnowledgeV2Fixture.REQ,"ac_ids",List.of(ac)));
        catalog.putArray("acceptance_criteria").add(ReviewedGrpcProofIdentityTest.obj("id",ac,"requirement_id",KnowledgeV2Fixture.REQ,"classification","automatable","review_ref","fixture:review","test_case_ids",List.of(KnowledgeV2Fixture.CASE)));
        Path path=root.resolve("catalog.json");JSON.writeValue(path.toFile(),catalog);var report=project(f,"report",path,null);var row=row(report);
        assertThat(row.path("acceptance_criteria_refs").get(0).asText()).isEqualTo(ac);
        for(JsonNode entity:report.document().path("entities"))assertThat(entity.path("oracle_trace_refs").get(0).asText()).isEqualTo(row.path("trace_id").asText());
        assertThat(report.document().at("/metrics/acceptance_criteria/satisfied").asInt()).isEqualTo(1);assertThat(row.at("/independence/state").asText()).isEqualTo("UNKNOWN");
    }
    @Test void wrongQualifiedOracleIdentityIsInvalidEvenWithAnOtherwisePlausibleHash()throws Exception {
        var f=fixture("run",true);((ObjectNode)f.provenance.at("/knowledge_trace/0/oracle")).put("qualified_ref",KnowledgeV2Fixture.ORACLE.replace("fixture:product","fixture:other"));f.save();var report=project(f,"report",null,null);
        assertThat(report.status()).isEqualTo("INVALID");assertThat(row(report).path("binding_state").asText()).isEqualTo("INVALID");assertThat(row(report).path("expected_ref").isNull()).isTrue();
    }
    @Test void conflictingPKContextCannotBeRepublishedAsValidatedMetadata()throws Exception {
        var f=fixture("run",true);((ObjectNode)f.provenance.path("knowledge_trace").get(0)).put("context_sha256","0".repeat(64));f.save();var report=project(f,"report",null,null);
        assertThat(report.status()).isEqualTo("INVALID");assertThat(row(report).path("knowledge_refs").isEmpty()).isTrue();assertThat(row(report).path("expected_ref").isNull()).isTrue();
    }
    @Test void markdownKeepsRiskRowInMetricTableBeforeIndependenceProse()throws Exception {
        var f=fixture("format",false);var report=project(f,"format-report",null,null);String md=Files.readString(report.markdown());
        assertThat(md).contains("| risks | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN |\n\nOracle SOURCE_BOUND");
        String table=md.substring(md.indexOf("| Metric |"),md.indexOf("Oracle SOURCE_BOUND"));
        assertThat(table.lines().filter(line->!line.isBlank()).toList()).hasSize(5).allMatch(line->line.startsWith("|")&&line.endsWith("|"));
    }

    private ObjectNode parserRelation(ReviewedGrpcProofIdentityTest f)throws Exception {
        ObjectNode section=f.provenance.putObject("parser_expected_artifact_relations").put("schema_version","parser-expected-artifact-relations.v1").put("state","COMPLETE").put("manifest_path","suite.yaml").put("manifest_sha256","6".repeat(64));
        section.putArray("assertions").add(ReviewedGrpcProofIdentityTest.obj("qualified_case_ref",KnowledgeV2Fixture.CASE,"case_path","test_cases/case.yaml","case_sha256","8".repeat(64),"test_case_id","TC-1","assertion_id","total_oracle","assertion_operator","json_match","expected_operand","EXPECTED_REF","raw_expected_ref","artifact://expected_results/oracle.json","normalized_expected_path","expected_results/oracle.json","expected_artifact_sha256","9".repeat(64),"qualified_expected_artifact_ref",KnowledgeV2Fixture.ORACLE));
        f.save();return section;
    }
    @Test void parserSourceExportCannotAuthorizeExpectedRefWithoutIndependentProducerAuthority()throws Exception {
        var f=fixture("parser",true);((ObjectNode)f.provenance.path("requirement_mapping").get(0)).withArray("oracle_refs").add("test_cases/case.yaml#status");parserRelation(f);
        var report=project(f,"parser-report",null,null);assertThat(report.document().path("oracle_trace").size()).isEqualTo(2);
        var file=report.document().path("oracle_trace").get(0);var literal=report.document().path("oracle_trace").get(1);
        assertThat(file.path("expected_ref").isNull()).isTrue();
        assertThat(file.path("expected_ref_state").asText()).isEqualTo("UNKNOWN");
        assertThat(literal.path("expected_ref").isNull()).isTrue();assertThat(file.at("/independence/state").asText()).isEqualTo("UNKNOWN");
        assertThat(report.document().at("/metrics/requirements/denominator").isNull()).isTrue();
    }
    @Test void presentMalformedParserRelationCannotHideBehindOtherwiseValidKnowledge()throws Exception {
        var f=fixture("parser",true);parserRelation(f).put("state","SELF_REVIEWED");f.save();
        assertThatThrownBy(()->project(f,"invalid-parser",null,null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("parser");
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"manifest_hash","manifest_path","case_hash","case_ref","case_id","case_path","oracle_hash","oracle_ref","normalized_path","unsafe_uri","operator","literal_borrow","duplicate","unknown_field","unselected","source_A_vs_X"})
    void directParserSourceJoinsRejectResealedMetadataWithoutEarlierPinMasking(String mode)throws Exception {
        var f=fixture("parser-join",true);ObjectNode section=parserRelation(f),edge=(ObjectNode)section.path("assertions").get(0);
        switch(mode){
            case "manifest_hash"->section.put("manifest_sha256","0".repeat(64));case "manifest_path"->section.put("manifest_path","other.yaml");
            case "case_hash"->edge.put("case_sha256","0".repeat(64));case "case_ref"->edge.put("qualified_case_ref",KnowledgeV2Fixture.CASE.replace(KnowledgeV2Fixture.A,KnowledgeV2Fixture.X));case "case_id"->edge.put("test_case_id","OTHER");case "case_path"->edge.put("case_path","other/case.yaml");
            case "oracle_hash"->edge.put("expected_artifact_sha256","0".repeat(64));case "oracle_ref"->edge.put("qualified_expected_artifact_ref",KnowledgeV2Fixture.ORACLE.replace("#ORACLE1","#OTHER"));case "normalized_path"->edge.put("normalized_expected_path","different/oracle.json");case "unsafe_uri"->edge.put("raw_expected_ref","artifact://expected_results/%2e%2e/oracle.json");
            case "operator"->edge.put("assertion_operator","inferred");case "literal_borrow"->edge.put("expected_operand","EXPECTED");case "duplicate"->section.withArray("assertions").add(edge.deepCopy());case "unknown_field"->edge.put("raw_body","PUBLIC_SYNTHETIC_CANARY");case "unselected"->((ObjectNode)f.provenance.path("source_case_selection").get(0)).put("state","NOT_SELECTED");
            case "source_A_vs_X"->{for(JsonNode raw:f.provenance.path("executed_source_bindings"))if("case".equals(raw.path("role").asText()))((ObjectNode)raw).put("source_commit",KnowledgeV2Fixture.X);}
        }
        var type=Class.forName("com.specdriven.regression.report.CanonicalCoverageReport");var check=type.getDeclaredMethod("parserRelations",JsonNode.class);check.setAccessible(true);
        assertThatThrownBy(()->{try{check.invoke(null,f.provenance);}catch(java.lang.reflect.InvocationTargetException wrapped){throw wrapped.getCause();}}).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("parser expected artifact metadata");
    }
    @Test void twoExplicitFileConsumersRemainAmbiguousEvenForTheSameExactOracle()throws Exception {
        var f=fixture("two-edges",true);((ObjectNode)f.provenance.path("requirement_mapping").get(0)).withArray("oracle_refs").add("test_cases/case.yaml#status");ObjectNode section=parserRelation(f);ObjectNode second=(ObjectNode)section.path("assertions").get(0).deepCopy();second.put("assertion_id","second_ref");section.withArray("assertions").add(second);f.save();
        for(JsonNode row:project(f,"two-edges-report",null,null).document().path("oracle_trace"))assertThat(row.path("expected_ref").isNull()).isTrue();
    }
    static String sha(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
}
