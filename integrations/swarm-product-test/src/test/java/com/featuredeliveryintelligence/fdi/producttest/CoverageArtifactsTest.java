package com.featuredeliveryintelligence.fdi.producttest;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract evidence for module consumption, not a native-agent execution test. */
class CoverageArtifactsTest {
    @TempDir Path root;
    Path result,report,coverage,markdown;
    ObjectNode document;
    private ProductTestRequest request() {
        return new ProductTestRequest("product-test-request.v1","product-test","1","mission","request","revision","workspace","project",Map.of("framework","revision"),Map.of(),"component","regression","local","not_used",root.toString(),root.resolve("suite.yaml").toString(),"SUITE","local",root.resolve("handoff.json").toString(),"a".repeat(64));
    }
    private void fixture() throws Exception {
        root=root.toRealPath(); Path canonical=Files.createDirectories(root.resolve("run")),derived=Files.createDirectories(root.resolve("coverage"));
        result=canonical.resolve("result.json");report=root.resolve("framework-report.json");coverage=derived.resolve("coverage.json");markdown=derived.resolve("coverage.md");
        ObjectNode canonicalDoc=Json.MAPPER.createObjectNode().put("suite_id","SUITE").put("profile","local").put("run_id","RUN-1").put("batch_id","BATCH-1").put("status","failed");
        canonicalDoc.putArray("test_results").addObject().put("test_case_id","TC-1").put("status","failed");Json.MAPPER.writeValue(result.toFile(),canonicalDoc);
        document=Json.MAPPER.createObjectNode().put("schema_version","coverage_report.v1").put("report_status","INCOMPLETE").put("generation_status","GENERATED").put("publication_status","PUBLISHED").put("evidence_classification","framework_verification_only").put("downstream_release_evidence",false).put("canonical_outcome","FAIL");
        ObjectNode subject=document.putObject("subject").put("suite_id","SUITE").put("profile","local").put("run_id","RUN-1").put("batch_id","BATCH-1").put("project_ref","project").put("workspace_ref","workspace");subject.set("repository_revisions",Json.MAPPER.valueToTree(request().repositoryRevisions()));
        document.putObject("inventory").put("state","UNKNOWN");ObjectNode metrics=document.putObject("metrics");for(String k:List.of("requirements","acceptance_criteria"))metrics.putObject(k).putNull("denominator").putNull("satisfied_percent");
        document.putArray("sources").addObject().put("path",result.toString()).put("sha256",LocalFiles.sha256(result)).put("selection_state","SELECTED");
        document.putArray("evidence_links").addObject().put("path",result.toString()).put("sha256",LocalFiles.sha256(result));
        document.putArray("cases").addObject().put("test_case_id","TC-1").put("source_result",result.toString()).put("canonical_outcome","FAIL");
        Files.writeString(markdown,"# Coverage\n\nUNKNOWN inventory; canonical FAIL.\n");save();
    }
    private void save() throws Exception {
        Json.MAPPER.writeValue(coverage.toFile(),document);
        var summary=Json.MAPPER.createObjectNode().put("coverage_json",coverage.toString()).put("coverage_md",markdown.toString()).put("coverage_report_status",document.path("report_status").asText()).put("coverage_json_sha256",LocalFiles.sha256(coverage)).put("coverage_md_sha256",LocalFiles.sha256(markdown));
        Json.MAPPER.writeValue(report.toFile(),summary);
    }
    @SuppressWarnings("unchecked") private Map<String,ProductTestReceipt.Artifact> verify() throws Exception {
        Class<?> type=assertDoesNotThrow(() -> Class.forName("com.featuredeliveryintelligence.fdi.producttest.CoverageArtifacts"),"Coverage artifact validation must be implemented");
        var method=type.getDeclaredMethod("verify",Path.class,Path.class,Path.class,ProductTestRequest.class);method.setAccessible(true);
        try{return (Map<String,ProductTestReceipt.Artifact>)method.invoke(null,coverage.getParent(),report,result,request());}
        catch(InvocationTargetException e){if(e.getCause() instanceof Exception ex)throw ex;throw e;}
    }
    @Test void incompleteUnknownCoverageIsPinnedWithoutRewritingFailedVerdict() throws Exception {
        fixture();String before=LocalFiles.sha256(result);var artifacts=verify();
        assertThat(artifacts.keySet()).containsExactlyInAnyOrder("coverage_json","coverage_md");assertThat(artifacts.get("coverage_md").sha256()).isEqualTo(LocalFiles.sha256(markdown));assertThat(LocalFiles.sha256(result)).isEqualTo(before);
    }
    @Test void missingPairMemberPreventsCompletion() throws Exception {fixture();Files.delete(markdown);assertThatThrownBy(this::verify).isInstanceOf(Exception.class).hasMessageContaining("regular file");}
    @Test void tamperedMarkdownFailsTrustedReportDigest() throws Exception {fixture();Files.writeString(markdown,"changed");assertThatThrownBy(this::verify).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("digest");}
    @Test void recomputedDigestCannotHideIdentityMismatch() throws Exception {fixture();((ObjectNode)document.path("subject")).put("run_id","OTHER");save();assertThatThrownBy(this::verify).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("identity");}
    @Test void externalReferenceAndTamperedCanonicalSourceAreRejected() throws Exception {
        fixture();Path outside=root.resolve("outside.txt");Files.writeString(outside,"unapproved");document.withArray("evidence_links").addObject().put("path",outside.toString()).put("sha256",LocalFiles.sha256(outside));save();assertThatThrownBy(this::verify).isInstanceOf(Exception.class).hasMessageContaining("directory");
        fixture();((ObjectNode)document.path("sources").get(0)).put("sha256","b".repeat(64));save();assertThatThrownBy(this::verify).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("source");
    }
    @Test void invalidCoverageOrInventedUnknownDenominatorIsRejected() throws Exception {
        fixture();document.put("report_status","INVALID");save();assertThatThrownBy(this::verify).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("status");
        fixture();((ObjectNode)document.at("/metrics/requirements")).put("denominator",100);save();assertThatThrownBy(this::verify).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("UNKNOWN");
    }
}
