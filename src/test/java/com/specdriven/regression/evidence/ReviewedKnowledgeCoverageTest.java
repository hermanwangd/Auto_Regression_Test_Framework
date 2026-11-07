package com.specdriven.regression.evidence;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.specdriven.regression.report.CoverageReportService;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public frozen metadata contracts only, including equal bare case IDs from distinct repositories. */
class ReviewedKnowledgeCoverageTest {
    @TempDir Path root;
    static final String OTHER="fixture:other-product";
    private ReviewedGrpcProofIdentityTest fixture(String directory,String repo)throws Exception {
        var f=new ReviewedGrpcProofIdentityTest();f.root=Files.createDirectories(root.resolve(directory));f.validHistoricalFixture();KnowledgeV2Fixture.augment(f);
        if(!repo.equals(KnowledgeV2Fixture.REPO))f.provenance=(ObjectNode)KnowledgeV2Fixture.JSON.readTree(f.provenance.toString().replace(KnowledgeV2Fixture.REPO,repo));
        var revisions=KnowledgeV2Fixture.JSON.valueToTree(Map.of(KnowledgeV2Fixture.REPO,KnowledgeV2Fixture.X,OTHER,KnowledgeV2Fixture.X,KnowledgeV2Fixture.PK,KnowledgeV2Fixture.P));
        f.provenance.set("repository_revisions",revisions.deepCopy());((ObjectNode)f.provenance.path("knowledge_context_scope")).set("repository_revisions",revisions.deepCopy());f.save();return f;
    }
    private JsonNode project(ReviewedGrpcProofIdentityTest f,String out,Path catalog,Path selection){return new CoverageReportService().projectCanonical(f.resultFile,root.resolve(out),catalog,selection).document();}
    @Test void projectsPinnedQualifiedKnowledgeTraceWithoutChangingHistoricalVerdict()throws Exception {
        var f=fixture("run",KnowledgeV2Fixture.REPO);var report=project(f,"coverage",null,null);var row=report.path("knowledge_trace").get(0);
        assertThat(row).isNotNull();assertThat(row.path("qualified_source_ref").asText()).isEqualTo(KnowledgeV2Fixture.SOURCE);assertThat(row.path("qualified_case_ref").asText()).isEqualTo(KnowledgeV2Fixture.CASE);
        assertThat(row.path("run_id").asText()).isEqualTo("RUN-1");assertThat(row.path("batch_id").asText()).isEqualTo("BATCH-1");assertThat(row.path("canonical_outcome").asText()).isEqualTo("PASS");
        assertThat(row.path("source_result_sha256").asText()).isEqualTo(sha(f.resultFile));assertThat(row.path("source_result").asText()).isEqualTo(f.resultFile.toString());
        assertThat(row.path("historical_context_state").asText()).isEqualTo("VALID_AT_INVOCATION");assertThat(row.path("new_invocation_eligibility").asText()).isEqualTo("NOT_EVALUATED_REQUIRES_FRESH_REVIEW");
        assertThat(row.path("source_lock").path("composition_root_version").asText()).isEqualTo(KnowledgeV2Fixture.X);assertThat(row.path("source_lock").path("artifact_source_testrepo_version").asText()).isEqualTo(KnowledgeV2Fixture.A);
        assertThat(report.path("cases").get(0).path("qualified_case_ref").asText()).isEqualTo(KnowledgeV2Fixture.CASE);
        assertThat(report.path("inventory").path("state").asText()).isEqualTo("UNKNOWN");assertThat(report.at("/metrics/requirements/denominator").isNull()).isTrue();
        assertThat(row.toString()).doesNotContain("proposal_body","raw_context","source_text","access_token");
    }
    @Test void equalBareCaseIdsFromDifferentRepositoriesRemainDistinct()throws Exception {
        var a=fixture("a",KnowledgeV2Fixture.REPO);var b=fixture("b",OTHER);b.result.put("end_time","2000-01-01T00:00:03Z");b.save();
        ObjectNode selection=KnowledgeV2Fixture.obj("schema_version","coverage-selection.v1","selection_id","fixture:selection","policy","latest_compatible_per_case","as_of","2000-01-01T00:00:10Z","max_age_seconds",60,"expected_scope",Map.of("domain","product","project_ref","fixture-project","workspace_ref","fixture-workspace","repository_revisions",KnowledgeV2Fixture.JSON.convertValue(a.provenance.path("repository_revisions"),Map.class)));
        selection.putArray("results").add(KnowledgeV2Fixture.obj("path",a.resultFile.toString(),"sha256",sha(a.resultFile))).add(KnowledgeV2Fixture.obj("path",b.resultFile.toString(),"sha256",sha(b.resultFile)));Path path=root.resolve("selection.json");KnowledgeV2Fixture.JSON.writeValue(path.toFile(),selection);
        var report=project(b,"coverage",null,path);assertThat(report.path("cases").size()).isEqualTo(2);Set<String>ids=new HashSet<>();report.path("cases").forEach(c->ids.add(c.path("qualified_case_ref").asText()));assertThat(ids).containsExactlyInAnyOrder(KnowledgeV2Fixture.CASE,KnowledgeV2Fixture.CASE.replace(KnowledgeV2Fixture.REPO,OTHER));
    }
    @Test void nakedCaseIdCannotCreditQualifiedV2CatalogMapping()throws Exception {
        var f=fixture("run",KnowledgeV2Fixture.REPO);ObjectNode c=KnowledgeV2Fixture.obj("schema_version","coverage-catalog.v1","catalog_revision","fixture:catalog","scope",Map.of("domain","product","project_ref","fixture-project","workspace_ref","fixture-workspace","repository_revisions",KnowledgeV2Fixture.JSON.convertValue(f.provenance.path("repository_revisions"),Map.class)),"authority",Map.of("kind","reviewed_inventory","source_ref","fixture:inventory","source_sha256","a".repeat(64),"review_ref","fixture:review","approved_by","fixture:owner","approved_at","1999-12-31T23:59:59Z"));
        c.putArray("requirements").add(KnowledgeV2Fixture.obj("id","REQ","ac_ids",List.of("AC")));var ac=KnowledgeV2Fixture.obj("id","AC","requirement_id","REQ","classification","automatable","review_ref","fixture:review","test_case_ids",List.of("TC-1"));c.putArray("acceptance_criteria").add(ac);Path path=root.resolve("catalog.json");KnowledgeV2Fixture.JSON.writeValue(path.toFile(),c);
        var bare=project(f,"bare",path,null);assertThat(bare.at("/metrics/acceptance_criteria/mapped").asInt()).isZero();ac.putArray("test_case_ids").add(KnowledgeV2Fixture.CASE);KnowledgeV2Fixture.JSON.writeValue(path.toFile(),c);var qualified=project(f,"qualified",path,null);assertThat(qualified.at("/metrics/acceptance_criteria/mapped").asInt()).isEqualTo(1);
    }
    private static String sha(Path p)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));}
}
