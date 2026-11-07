package com.specdriven.regression.report;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.specdriven.regression.cli.RegressionCommand;
import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CanonicalCoverageReportTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String TC = "EVIDENCE-HARDENING-TC-001";
    @TempDir Path tmp;
    record Run(int exit, JsonNode stdout, String err, Path dir) {}
    private Path fixture(String name, String status, int second) throws Exception {
        Path source = Path.of("samples/40-evidence-reporting/evidence_hardening");
        Path dir = tmp.resolve(name);
        try (var paths = Files.walk(source)) { for (Path p : paths.toList()) {
            Path target = dir.resolve(source.relativize(p));
            if (Files.isDirectory(p)) Files.createDirectories(target); else Files.copy(p, target);
        }}
        Path result = dir.resolve("valid_result.json");
        ObjectNode d = (ObjectNode) JSON.readTree(result.toFile());
        d.put("status", status); ((ObjectNode)d.path("test_results").get(0)).put("status", status);
        d.put("start_time", "2026-06-30T00:00:00Z").put("end_time", String.format("2026-06-30T00:00:%02dZ", second));
        d.put("project_ref", "project").put("workspace_ref", "workspace");
        d.putObject("repository_revisions").put("framework", "revision");
        JSON.writeValue(result.toFile(), d); return result;
    }
    private ObjectNode catalog(String classification) {
        ObjectNode c = JSON.createObjectNode().put("schema_version", "coverage-catalog.v1").put("catalog_revision", "catalog-revision");
        c.set("scope", JSON.valueToTree(Map.of("domain","product","project_ref","project","workspace_ref","workspace","repository_revisions",Map.of("framework","revision"))));
        c.set("authority", JSON.valueToTree(Map.of("kind","reviewed_inventory","source_ref","owner-inventory","source_sha256","a".repeat(64),"review_ref","review-1","approved_by","owner","approved_at","2026-06-29T00:00:00Z")));
        c.putArray("requirements").addObject().put("id","REQ-1").putArray("ac_ids").add("AC-1");
        ObjectNode ac = c.putArray("acceptance_criteria").addObject().put("id","AC-1").put("requirement_id","REQ-1").put("classification",classification).put("review_ref","review-1");
        ac.putArray("test_case_ids").add(TC); return c;
    }
    private Path write(String name, JsonNode d) throws Exception { Path p = tmp.resolve(name); JSON.writeValue(p.toFile(), d); return p; }
    private Run report(Path result, Path catalog, Path selection, String name) throws Exception {
        Path dir = tmp.resolve(name); List<String> args = new ArrayList<>(List.of("report","--result",result.toString(),"--format","json","--coverage-dir",dir.toString()));
        if (catalog != null) args.addAll(List.of("--coverage-catalog",catalog.toString()));
        if (selection != null) args.addAll(List.of("--coverage-selection",selection.toString()));
        var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream();
        int exit = new RegressionCommand(new ProductRepoService(),new ReleasePackageService()).execute(args.toArray(String[]::new),new PrintStream(out),new PrintStream(err));
        return new Run(exit, JSON.readTree(out.toByteArray()),err.toString(),dir);
    }
    private JsonNode artifact(Run r) throws Exception { assertThat(r.dir.resolve("coverage.json")).as(r.stdout.toString()+r.err).exists(); return JSON.readTree(r.dir.resolve("coverage.json").toFile()); }
    private Path selection(Path... results) throws Exception {
        ObjectNode s=JSON.createObjectNode().put("schema_version","coverage-selection.v1").put("selection_id","selection").put("policy","latest_compatible_per_case").put("as_of","2026-06-30T00:01:00Z").put("max_age_seconds",120);
        s.set("expected_scope",catalog("automatable").path("scope").deepCopy());
        var list=s.putArray("results"); for(Path p:results) list.addObject().put("path",p.toString()).put("sha256",sha(p));
        return write("selection-"+UUID.randomUUID()+".json",s);
    }
    static String sha(Path p) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))); }
    @Test void unknownInventoryPublishesPairWithActualLinksWithoutFabricatedCoverage() throws Exception {
        Path p=fixture("run","passed",1); String before=sha(p); Run r=report(p,null,null,"report");
        assertThat(r.exit).as(r.err).isZero(); JsonNode d=artifact(r);
        assertThat(d.path("inventory").path("state").asText()).isEqualTo("UNKNOWN");
        assertThat(d.at("/metrics/acceptance_criteria/denominator").isNull()).isTrue();
        assertThat(d.at("/metrics/requirements/satisfied_percent").isNull()).isTrue();
        assertThat(d.path("evidence_links").toString()).contains(p.toString(),before);
        assertThat(Files.readString(r.dir.resolve("coverage.md"))).contains("UNKNOWN",p.toString(),before);
        assertThat(r.stdout.path("coverage_percent_metric").asText()).isEqualTo("test_pass_rate_percent");
        assertThat(r.stdout.path("coverage_json_sha256").asText()).isEqualTo(sha(r.dir.resolve("coverage.json")));
        assertThat(r.stdout.path("coverage_md_sha256").asText()).isEqualTo(sha(r.dir.resolve("coverage.md")));
        assertThat(sha(p)).isEqualTo(before);
    }
    @Test void knownEmptyIsZeroWithNullPercentAndDuplicateIdsFailClosed() throws Exception {
        Path p=fixture("run","passed",1); ObjectNode c=catalog("automatable"); c.putArray("requirements"); c.putArray("acceptance_criteria");
        JsonNode d=artifact(report(p,write("empty.json",c),null,"empty"));
        assertThat(d.at("/inventory/state").asText()).isEqualTo("KNOWN_EMPTY");
        assertThat(d.at("/metrics/requirements/denominator").asInt()).isZero();
        assertThat(d.at("/metrics/requirements/satisfied_percent").isNull()).isTrue();
        c=catalog("automatable"); ((com.fasterxml.jackson.databind.node.ArrayNode)c.path("acceptance_criteria")).add(c.path("acceptance_criteria").get(0).deepCopy());
        Run r=report(p,write("duplicate.json",c),null,"duplicate"); assertThat(r.exit).isEqualTo(1); assertThat(r.stdout.toString()+r.err).contains("duplicate");
    }
    @Test void partialAutomationNeverSatisfiesFullAcOrRequirement() throws Exception {
        Run r=report(fixture("run","passed",1),write("catalog.json",catalog("partial")),null,"report"); JsonNode d=artifact(r);
        assertThat(d.at("/metrics/acceptance_criteria/satisfied").asInt()).isZero();
        assertThat(d.path("entities").toString()).contains("partial_remainder_not_evidenced","UNKNOWN","mapped_test_satisfaction_state","SATISFIED");
        assertThat(d.at("/metrics/requirements/satisfied").asInt()).isZero();
    }
    @Test void multipleTestsDoNotDuplicateAcAndUnmappedAcRemainsVisible() throws Exception {
        ObjectNode c=catalog("automatable"); ((com.fasterxml.jackson.databind.node.ArrayNode)c.at("/requirements/0/ac_ids")).add("AC-2");
        ((com.fasterxml.jackson.databind.node.ArrayNode)c.path("acceptance_criteria")).addObject().put("id","AC-2").put("requirement_id","REQ-1").put("classification","automatable");
        JsonNode d=artifact(report(fixture("run","passed",1),write("catalog.json",c),null,"report"));
        assertThat(d.at("/metrics/acceptance_criteria/denominator").asInt()).isEqualTo(2);
        assertThat(d.at("/metrics/requirements/mapped").asInt()).isEqualTo(1);
        assertThat(d.at("/metrics/acceptance_criteria/satisfied").asInt()).isEqualTo(1);
        assertThat(d.at("/metrics/requirements/satisfied").asInt()).isZero();
        assertThat(d.path("entities").toString()).contains("AC-2","UNMAPPED");
    }
    @Test void approvedExclusionOnlyRemovesApprovedAc() throws Exception {
        ObjectNode c=catalog("manual_only"); Path p=fixture("run","passed",1);
        JsonNode d=artifact(report(p,write("unapproved.json",c),null,"unapproved"));
        assertThat(d.at("/metrics/acceptance_criteria/denominator").asInt()).isEqualTo(1);
        ((ObjectNode)c.at("/acceptance_criteria/0")).set("exclusion",JSON.valueToTree(Map.of("reason","manual review","review_ref","review","approved_by","owner","approved_at","2026-06-29T00:00:00Z")));
        d=artifact(report(p,write("approved.json",c),null,"approved"));
        assertThat(d.at("/metrics/acceptance_criteria/denominator").asInt()).isZero(); assertThat(d.path("entities").toString()).contains("EXCLUDED","manual review");
    }
    @Test void newerFailedCaseSupersedesOldPassAndNegativeDetectionStaysSeparate() throws Exception {
        Path old=fixture("old","passed",1), newer=fixture("new","failed",2);
        JsonNode d=artifact(report(newer,write("catalog.json",catalog("automatable")),selection(old,newer),"report"));
        assertThat(d.at("/metrics/acceptance_criteria/satisfied").asInt()).isZero();
        assertThat(d.at("/metrics/acceptance_criteria/executed").asInt()).isEqualTo(1);
        assertThat(d.at("/cases/0/canonical_outcome").asText()).isEqualTo("FAIL");
        assertThat(d.at("/cases/0/expected_negative/detection").asText()).isEqualTo("UNKNOWN");
    }
    @Test void newestMissingEvidenceBlocksFallbackToOldPass() throws Exception {
        Path old=fixture("old","passed",1), newer=fixture("new","passed",2); Files.delete(newer.getParent().resolve("evidence/provider_evidence.json"));
        Run r=report(newer,write("catalog.json",catalog("automatable")),selection(old,newer),"report");
        assertThat(r.exit).isEqualTo(1); JsonNode d=artifact(r);
        assertThat(d.at("/cases/0/source_result").asText()).isEqualTo(newer.toString());
        assertThat(d.at("/metrics/acceptance_criteria/satisfied").asInt()).isZero();
    }
    @Test void newestTamperedPinnedResultBlocksFallbackToOldPass() throws Exception {
        Path old=fixture("old","passed",1), newer=fixture("new","passed",2); Path s=selection(old,newer); Files.writeString(newer,Files.readString(newer)+"\n");
        Run r=report(newer,write("catalog.json",catalog("automatable")),s,"report"); assertThat(r.exit).isEqualTo(1); JsonNode d=artifact(r);
        assertThat(d.at("/cases/0/source_result").asText()).isEqualTo(newer.toString());
        assertThat(d.at("/cases/0/evidence_state").asText()).isEqualTo("TAMPERED");
        assertThat(d.at("/metrics/acceptance_criteria/satisfied").asInt()).isZero();
    }
    @Test void staleEvidenceCannotSatisfyAndMismatchedCatalogFails() throws Exception {
        Path p=fixture("run","passed",1); Path sel=selection(p); ObjectNode s=(ObjectNode)JSON.readTree(sel.toFile()); s.put("max_age_seconds",1); JSON.writeValue(sel.toFile(),s);
        JsonNode d=artifact(report(p,write("catalog.json",catalog("automatable")),sel,"stale"));
        assertThat(d.at("/cases/0/evidence_state").asText()).isEqualTo("STALE"); assertThat(d.at("/metrics/acceptance_criteria/satisfied").asInt()).isZero();
        ObjectNode c=catalog("automatable"); ((ObjectNode)c.path("scope")).put("project_ref","other");
        assertThat(report(p,write("wrong.json",c),null,"wrong").exit).isEqualTo(1);
    }
    @Test void jacocoAggregateNeedsSeparateScopeProvenance() throws Exception {
        Path xml=tmp.resolve("jacoco.xml"); Files.writeString(xml,"<report name='unit'><counter type='LINE' missed='2' covered='8'/><counter type='BRANCH' missed='3' covered='1'/></report>");
        ObjectNode c=catalog("automatable"); c.putArray("code_coverage_artifacts").addObject().put("domain","framework_unit").put("path",xml.toString()).put("sha256",sha(xml)).put("repository_revision","revision");
        JsonNode d=artifact(report(fixture("run","passed",1),write("catalog.json",c),null,"report"));
        assertThat(d.at("/code_coverage/0/line/percent").asDouble()).isEqualTo(80);
        assertThat(d.at("/code_coverage/0/branch/percent").asDouble()).isEqualTo(25);
        assertThat(d.at("/code_coverage/0/scope/kind").asText()).isEqualTo("artifact_aggregate");
        assertThat(d.at("/code_coverage/0/scope/completeness").asText()).isEqualTo("UNKNOWN");
    }
    @Test void outputInsideCanonicalTreeAndExistingPairAreRejected() throws Exception {
        Path p=fixture("run","passed",1); Run r=report(p,null,null,"run/derived"); assertThat(r.exit).isEqualTo(1); assertThat(r.dir.resolve("coverage.json")).doesNotExist();
        r=report(p,null,null,"report"); assertThat(r.exit).isZero(); String digest=sha(r.dir.resolve("coverage.json"));
        r=report(p,null,null,"report"); assertThat(r.exit).isEqualTo(1); assertThat(sha(r.dir.resolve("coverage.json"))).isEqualTo(digest);
    }
    @Test void duplicateJsonKeysAndSymlinkInputsFail() throws Exception {
        Path p=fixture("run","passed",1); Path c=tmp.resolve("bad.json"); Files.writeString(c,"{\"schema_version\":\"coverage-catalog.v1\",\"schema_version\":\"bad\"}");
        assertThat(report(p,c,null,"bad").exit).isEqualTo(1);
        Path link=tmp.resolve("linked-result.json"); Files.createSymbolicLink(link,p);
        assertThat(report(link,null,null,"linked").exit).isEqualTo(1);
    }
    @Test void unknownRequirementInventoryCanMeasureIndependentInstrumentedArtifacts() throws Exception {
        Path p=fixture("run","passed",1),sel=selection(p),xml=tmp.resolve("jacoco.xml");
        Files.writeString(xml,"<report name='unit'><counter type='LINE' missed='2' covered='8'/><counter type='BRANCH' missed='3' covered='1'/></report>");
        ObjectNode selection=(ObjectNode)JSON.readTree(sel.toFile());
        selection.putArray("code_coverage_artifacts").addObject().put("domain","framework_unit").put("path",xml.toString()).put("sha256",sha(xml)).put("repository_revision","revision");JSON.writeValue(sel.toFile(),selection);
        JsonNode d=artifact(report(p,null,sel,"report"));
        assertThat(d.at("/inventory/state").asText()).isEqualTo("UNKNOWN");
        assertThat(d.at("/metrics/requirements/denominator").isNull()).isTrue();
        assertThat(d.at("/code_coverage/0/state").asText()).isEqualTo("MEASURED");
        assertThat(d.at("/code_coverage/0/line/percent").asDouble()).isEqualTo(80);
        ObjectNode c=catalog("automatable"); c.set("code_coverage_artifacts",selection.path("code_coverage_artifacts"));
        assertThat(report(p,write("catalog.json",c),sel,"both").exit).isEqualTo(1);
    }
    @Test void actualJacocoDoctypeIsAcceptedWithoutExternalDtdResolution() throws Exception {
        Path xml=tmp.resolve("jacoco.xml");
        Files.writeString(xml,"<?xml version='1.0'?><!DOCTYPE report PUBLIC '-//JACOCO//DTD Report 1.1//EN' 'report.dtd'><report name='unit'><counter type='LINE' missed='2' covered='8'/><counter type='BRANCH' missed='3' covered='1'/></report>");
        ObjectNode c=catalog("automatable");c.putArray("code_coverage_artifacts").addObject().put("domain","framework_unit").put("path",xml.toString()).put("sha256",sha(xml)).put("repository_revision","revision");
        Run r=report(fixture("run","passed",1),write("catalog.json",c),null,"report");
        assertThat(r.exit).as(r.err+r.stdout).isZero();assertThat(artifact(r).at("/code_coverage/0/state").asText()).isEqualTo("MEASURED");
    }
    @Test void selectedClassScopeRequiresMatchingPinnedProvenance() throws Exception {
        Path xml=tmp.resolve("jacoco.xml"),p=fixture("run","passed",1);
        Files.writeString(xml,"<report name='unit'><package name='p'><class name='p/C'/></package><counter type='LINE' missed='2' covered='8'/><counter type='BRANCH' missed='3' covered='1'/></report>");
        ObjectNode provenance=JSON.createObjectNode().put("kind","selected_classes").put("repository_revision","revision").put("exclusions_reason","reviewed subset");provenance.putArray("included_classes").add("p/C");provenance.putArray("excluded_classes").add("p/D");
        Path pin=write("scope.json",provenance);
        ObjectNode c=catalog("automatable"),entry=c.putArray("code_coverage_artifacts").addObject().put("domain","framework_unit").put("path",xml.toString()).put("sha256",sha(xml)).put("repository_revision","revision");
        ObjectNode scope=provenance.deepCopy();scope.remove("repository_revision");scope.put("provenance_ref",pin.toString()).put("provenance_sha256",sha(pin));entry.set("scope",scope);
        JsonNode d=artifact(report(p,write("catalog.json",c),null,"good"));assertThat(d.at("/code_coverage/0/scope/kind").asText()).isEqualTo("selected_classes");assertThat(d.at("/code_coverage/0/scope/completeness").asText()).isEqualTo("PINNED");
        scope.put("provenance_sha256","b".repeat(64));Run bad=report(p,write("bad-catalog.json",c),null,"bad");assertThat(bad.exit).isEqualTo(1);
    }
    @Test void blockedCaseDoesNotCountExecutedAndCannotSatisfy() throws Exception {
        JsonNode d=artifact(report(fixture("run","blocked",1),write("catalog.json",catalog("automatable")),null,"report"));
        assertThat(d.at("/cases/0/canonical_outcome").asText()).isEqualTo("BLOCKED");
        assertThat(d.at("/metrics/acceptance_criteria/executed").asInt()).isZero();
        assertThat(d.at("/metrics/acceptance_criteria/satisfied").asInt()).isZero();
        assertThat(d.path("breakdowns").toString()).contains("native_swarm","independent_domain_evidence_missing");
    }
    @Test void secondArtifactObstructionCannotLeaveSuccessJson() throws Exception {
        Path p=fixture("run","passed",1),dir=tmp.resolve("report");Files.createDirectories(dir.resolve("coverage.md"));
        Run r=report(p,null,null,"report");assertThat(r.exit).isEqualTo(1);assertThat(dir.resolve("coverage.json")).doesNotExist();assertThat(r.stdout.has("coverage_json")).isFalse();
    }

    @Test void explicitCoverageCannotDowngradeSiblingLegacyEvidenceToCanonical() throws Exception {
        Path legacy=Path.of("samples/90-compatibility/legacy-v0.2/10-contract-baseline/mixed_wiremock_jdbc_nats/result/sample_result.json").toAbsolutePath();
        Run r=report(legacy,null,null,"explicit-legacy");
        assertThat(r.exit).isEqualTo(1);
        assertThat(r.stdout.path("coverage_report_status").asText()).isEqualTo("INVALID");
        assertThat(artifact(r).path("gaps").toString()).contains("reference escaped canonical tree");
    }

    @Test void catalogDomainCannotPromoteProductEvidenceToNativeSwarmOrModuleCoverage() throws Exception {
        Path p=fixture("domain-run","passed",1);
        for(String domain:List.of("native_swarm","module","framework_unit","native_fixture")) {
            ObjectNode c=catalog("automatable");((ObjectNode)c.path("scope")).put("domain",domain);
            Run r=report(p,write("catalog-"+domain+".json",c),null,"domain-"+domain);
            assertThat(r.exit).as("catalog domain "+domain+" must not reuse product evidence").isEqualTo(1);
            assertThat(r.stdout.toString()+r.err).contains("scope");
        }
    }
    private Path payloadFixture(String name,int characters) throws Exception {
        Path p=fixture(name,"passed",1);ObjectNode d=(ObjectNode)JSON.readTree(p.toFile());String payload="q".repeat(characters);
        ((ObjectNode)d.path("steps").get(0)).putObject("outputs").put("response.message",payload);
        d.set("step_results",d.path("steps").deepCopy());
        ((ObjectNode)d.path("provider_results").get(0).path("resolved_operation_result")).putObject("outputs").put("response.message",payload);
        JSON.writeValue(p.toFile(),d);
        assertThat(new com.specdriven.regression.contract.ContractBaselineService().report(p).valid()).isTrue();
        assertThat(new com.specdriven.regression.evidence.EvidenceHardeningService().validateResult(p).valid()).isTrue();
        return p;
    }
    @Test void legal64KiBCanonicalPayloadRemainsReportable() throws Exception {
        Path p=payloadFixture("payload64k",65536);Run r=report(p,null,null,"payload64k-report");
        assertThat(r.exit).as(r.stdout.toString()+r.err).isZero();
        assertThat(artifact(r).at("/cases/0/canonical_outcome").asText()).isEqualTo("PASS");
    }
    @Test void duplicatedLegalPayloadOverTwoMiBRemainsReportable() throws Exception {
        Path p=payloadFixture("payload768k",768*1024);assertThat(Files.size(p)).isGreaterThan(2*1024*1024).isLessThan(4*1024*1024);
        Run r=report(p,null,null,"payload768k-report");assertThat(r.exit).as(r.stdout.toString()+r.err).isZero();
        assertThat(artifact(r).path("report_status").asText()).isEqualTo("INCOMPLETE");
    }
    @Test void largePayloadAllowanceDoesNotRelaxCoverageMetadataBounds() throws Exception {
        Path p=fixture("metadata-run","passed",1);ObjectNode c=catalog("automatable");((ObjectNode)c.path("authority")).put("source_ref","x".repeat(5000));
        assertThat(report(p,write("oversized-metadata.json",c),null,"oversized-metadata").exit).isEqualTo(1);
    }
    @Test void repositoryKeyOrderCannotSplitOneCaseAndHideNewestFailure() throws Exception {
        Path old=fixture("ordered-old","passed",1),newer=fixture("reordered-new","failed",2);
        ObjectNode a=(ObjectNode)JSON.readTree(old.toFile()),b=(ObjectNode)JSON.readTree(newer.toFile());
        a.putObject("repository_revisions").put("framework","revision").put("grpc-go","go-revision");
        b.putObject("repository_revisions").put("grpc-go","go-revision").put("framework","revision");
        JSON.writeValue(old.toFile(),a);JSON.writeValue(newer.toFile(),b);
        ObjectNode c=catalog("automatable");((ObjectNode)c.path("scope")).set("repository_revisions",a.path("repository_revisions"));
        Path selected=selection(old,newer);ObjectNode sel=(ObjectNode)JSON.readTree(selected.toFile());((ObjectNode)sel.path("expected_scope")).set("repository_revisions",a.path("repository_revisions"));JSON.writeValue(selected.toFile(),sel);
        Run r=report(newer,write("ordered-catalog.json",c),selected,"ordered-report");assertThat(r.exit).as(r.stdout.toString()+r.err).isZero();
        JsonNode d=artifact(r);assertThat(d.path("cases")).hasSize(1);assertThat(d.at("/cases/0/canonical_outcome").asText()).isEqualTo("FAIL");assertThat(d.at("/metrics/acceptance_criteria/satisfied").asInt()).isZero();
    }
    @Test void frameworkAndModuleInstrumentationCannotBorrowAnotherRepositoryRevision() throws Exception {
        Path p=fixture("code-repo-run","passed",1),xml=tmp.resolve("repository-jacoco.xml");
        Files.writeString(xml,"<report name='unit'><counter type='LINE' missed='2' covered='8'/><counter type='BRANCH' missed='3' covered='1'/></report>");
        ObjectNode d=(ObjectNode)JSON.readTree(p.toFile());((ObjectNode)d.path("repository_revisions")).put("grpc-go","go-revision");JSON.writeValue(p.toFile(),d);
        for(String domain:List.of("framework_unit","module")) {
            ObjectNode c=catalog("automatable");((ObjectNode)c.path("scope")).set("repository_revisions",d.path("repository_revisions"));
            ObjectNode entry=c.putArray("code_coverage_artifacts").addObject().put("domain",domain).put("path",xml.toString()).put("sha256",sha(xml)).put("repository_revision","go-revision");
            JsonNode wrong=artifact(report(p,write("wrong-repo-"+domain+".json",c),null,"wrong-repo-"+domain));
            assertThat(wrong.at("/code_coverage/0/state").asText()).isEqualTo("STALE");assertThat(wrong.at("/code_coverage/0/line").isNull()).isTrue();
            entry.put("repository_revision","revision");JsonNode right=artifact(report(p,write("right-repo-"+domain+".json",c),null,"right-repo-"+domain));
            assertThat(right.at("/code_coverage/0/state").asText()).isEqualTo("MEASURED");assertThat(right.at("/code_coverage/0/repository").asText()).isEqualTo("framework");
        }
    }

    @Test void newCanonicalMarkersCannotTakeLegacyDefaultPath() throws Exception {
        Path p=fixture("marker-run","passed",1);ObjectNode d=(ObjectNode)JSON.readTree(p.toFile());d.put("dsl_version","v0.3");d.remove("result_contract_version");JSON.writeValue(p.toFile(),d);
        var out=new ByteArrayOutputStream();var err=new ByteArrayOutputStream();
        int exit=new RegressionCommand(new ProductRepoService(),new ReleasePackageService()).execute(new String[]{"report","--result",p.toString(),"--format","json"},new PrintStream(out),new PrintStream(err));
        assertThat(exit).as(out+err.toString()).isEqualTo(1);
        assertThat(report(p,null,null,"marker-explicit").exit).isEqualTo(1);
        d.put("result_contract_version","v0.2");JSON.writeValue(p.toFile(),d);
        out.reset();err.reset();exit=new RegressionCommand(new ProductRepoService(),new ReleasePackageService()).execute(new String[]{"report","--result",p.toString(),"--format","json"},new PrintStream(out),new PrintStream(err));assertThat(exit).isEqualTo(1);
    }
    @Test void missingFrameworkKeyAndUnboundCodeDomainsRemainStale() throws Exception {
        Path p=fixture("unbound-code-run","passed",1),xml=tmp.resolve("unbound-jacoco.xml");Files.writeString(xml,"<report name='unit'><counter type='LINE' missed='2' covered='8'/><counter type='BRANCH' missed='3' covered='1'/></report>");
        ObjectNode d=(ObjectNode)JSON.readTree(p.toFile());d.putObject("repository_revisions").put("grpc-go","revision");JSON.writeValue(p.toFile(),d);
        for(String domain:List.of("framework_unit","module","native_swarm","native_fixture")) {
            ObjectNode c=catalog("automatable");((ObjectNode)c.path("scope")).set("repository_revisions",d.path("repository_revisions"));c.putArray("code_coverage_artifacts").addObject().put("domain",domain).put("path",xml.toString()).put("sha256",sha(xml)).put("repository_revision","revision");
            JsonNode result=artifact(report(p,write("unbound-"+domain+".json",c),null,"unbound-"+domain));
            assertThat(result.at("/code_coverage/0/state").asText()).isEqualTo("STALE");assertThat(result.at("/code_coverage/0/line").isNull()).isTrue();
        }
    }

}
