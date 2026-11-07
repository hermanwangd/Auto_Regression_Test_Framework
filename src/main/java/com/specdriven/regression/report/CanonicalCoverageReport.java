package com.specdriven.regression.report;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.specdriven.regression.contract.ContractBaselineService;
import com.specdriven.regression.contract.ResultDocumentReader;
import com.specdriven.regression.evidence.EvidenceHardeningService;
import com.specdriven.regression.producttest.KnowledgeSelectionProof;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** One projection of validated framework evidence. Does not execute tests or infer an inventory. */
final class CanonicalCoverageReport {
    private static final int INPUT_LIMIT = 2 * 1024 * 1024, OUTPUT_LIMIT = 8 * 1024 * 1024;
    private static final ObjectMapper JSON = mapper(4096, 80);
    private static ObjectMapper mapper(int stringLimit, int nestingLimit) {
        return new ObjectMapper(JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(nestingLimit)
                        .maxStringLength(stringLimit).build()).build())
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    private final ArrayNode gaps = JSON.createArrayNode(), links = JSON.createArrayNode();
    private final Map<Path, String> snapshots = new LinkedHashMap<>();
    private boolean invalid;
    private record Metric(Integer denominator, Integer mapped, Integer executed, Integer satisfied,
                          Double mapped_percent, Double executed_percent, Double satisfied_percent) {}
    private record Candidate(Path path, JsonNode result, JsonNode provenance, ObjectNode subject,
                             Instant start, Instant end, String evidence, boolean compatible, String digest, boolean knowledgeValid) {}
    private record Chosen(Candidate source, JsonNode test) {}
    private record CaseIdentity(String project, String workspace, JsonNode repositoryRevisions,
                                String suite, String profile, String testCase, String qualifiedCaseRef) {}

    CanonicalCoverageReportResult project(Path result, Path output, Path catalogPath, Path selectionPath) {
        try { return generate(result, output, catalogPath, selectionPath); }
        catch (IOException e) { throw new UncheckedIOException("Coverage generation failed: " + e.getMessage(), e); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Coverage generation failed: " + e.getMessage(), e); }
        catch (Exception e) { throw new IllegalArgumentException("Coverage generation failed: " + e.getMessage(), e); }
    }

    private CanonicalCoverageReportResult generate(Path primaryPath, Path output, Path catalogPath, Path selectionPath) throws Exception {
        primaryPath = regular(primaryPath); output = safeOutput(output);
        JsonNode selection = selectionPath == null ? null : read(selectionPath, INPUT_LIMIT);
        JsonNode catalog = catalogPath == null ? null : read(catalogPath, INPUT_LIMIT);
        if (selection != null) {
            require("coverage-selection.v1".equals(text(selection,"schema_version")), "unsupported selection version");
            require("latest_compatible_per_case".equals(text(selection,"policy")), "unsupported selection policy");
            required(selection,"selection_id"); required(selection,"as_of");
            require(selection.path("max_age_seconds").canConvertToLong() && selection.path("max_age_seconds").asLong() >= 0, "invalid max_age_seconds");
        }
        Map<Path,String> inputPaths = new LinkedHashMap<>();
        if (selection != null) for (JsonNode entry : array(selection,"results",100)) {
            Path path = resolve(selectionPath.toAbsolutePath().getParent(), required(entry,"path"));
            require(inputPaths.putIfAbsent(path, digestField(entry,"sha256")) == null, "duplicate selected result path");
        }
        inputPaths.putIfAbsent(primaryPath, null);
        require(inputPaths.size() <= 100, "too many selected results");
        List<Candidate> candidates = new ArrayList<>();
        for (var input : inputPaths.entrySet()) {
            require(!output.startsWith(input.getKey().getParent()), "coverage output must be outside canonical run tree");
            candidates.add(candidate(input.getKey(), input.getValue(), selection));
        }
        Candidate primary = null;
        for (Candidate candidate : candidates) if (candidate.path.equals(primaryPath)) primary = candidate;
        require(primary != null, "primary result missing from selection");
        Map<CaseIdentity,Chosen> chosen = select(candidates);
        ObjectNode document = obj("schema_version","coverage_report.v1","generated_at",Instant.now().toString(),
                "generation_status","GENERATED","publication_status","PUBLISHED",
                "evidence_classification","framework_verification_only","downstream_release_evidence",false,
                "subject",primary.subject,"canonical_outcome",outcome(text(primary.result,"status")));
        ObjectNode selectionOut = obj("id",selection == null ? null : selection.get("selection_id"),
                "policy",selection == null ? "explicit_result_only" : selection.get("policy"),
                "as_of",selection == null ? nullable(text(primary.result,"end_time")) : selection.get("as_of"),
                "max_age_seconds",selection == null ? null : selection.get("max_age_seconds"),
                "input_sha256",selectionPath == null ? null : sha(regular(selectionPath)));
        document.set("selection",selectionOut);
        ArrayNode sources = document.putArray("sources");
        for (Candidate c : candidates) {
            boolean selected = chosen.values().stream().anyMatch(v -> v.source == c);
            sources.add(obj("kind","canonical_result","path",c.path.toString(),"sha256",c.digest,
                    "run_id",c.result.get("run_id"),"batch_id",c.result.get("batch_id"),
                    "start_time",c.result.get("start_time"),"end_time",c.result.get("end_time"),
                    "selection_state",selected ? "SELECTED" : c.compatible ? "SUPERSEDED" : "INCOMPATIBLE",
                    "reason",selected ? c.evidence : c.compatible ? "newer_identity_compatible_result" : "identity_mismatch"));
        }
        ArrayNode oracleTraces=document.putArray("oracle_trace");Set<String>oracleTraceIds=new HashSet<>();
        ArrayNode cases = document.putArray("cases"),knowledgeTrace=document.putArray("knowledge_trace"),sourceCaseSelection=document.putArray("source_case_selection");
        Set<Candidate>projectedSourceSelections=Collections.newSetFromMap(new IdentityHashMap<>());
        for (Chosen v : chosen.values()) {
            Candidate c=v.source; String id=required(v.test,"test_case_id");
            ObjectNode caseRow=obj("test_case_id",id,"run_id",c.result.get("run_id"),"batch_id",c.result.get("batch_id"),
                    "source_result",c.path.toString(),"canonical_outcome",outcome(text(v.test,"status")),
                    "evidence_state",c.evidence,"expected_negative",obj("expected",null,"detection","UNKNOWN","evidence_refs",List.of(),"reason","independent_negative_detection_evidence_missing"),
                    "mapping_refs",mappingRefs(c.provenance,id),"evidence_refs",references(c.path));
            if(reviewedV2(c.provenance))caseRow.put("qualified_case_ref",qualifiedCase(c,id));cases.add(caseRow);
            ArrayNode caseTraceRefs=caseRow.putArray("oracle_trace_refs");
            for(JsonNode trace:oracleTrace(c,v.test,catalog)) {
                String traceId=required(trace,"trace_id");
                if(oracleTraceIds.add(traceId))oracleTraces.add(trace);
                if(!contains(caseTraceRefs,traceId))caseTraceRefs.add(traceId);
                require(oracleTraces.size()<=10000,"too many aggregate oracle trace rows");
            }
            if(c.knowledgeValid){
                for(JsonNode sourceRow:c.provenance.path("knowledge_trace"))if(id.equals(text(sourceRow,"test_case_id"))){
                    ObjectNode row=(ObjectNode)sourceRow.deepCopy();row.put("run_id",text(c.result,"run_id")).put("batch_id",text(c.result,"batch_id")).put("source_result",c.path.toString()).put("source_result_sha256",c.digest).put("canonical_outcome",outcome(text(v.test,"status"))).put("evidence_state",c.evidence).put("historical_context_state","VALID_AT_INVOCATION").put("new_invocation_eligibility","NOT_EVALUATED_REQUIRES_FRESH_REVIEW");
                    for(String field:List.of("knowledge_context_scope","source_lock","source_repository_versions"))row.set(field,c.provenance.path(field).deepCopy());row.set("scope",c.subject.deepCopy());row.set("evidence_refs",references(c.path));knowledgeTrace.add(row);
                }
                if(projectedSourceSelections.add(c))for(JsonNode sourceRow:c.provenance.path("source_case_selection")){ObjectNode row=(ObjectNode)sourceRow.deepCopy();row.put("run_id",text(c.result,"run_id")).put("batch_id",text(c.result,"batch_id")).put("source_result",c.path.toString()).put("source_result_sha256",c.digest);sourceCaseSelection.add(row);}
            }
        }
        ObjectNode inventory = inventory(catalog,catalogPath,primary.subject);
        document.set("inventory",inventory);
        document.set("risk_inventory",obj("state","UNKNOWN","authority",null,"revision",null,
                "denominator",null,"reason","risk_inventory_missing"));
        ArrayNode entities = document.putArray("entities");
        ObjectNode metrics = document.putObject("metrics");
        if (catalog == null) {
            metrics.set("requirements",JSON.valueToTree(metric(null,0,0,0)));
            metrics.set("acceptance_criteria",JSON.valueToTree(metric(null,0,0,0)));
        } else {
            projectEntities(catalog,cases,entities,metrics);
        }
        entityOracleRefs(entities,oracleTraces);
        if(!oracleTraces.isEmpty())gap("oracle_independence_unknown","oracle_trace",primary.path,
                "Review separately evidenced oracle independence; source binding and a declared assertion reference do not prove independence or evaluated assertions.",false);
        ObjectNode observations=document.putObject("observations");
        observations.put("selected_case_count",cases.size());
        observations.set("declared_requirement_mapping",primary.provenance.path("requirement_mapping").isArray()
                ? primary.provenance.path("requirement_mapping") : JSON.createArrayNode());
        observations.put("mapping_inventory_completeness","DECLARED_SUBSET");
        Set<String> mappedTests=new HashSet<>();
        for(JsonNode entity:entities) for(JsonNode test:entity.path("test_case_ids")) mappedTests.add(test.asText());
        ArrayNode unmapped=observations.putArray("unmapped_test_case_ids");
        for(JsonNode c:cases) if(!mappedTests.contains(caseKey(c))) unmapped.add(caseKey(c));
        document.set("breakdowns",breakdowns(chosen));
                require(!(catalog != null && catalog.has("code_coverage_artifacts") && selection != null && selection.has("code_coverage_artifacts")), "code artifacts supplied in both catalog and selection");
        JsonNode codeSource = selection != null && selection.has("code_coverage_artifacts") ? selection : catalog;
        Path codeSourcePath = selection != null && selection.has("code_coverage_artifacts") ? selectionPath : catalogPath;
        document.set("code_coverage",codeCoverage(codeSource,codeSourcePath,primary.subject));
        if(catalog==null) gap("authoritative_inventory_missing","inventory",primary.path,"Supply a reviewed scope/revision-bound inventory to measure full requirement/AC coverage.",false);
        gap("risk_inventory_missing","risk_inventory",primary.path,
                "Supply a reviewed risk inventory with explicit authority and revision; no risk denominator is inferred.",false);
        boolean incomplete = "UNKNOWN".equals(document.path("risk_inventory").path("state").asText())
                || catalog == null || entities.findValuesAsText("satisfaction_state").stream().anyMatch(s -> !Set.of("SATISFIED","EXCLUDED").contains(s));
        for(JsonNode c:cases) if(!"VALID".equals(c.path("evidence_state").asText())) incomplete=true;
        String status = invalid ? "INVALID" : incomplete ? "INCOMPLETE" : "VALID";
        document.put("report_status",status); document.set("gaps",gaps); document.set("evidence_links",links);
        verifySnapshots();
        publish(output, document);
        try { verifySnapshots(); }
        catch (Exception changed) {
            Files.deleteIfExists(output.resolve("coverage.json"));
            Files.deleteIfExists(output.resolve("coverage.md"));
            throw changed;
        }
        return new CanonicalCoverageReportResult(output.resolve("coverage.json"),output.resolve("coverage.md"),status,document);
    }

    private Candidate candidate(Path path, String expectedDigest, JsonNode selection) throws Exception {
        path=regular(path); JsonNode d=readCanonical(path); String digest=sha(path); pin(path,"canonical_result",text(d,"run_id"));
        JsonNode provenance=JSON.createObjectNode(); String state="VALID";
        Path provenancePath=path.getParent().resolve("product-test/reviewed-handoff.json");
        if(d.has("product_test") && Files.exists(provenancePath,LinkOption.NOFOLLOW_LINKS)) {
            provenance=read(provenancePath,INPUT_LIMIT); pin(provenancePath,"reviewed_handoff",text(d,"run_id"));
        }
        ObjectNode subject=subject(d,provenance);
        boolean compatible=true;
        if(selection!=null) compatible=scopeMatches(selection.path("expected_scope"),subject);
        String version=text(d,"result_contract_version");
        if ("v0.3".equals(text(d,"dsl_version")) || d.has("product_test"))
            require("v0.3".equals(version), "canonical result_contract_version v0.3 is required for modern or reviewed evidence");
        if(version.isEmpty()) version=text(d,"dsl_version");
        compatible &= Set.of("v0.2","v0.3").contains(version);
        if(!compatible) { state="MISMATCH"; gap("identity_or_version_mismatch",text(d,"run_id"),path,"Select results with the expected scope, repository revisions and supported contract version.",true); }
        if(expectedDigest!=null && !expectedDigest.equals(digest)) { state="TAMPERED"; gap("result_digest_mismatch",text(d,"run_id"),path,"Restore the pinned canonical result; do not fall back to older PASS.",true); }
        Instant start=time(d,"start_time"),end=time(d,"end_time");
        if(start!=null && end!=null && end.isBefore(start)) { state="INVALID"; gap("invalid_result_time",text(d,"run_id"),path,"Fix inconsistent result timestamps.",true); }
        if(selection!=null) {
            Instant asOf=Instant.parse(required(selection,"as_of"));
            if(start==null||end==null||end.isAfter(asOf)) { state="INVALID"; gap("invalid_selection_time",text(d,"run_id"),path,"Supply valid start/end times at or before as_of.",true); }
            else if(Duration.between(end,asOf).getSeconds()>selection.path("max_age_seconds").asLong() && "VALID".equals(state)) state="STALE";
        }
        var canonical = new ContractBaselineService().report(path);
        if(!canonical.valid()) { if("VALID".equals(state)) state="INVALID"; gap("canonical_result_invalid",text(d,"run_id"),path,"Repair canonical result contract findings before claiming satisfaction.",true); }
        var service = new EvidenceHardeningService();
        if(service.hasEvidenceIndexReference(path)) {
            var evidence=service.validateResult(path);
            if(!evidence.valid()) {
                if("VALID".equals(state)) state=evidence.missingEvidenceCount()>0?"MISSING":"INVALID";
                gap("canonical_evidence_invalid",text(d,"run_id"),path,"Restore valid identity-bound and masked canonical evidence before claiming satisfaction.",true);
            }
            try { indexReferences(path,d); }
            catch(Exception e) { if("VALID".equals(state)) state="MISSING"; gap("missing_or_invalid_reference",text(d,"run_id"),path,"Restore regular contained indexed evidence files: "+e.getMessage(),true); }
        } else {
            if("VALID".equals(state)) state="MISSING";
            gap("evidence_index_missing",text(d,"run_id"),path,"Supply indexed canonical evidence; a result status alone cannot satisfy coverage.",false);
        }
        if(!text(d,"suite_summary_ref").isEmpty()) {
            try { Path summary=contained(path.getParent(),text(d,"suite_summary_ref")); pin(summary,"suite_summary",text(d,"run_id")); }
            catch(Exception e) { if("VALID".equals(state)) state="MISSING"; gap("suite_summary_missing",text(d,"run_id"),path,"Restore the canonical suite summary.",true); }
        }
        boolean knowledgeValid=false;
        if(reviewedV2(provenance))try{KnowledgeSelectionProof.validateProvenance(JSON.convertValue(provenance,Map.class),JSON.convertValue(d,Map.class));knowledgeValid=true;}
        catch(IllegalArgumentException invalidKnowledge){state="INVALID";gap("knowledge_provenance_invalid",text(d,"run_id"),path,"Restore exact recorded knowledge, source, case, oracle and invocation joins.",true);}
        return new Candidate(path,d,provenance,subject,start,end,state,compatible,digest,knowledgeValid);
    }

    private Map<CaseIdentity,Chosen> select(List<Candidate> candidates) {
        Map<CaseIdentity,Chosen> result=new LinkedHashMap<>();
        for(Candidate c:candidates) if(c.compatible) {
            JsonNode tests=c.result.path("test_results");
            if(!tests.isArray()) tests=JSON.createArrayNode().add(c.result);
            for(JsonNode t:tests) {
                String id=required(t,"test_case_id");
                // JsonNode object equality/hashCode compare repository maps structurally, regardless of key order.
                CaseIdentity key=new CaseIdentity(text(c.subject,"project_ref"),text(c.subject,"workspace_ref"),
                        c.subject.path("repository_revisions").deepCopy(),text(c.result,"suite_id"),text(c.result,"profile"),id,reviewedV2(c.provenance)?qualifiedCase(c,id):"");
                Chosen old=result.get(key);
                if(old==null) result.put(key,new Chosen(c,t));
                else {
                    int order=Comparator.nullsFirst(Comparator.<Instant>naturalOrder()).compare(c.end,old.source.end);
                    if(order==0) order=Comparator.nullsFirst(Comparator.<Instant>naturalOrder()).compare(c.start,old.source.start);
                    require(order!=0 || c.digest.equals(old.source.digest),"ambiguous selected result timestamp tie");
                    if(order>0) result.put(key,new Chosen(c,t));
                }
            }
        }
        return result;
    }

    private ObjectNode subject(JsonNode d,JsonNode p) {
        ObjectNode s=obj("domain","product","suite_id",d.get("suite_id"),"profile",d.get("profile"),"run_id",d.get("run_id"),"batch_id",d.get("batch_id"));
        for(String field:List.of("project_ref","workspace_ref","repository_revisions","framework_version","framework_artifact_sha256","result_contract_version","plan_digest")) {
            JsonNode v=d.get(field); if(v==null) v=d.path("product_test").get(field); if(v==null) v=p.get(field); s.set(field,v==null?NullNode.instance:v);
        }
        return s;
    }
    private ObjectNode inventory(JsonNode c,Path path,JsonNode subject) throws Exception {
        if(c==null) return obj("state","UNKNOWN","completeness","UNKNOWN","catalog_revision",null,"catalog_sha256",null,"authority",null,"scope",null,"reason","authoritative_inventory_missing");
        require("coverage-catalog.v1".equals(required(c,"schema_version")),"unsupported catalog version");
        required(c,"catalog_revision"); JsonNode a=c.path("authority");
        require("reviewed_inventory".equals(required(a,"kind")),"invalid catalog authority");
        for(String f:List.of("source_ref","review_ref","approved_by")) required(a,f);
        digestField(a,"source_sha256"); Instant.parse(required(a,"approved_at"));
        JsonNode scope=c.path("scope"); required(scope,"domain"); required(scope,"project_ref"); required(scope,"workspace_ref");
        require(scope.path("repository_revisions").isObject()&&!scope.path("repository_revisions").isEmpty(),"catalog revisions missing");
        scope.path("repository_revisions").properties().forEach(e -> require(e.getValue().isTextual()&&!e.getValue().asText().isBlank(),"invalid catalog revision"));
        require(scopeMatches(scope,subject),"catalog scope/revision mismatch");
        var reqs=array(c,"requirements",10000);var acs=array(c,"acceptance_criteria",10000);
        pin(path,"reviewed_catalog","");
        return obj("state",reqs.isEmpty()&&acs.isEmpty()?"KNOWN_EMPTY":"KNOWN","completeness","REVIEWED_INVENTORY","catalog_revision",c.get("catalog_revision"),"catalog_sha256",sha(regular(path)),"authority",a,"scope",scope,"reason",reqs.isEmpty()&&acs.isEmpty()?"zero_denominator":null);
    }
    private void projectEntities(JsonNode catalog,ArrayNode cases,ArrayNode entities,ObjectNode metrics) {
        Map<String,JsonNode> reqs=unique(array(catalog,"requirements",10000)), acs=unique(array(catalog,"acceptance_criteria",10000));
        Map<String,JsonNode> tests=new HashMap<>(); for(JsonNode c:cases) require(tests.put(caseKey(c),c)==null,"ambiguous test identity across selected scopes");
        Map<String,ObjectNode> acEntities=new LinkedHashMap<>();
        for(var entry:acs.entrySet()) {
            String id=entry.getKey();JsonNode ac=entry.getValue();String parent=required(ac,"requirement_id"),classification=required(ac,"classification");
            require(reqs.containsKey(parent),"orphan AC requirement: "+parent);
            require(Set.of("automatable","partial","manual_only","waived").contains(classification),"invalid AC classification");
            List<String> ids=strings(ac.path("test_case_ids"),true);require(ids.isEmpty()||!text(ac,"review_ref").isEmpty(),"AC mapping review_ref missing");
            boolean excluded=Set.of("manual_only","waived").contains(classification)&&exclusionApproved(ac.path("exclusion"));
            boolean mapped=!ids.isEmpty()&&ids.stream().allMatch(tests::containsKey);
            boolean executed=mapped&&ids.stream().allMatch(t -> "VALID".equals(text(tests.get(t),"evidence_state"))&&Set.of("PASS","FAIL").contains(text(tests.get(t),"canonical_outcome")));
            boolean pass=executed&&ids.stream().allMatch(t -> "PASS".equals(text(tests.get(t),"canonical_outcome")));
            String satisfaction=excluded?"EXCLUDED":!Set.of("automatable","partial").contains(classification)?"UNSATISFIED":"partial".equals(classification)?"UNKNOWN":pass?"SATISFIED":"UNSATISFIED";
            String reason=excluded?text(ac.path("exclusion"),"reason"):"partial".equals(classification)?"partial_remainder_not_evidenced":!Set.of("automatable","partial").contains(classification)?"unapproved_exclusion":!mapped?"unmapped_ac":!pass?"mapped_tests_not_satisfied":null;
            ObjectNode e=obj("kind","acceptance_criterion","id",id,"parent_requirement_id",parent,"classification",classification,"state",excluded?"EXCLUDED":mapped?"MAPPED":"UNMAPPED","reason",reason,"test_case_ids",ids,"mapping_refs",ids.isEmpty()?List.of():List.of(text(ac,"review_ref")),"evidence_refs",evidenceRefs(ids,tests),"execution_state",executed?"EXECUTED":"NOT_RUN","satisfaction_state",satisfaction,"mapped_test_satisfaction_state",pass?"SATISFIED":"UNSATISFIED");
            if(reason!=null) gap(reason,id,null,"Review the explicit mapping, exclusion or remainder and collect current valid evidence.",false);
            acEntities.put(id,e); entities.add(e);
        }
        for(var entry:reqs.entrySet()) {
            String id=entry.getKey(); List<String> ids=strings(entry.getValue().path("ac_ids"),false);
            for(String ac:ids) require(acs.containsKey(ac)&&id.equals(text(acs.get(ac),"requirement_id")),"requirement/AC linkage mismatch: "+ac);
            for(var ac:acs.entrySet()) if(id.equals(text(ac.getValue(),"requirement_id"))) require(ids.contains(ac.getKey()),"requirement missing AC linkage: "+ac.getKey());
            List<ObjectNode> eligible=ids.stream().map(acEntities::get).filter(e -> !"EXCLUDED".equals(text(e,"state"))).toList();
            boolean excluded=!ids.isEmpty()&&eligible.isEmpty(),mapped=eligible.stream().anyMatch(e -> "MAPPED".equals(text(e,"state")));
            boolean executed=!eligible.isEmpty()&&eligible.stream().allMatch(e -> "EXECUTED".equals(text(e,"execution_state")));
            boolean satisfied=!eligible.isEmpty()&&eligible.stream().allMatch(e -> "SATISFIED".equals(text(e,"satisfaction_state")));
            boolean unknown=mapped&&eligible.stream().anyMatch(e -> "UNKNOWN".equals(text(e,"satisfaction_state")))&&eligible.stream().noneMatch(e -> "UNSATISFIED".equals(text(e,"satisfaction_state")));
            entities.add(obj("kind","requirement","id",id,"ac_ids",ids,"state",excluded?"EXCLUDED":mapped?"MAPPED":"UNMAPPED","reason",excluded?"all_child_ac_approved_excluded":!mapped?"unmapped_requirement":unknown?"partial_remainder_not_evidenced":null,"test_case_ids",List.of(),"mapping_refs",List.of(),"evidence_refs",List.of(),"execution_state",executed?"EXECUTED":"NOT_RUN","satisfaction_state",excluded?"EXCLUDED":satisfied?"SATISFIED":unknown?"UNKNOWN":"UNSATISFIED"));
        }
        metrics.set("acceptance_criteria",JSON.valueToTree(measure(entities,"acceptance_criterion")));
        metrics.set("requirements",JSON.valueToTree(measure(entities,"requirement")));
    }
    private Metric measure(ArrayNode entities,String kind) {
        int denominator=0,mapped=0,executed=0,satisfied=0;
        for(JsonNode e:entities) if(kind.equals(text(e,"kind"))&&!"EXCLUDED".equals(text(e,"state"))) {
            denominator++; if("MAPPED".equals(text(e,"state"))) mapped++; if("EXECUTED".equals(text(e,"execution_state"))) executed++; if("SATISFIED".equals(text(e,"satisfaction_state"))) satisfied++;
        }
        return metric(denominator,mapped,executed,satisfied);
    }
    private static Metric metric(Integer d,int m,int e,int s) { return new Metric(d,d==null?null:m,d==null?null:e,d==null?null:s,percent(m,d),percent(e,d),percent(s,d)); }
    private static Double percent(long n,Number d) { return d==null||d.longValue()==0?null:100.0*n/d.longValue(); }
    private boolean exclusionApproved(JsonNode e) {
        try { for(String f:List.of("reason","review_ref","approved_by")) required(e,f); Instant.parse(required(e,"approved_at"));return true; } catch(RuntimeException ex) {return false;}
    }
    private ArrayNode breakdowns(Map<CaseIdentity,Chosen> chosen) {
        ArrayNode result=JSON.createArrayNode();
        for(var v:chosen.values()) {
            JsonNode p=v.source.provenance,d=v.source.result;
            result.add(obj("test_case_id",v.test.get("test_case_id"),"scope",v.source.subject,"provider_ids",v.test.path("provider_ids"),"provider_types",v.test.path("provider_types"),"protocol_contracts",p.path("reviewed_contracts"),
                    "test_level",unknown(text(p,"test_level")),"knowledge_mode",unknown(text(p,"knowledge_mode")),"environment_stage",unknown(text(p,"environment_stage")),"observed_provider_mode",p.path("observed_provider_modes"),"observed_dependency_mode",p.path("observed_dependency_modes"),
                    "evidence_classification",unknown(text(d.path("labels"),"evidence_classification")),"execution_domain",nativeFixture(p)?"native_fixture":"UNKNOWN","inventory_state","UNKNOWN","canonical_outcome",outcome(text(v.test,"status")),"reason","no_cross_domain_inventory_inference"));
        }
        for(String domain:List.of("framework_unit","module","native_swarm")) result.add(obj("execution_domain",domain,"inventory_state","UNKNOWN","reason","independent_domain_evidence_missing"));
        return result;
    }
    private static boolean reviewedV2(JsonNode provenance){return "product-test-reviewed-handoff.v2".equals(text(provenance,"contract_version"));}
    private static String qualifiedCase(Candidate candidate,String testId){
        Set<String>refs=new HashSet<>();for(JsonNode row:candidate.provenance.path("knowledge_trace"))if(testId.equals(text(row,"test_case_id")))refs.add(required(row,"qualified_case_ref"));
        require(refs.size()==1,"reviewed v2 qualified case identity missing or ambiguous");String ref=refs.iterator().next();require(ref.matches("[^@#\\s]+@[a-f0-9]{40}(?:[a-f0-9]{24})?:[^@#\\s]+#[^@#\\s]+"),"reviewed v2 qualified case identity invalid");return ref;
    }
    private static String caseKey(JsonNode row){return row.hasNonNull("qualified_case_ref")?required(row,"qualified_case_ref"):required(row,"test_case_id");}
    private static boolean nativeFixture(JsonNode provenance) {
        if (!"integration".equals(text(provenance, "test_level"))) return false;
        for (JsonNode mode : provenance.path("observed_provider_modes")) if ("real".equals(mode.asText())) return true;
        return false;
    }
    private ArrayNode codeCoverage(JsonNode catalog,Path catalogPath,JsonNode subject) throws Exception {
        ArrayNode result=JSON.createArrayNode();Set<String> domains=new HashSet<>();
        if(catalog!=null && catalog.has("code_coverage_artifacts")) for(JsonNode entry:array(catalog,"code_coverage_artifacts",100)) {
            String domain=required(entry,"domain");require(domains.add(domain),"duplicate code coverage domain");
            ObjectNode measured=obj("domain",domain,"state","MEASURED","tool","JaCoCo","scope",obj("kind","artifact_aggregate","completeness","UNKNOWN"),"line",null,"branch",null,"reason",null);
            try {
                String revision=required(entry,"repository_revision"); measured.put("revision_binding",revision);
                // Both delivered Java components are built from the framework repository, not any product repository.
                if (!Set.of("framework_unit", "module").contains(domain)) {
                    measured.put("state", "STALE").putNull("repository")
                            .put("reason", "instrumentation_repository_binding_missing");
                    result.add(measured); continue;
                }
                measured.put("repository", "framework");
                String frameworkRevision=text(subject.path("repository_revisions"), "framework");
                if (frameworkRevision.isEmpty() || !revision.equals(frameworkRevision)) {
                    measured.put("state", "STALE").put("reason", frameworkRevision.isEmpty()
                            ? "instrumentation_repository_binding_missing" : "instrumentation_revision_mismatch");
                    result.add(measured); continue;
                }
                Path xml=resolve(catalogPath.toAbsolutePath().getParent(),required(entry,"path"));
                require(digestField(entry,"sha256").equals(sha(regular(xml))),"instrumentation digest mismatch"); pin(xml,"jacoco_xml","");
                measured.put("artifact_ref",xml.toString()).put("artifact_sha256",sha(xml));
                DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false);
                factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
                var builder=factory.newDocumentBuilder();
                builder.setEntityResolver((publicId,systemId) -> new org.xml.sax.InputSource(new StringReader("")));
                var parsed=builder.parse(new ByteArrayInputStream(bytes(xml,OUTPUT_LIMIT)));
                require(parsed.getDoctype()==null || parsed.getDoctype().getInternalSubset()==null || parsed.getDoctype().getInternalSubset().isBlank(),"XML internal entities prohibited");
                Element root=parsed.getDocumentElement();require("report".equals(root.getTagName()),"invalid JaCoCo report root");
                Set<String> types=new HashSet<>();
                for(var node=root.getFirstChild();node!=null;node=node.getNextSibling()) if(node instanceof Element el && "counter".equals(el.getTagName())) {
                    String type=el.getAttribute("type"); if(!Set.of("LINE","BRANCH").contains(type))continue;
                    require(types.add(type),"duplicate JaCoCo aggregate counter");long covered=Long.parseLong(el.getAttribute("covered")),missed=Long.parseLong(el.getAttribute("missed"));require(covered>=0&&missed>=0,"negative JaCoCo counter");long total=Math.addExact(covered,missed);
                    measured.set(type.toLowerCase(Locale.ROOT),obj("covered",covered,"missed",missed,"total",total,"percent",percent(covered,total)));
                }
                require(types.containsAll(Set.of("LINE","BRANCH")),"missing JaCoCo line/branch counter");
                if(entry.hasNonNull("scope")) measured.set("scope",instrumentationScope(entry.path("scope"),xml,root,revision,catalogPath));
            } catch(Exception e) { measured.put("state","INVALID").put("reason",e.getMessage());gap("instrumentation_invalid",domain,catalogPath,"Supply matching instrumented artifacts and explicit reviewed scope provenance.",true); }
            result.add(measured);
        }
        for(String domain:List.of("framework_unit","module","native_fixture","native_swarm")) if(!domains.contains(domain)) result.add(obj("domain",domain,"state","MISSING","tool",null,"scope",obj("kind","artifact_aggregate","completeness","UNKNOWN"),"line",null,"branch",null,"reason","instrumented_artifact_missing"));
        return result;
    }
    private ObjectNode instrumentationScope(JsonNode scope,Path xml,Element root,String revision,Path catalogPath) throws Exception {
        String kind=required(scope,"kind");require(Set.of("whole_project","selected_classes").contains(kind),"invalid instrumentation scope");
        Path provenance=resolve(catalogPath.toAbsolutePath().getParent(),required(scope,"provenance_ref"));String digest=digestField(scope,"provenance_sha256");require(digest.equals(sha(regular(provenance))),"scope provenance digest mismatch");JsonNode p=read(provenance,INPUT_LIMIT);pin(provenance,"instrumentation_scope","");
        require(revision.equals(required(p,"repository_revision"))&&kind.equals(required(p,"kind")),"scope provenance revision/kind mismatch");
        List<String> included=strings(scope.path("included_classes"),false),excluded=strings(scope.path("excluded_classes"),false);required(scope,"exclusions_reason");
        require(!included.isEmpty()&&new HashSet<>(included).equals(new HashSet<>(strings(p.path("included_classes"),false)))&&new HashSet<>(excluded).equals(new HashSet<>(strings(p.path("excluded_classes"),false))),"scope class inventory/exclusions mismatch");
        require(text(scope,"exclusions_reason").equals(required(p,"exclusions_reason")),"scope exclusion reason mismatch");
        Set<String> observed=new HashSet<>();var classes=root.getElementsByTagName("class");for(int i=0;i<classes.getLength();i++) observed.add(((Element)classes.item(i)).getAttribute("name"));
        require(observed.equals(new HashSet<>(included))&&Collections.disjoint(included,excluded),"instrumentation class inventory mismatch");
        if("whole_project".equals(kind)) { List<String> project=strings(p.path("project_classes"),false);Set<String> all=new HashSet<>(included);all.addAll(excluded);require(all.equals(new HashSet<>(project)),"whole project inventory missing");required(p,"review_ref");required(p,"approved_by");Instant.parse(required(p,"approved_at")); }
        ObjectNode result=(ObjectNode)scope.deepCopy();result.put("completeness","PINNED");return result;
    }

    private void indexReferences(Path result,JsonNode d) throws Exception {
        Path root=result.getParent(),index=contained(root,required(d,"evidence_index_ref"));pin(index,"evidence_index",text(d,"run_id"));
        LoaderOptions options=new LoaderOptions();options.setAllowDuplicateKeys(false);options.setMaxAliasesForCollections(0);options.setCodePointLimit(OUTPUT_LIMIT);options.setNestingDepthLimit(80);
        JsonNode idx=JSON.valueToTree(new Yaml(new SafeConstructor(options)).load(new String(bytes(index,OUTPUT_LIMIT),java.nio.charset.StandardCharsets.UTF_8)));
        for(JsonNode entry:array(idx,"entries",10000)) {
            Path path=contained(index.getParent(),required(entry,"file_path"));require(path.startsWith(root),"indexed evidence escaped run root");pin(path,"indexed_evidence",text(d,"run_id"));
        }
    }
    private ArrayNode references(Path result) {
        ArrayNode array=JSON.createArrayNode();for(JsonNode link:links) if(link.path("path").asText().startsWith(result.getParent().toString()+"/"))array.add(link);return array;
    }
    private ArrayNode mappingRefs(JsonNode p,String id) {
        ArrayNode refs=JSON.createArrayNode();for(JsonNode mapping:p.path("requirement_mapping")) for(JsonNode test:mapping.path("test_case_ids"))if(id.equals(test.asText()))refs.add(mapping);return refs;
    }
    /** Historical metadata joins only; a reviewed pointer is neither an evaluated assertion nor independence. */
    private ArrayNode oracleTrace(Candidate candidate, JsonNode test, JsonNode catalog) throws Exception {
        String id=required(test,"test_case_id");
        String qualified=reviewedV2(candidate.provenance)?qualifiedCase(candidate,id):null;
        ArrayNode mappings=mappingRefs(candidate.provenance,id), rows=JSON.createArrayNode();
        List<JsonNode> declaredMappings=new ArrayList<>();mappings.forEach(declaredMappings::add);
        if(declaredMappings.isEmpty())declaredMappings.add(JSON.createObjectNode());
        for(JsonNode mapping:declaredMappings) {
            List<String> declared=strings(mapping.path("oracle_refs"),true);
            List<String> pointers=declared.isEmpty()?Collections.singletonList(null):declared;
            String requirement=nullable(text(mapping,"requirement_ref"));
            ArrayNode knowledge=JSON.createArrayNode();Map<String,JsonNode> oracleSources=new LinkedHashMap<>();
            if(candidate.knowledgeValid)for(JsonNode trace:candidate.provenance.path("knowledge_trace")) {
                if(!id.equals(text(trace,"test_case_id"))||!Objects.equals(qualified,text(trace,"qualified_case_ref"))
                        || requirement!=null&&!contains(trace.path("requirement_refs"),requirement))continue;
                ObjectNode ref=obj("qualified_source_ref",trace.get("qualified_source_ref"),"record_version",trace.get("record_version"),
                        "content_sha256",trace.get("content_sha256"),"selection_proof_sha256",trace.get("selection_proof_sha256"),
                        "context_sha256",trace.get("context_sha256"),"source_bindings_sha256",trace.get("source_bindings_sha256"));
                if(!containsNode(knowledge,ref))knowledge.add(ref);
                oracleSources.putIfAbsent(required(trace.path("oracle"),"qualified_ref"),trace.path("oracle"));
            }
            for(String pointer:pointers) {
                String reason=pointer==null?"oracle_reference_missing":null;
                String binding="MISSING";ObjectNode oracle=emptyOracle();
                JsonNode artifact=null, selectedOracle=null;
                if(candidate.knowledgeValid) {
                    for(JsonNode traceOracle:oracleSources.values()) {
                        JsonNode bound=oracleArtifact(candidate.provenance,required(traceOracle,"qualified_ref"));
                        if(pointer!=null&&pointer.equals(text(bound,"reviewed_path"))) {
                            require(artifact==null,"ambiguous oracle pointer source binding");artifact=bound;selectedOracle=traceOracle;
                        }
                    }
                    if(artifact==null&&oracleSources.size()==1) {
                        selectedOracle=oracleSources.values().iterator().next();artifact=oracleArtifact(candidate.provenance,required(selectedOracle,"qualified_ref"));
                    }
                    if(artifact!=null) {
                        oracle=obj("qualified_ref",selectedOracle.get("qualified_ref"),"path",artifact.get("reviewed_path"),"sha256",selectedOracle.get("sha256"),
                                "source_repo",artifact.get("source_repo"),"source_commit",artifact.get("source_commit"),
                                "source_testrepo_version",artifact.get("source_testrepo_version"),"review_ref",selectedOracle.get("review_ref"),"reviewed_by",selectedOracle.get("reviewed_by"));
                        binding="SOURCE_BOUND";
                    } else if(oracleSources.size()>1)binding="AMBIGUOUS";
                } else if(!reviewedV2(candidate.provenance)&&pointer!=null&&!pointer.contains("#")) {
                    JsonNode reviewed=reviewedOracle(candidate.provenance,pointer);
                    if(reviewed!=null) {
                        oracle.set("path",JSON.valueToTree(pointer));oracle.set("sha256",reviewed.get("sha256"));
                        oracle.set("review_ref",JSON.valueToTree(nullable(text(mapping,"review_ref"))));
                        oracle.set("reviewed_by",JSON.valueToTree(nullable(text(candidate.provenance,"reviewed_by"))));binding="SOURCE_BOUND";
                    } else binding="DECLARED_ONLY";
                } else if(pointer!=null)binding="DECLARED_ONLY";
                String expected=null;
                boolean singleton=mapping.path("test_case_ids").isArray()&&mapping.path("test_case_ids").size()==1&&declared.size()==1;
                if(pointer!=null) {
                    if(!singleton)reason="mapping_case_oracle_join_ambiguous";
                    else if(pointer.contains("#"))reason="declared_assertion_relation_not_exported";
                    else if(text(mapping,"review_ref").isEmpty())reason="mapping_review_missing";
                    else if("SOURCE_BOUND".equals(binding)&&pointer.equals(text(oracle,"path")))expected=pointer;
                    else reason="oracle_source_binding_missing";
                }
                // Keep historical identity, but do not promote stale/missing/invalid evidence into a bound pointer.
                if(!"VALID".equals(candidate.evidence)) {
                    binding="STALE".equals(candidate.evidence)?"STALE":"MISSING".equals(candidate.evidence)?"MISSING":"INVALID";
                    expected=null;reason="selected_evidence_"+candidate.evidence.toLowerCase(Locale.ROOT);
                }
                if(reviewedV2(candidate.provenance)&&!candidate.knowledgeValid) {
                    binding="INVALID";oracle=emptyOracle();expected=null;reason="knowledge_provenance_invalid";
                }
                ArrayNode requirements=JSON.createArrayNode();if(requirement!=null)requirements.add(requirement);
                ArrayNode acceptances=JSON.createArrayNode();
                if(catalog!=null)for(JsonNode ac:catalog.path("acceptance_criteria"))
                    if(Objects.equals(requirement,nullable(text(ac,"requirement_id")))&&contains(ac.path("test_case_ids"),qualified==null?id:qualified))acceptances.add(required(ac,"id"));
                String traceId=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(
                        Arrays.asList(candidate.digest,nullable(text(candidate.subject,"project_ref")),nullable(text(candidate.subject,"workspace_ref")),
                                qualified==null?id:qualified,requirement,nullable(text(mapping,"review_ref")),pointer))));
                rows.add(obj("trace_id",traceId,"test_case_id",id,"qualified_case_ref",qualified,
                        "source_result",candidate.path.toString(),"source_result_sha256",candidate.digest,"run_id",candidate.result.get("run_id"),"batch_id",candidate.result.get("batch_id"),
                        "canonical_outcome",outcome(text(test,"status")),"evidence_state",candidate.evidence,
                        "requirement_refs",requirements,"acceptance_criteria_refs",acceptances,"mapping_review_ref",nullable(text(mapping,"review_ref")),
                        "declared_oracle_refs",declared,"oracle",oracle,"expected_ref",expected,
                        "expected_ref_state",expected==null?"UNKNOWN":"BOUND_DECLARED_REFERENCE","expected_ref_reason",expected==null?reason:null,
                        "binding_state",binding,"binding_reason",reason,"independence",obj("state","UNKNOWN","reason","independence_attestation_not_in_contract"),"knowledge_refs",knowledge));
                require(rows.size()<=10000,"too many oracle trace rows");
            }
        }
        return rows;
    }
    private static ObjectNode emptyOracle() {
        return obj("qualified_ref",null,"path",null,"sha256",null,"source_repo",null,"source_commit",null,"source_testrepo_version",null,"review_ref",null,"reviewed_by",null);
    }
    private static JsonNode oracleArtifact(JsonNode provenance,String qualified) {
        JsonNode found=null;
        for(JsonNode artifact:provenance.path("executed_source_bindings"))if(qualified.equals(text(artifact,"artifact_id"))) {
            require(found==null&&"oracle".equals(text(artifact,"role")),"ambiguous or non-oracle source binding");found=artifact;
        }
        require(found!=null,"oracle source binding missing");return found;
    }
    private static JsonNode reviewedOracle(JsonNode provenance,String path) {
        JsonNode found=null;
        for(JsonNode file:provenance.path("reviewed_files"))if(path.equals(text(file,"path"))) {
            require(found==null,"ambiguous reviewed oracle path");
            if(!"oracle".equals(text(file,"role")))return null;
            digestField(file,"sha256");found=file;
        }
        return found;
    }
    private static boolean contains(JsonNode array,String value) {
        for(JsonNode node:array)if(node.isTextual()&&node.asText().equals(value))return true;return false;
    }
    private static boolean containsNode(JsonNode array,JsonNode value) {
        for(JsonNode node:array)if(node.equals(value))return true;return false;
    }
    private static void entityOracleRefs(ArrayNode entities,ArrayNode traces) {
        for(JsonNode entity:entities) {
            ArrayNode refs=((ObjectNode)entity).putArray("oracle_trace_refs");
            String field="requirement".equals(text(entity,"kind"))?"requirement_refs":"acceptance_criteria_refs";
            for(JsonNode trace:traces)if(contains(trace.path(field),text(entity,"id"))&&!contains(refs,text(trace,"trace_id")))refs.add(text(trace,"trace_id"));
        }
    }

    private ArrayNode evidenceRefs(List<String> ids,Map<String,JsonNode> tests) {
        ArrayNode refs=JSON.createArrayNode();for(String id:ids)if(tests.containsKey(id))for(JsonNode ref:tests.get(id).path("evidence_refs"))refs.add(ref);return refs;
    }
    private void pin(Path path,String kind,String run) throws Exception {
        path=regular(path);String digest=sha(path);String old=snapshots.putIfAbsent(path,digest);require(old==null||old.equals(digest),"input changed during projection");
        if(old==null)links.add(obj("path",path.toString(),"sha256",digest,"kind",kind,"run_id",run));
    }
    private void verifySnapshots() throws Exception { for(var e:snapshots.entrySet())require(e.getValue().equals(sha(regular(e.getKey()))),"canonical input changed during projection: "+e.getKey()); }
    private static void publish(Path output,ObjectNode d) throws Exception {
        require(!Files.exists(output.resolve("coverage.json"),LinkOption.NOFOLLOW_LINKS)&&!Files.exists(output.resolve("coverage.md"),LinkOption.NOFOLLOW_LINKS),"coverage pair already exists; use a fresh output directory");
        Files.createDirectories(output);safeOutput(output);
        byte[] json=JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(d);byte[] md=markdown(d).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        require(json.length<=OUTPUT_LIMIT&&md.length<=OUTPUT_LIMIT,"coverage output size limit exceeded");
        Path jt=Files.createTempFile(output,".coverage-",".json"),mt=null;boolean first=false,second=false;
        try {
            mt=Files.createTempFile(output,".coverage-",".md");Files.write(jt,json);Files.write(mt,md);require(JSON.readTree(Files.readAllBytes(jt)).equals(JSON.readTree(json)) && Arrays.equals(Files.readAllBytes(jt),json),"coverage readback mismatch");require(Arrays.equals(Files.readAllBytes(mt),md),"Markdown readback mismatch");
            Files.move(jt,output.resolve("coverage.json"));first=true;Files.move(mt,output.resolve("coverage.md"));second=true;
        } finally {Files.deleteIfExists(jt);if(mt!=null)Files.deleteIfExists(mt);if(first&&!second)Files.deleteIfExists(output.resolve("coverage.json"));}
    }
    private static String markdown(JsonNode d) {
        StringBuilder s=new StringBuilder("# Coverage\n\nStatus: "+d.path("report_status").asText()+". Inventory: "+d.at("/inventory/state").asText()+". Classification: framework_verification_only.\n\n");
        s.append("Canonical outcome: ").append(d.path("canonical_outcome").asText()).append(". Full coverage and expected-negative detection are separate from this outcome.\n\n");
        s.append("| Metric | Denominator | Mapped | Executed | Satisfied | Satisfied percent |\n| --- | --- | --- | --- | --- | --- |\n");
        d.path("metrics").properties().forEach(e -> {JsonNode m=e.getValue();s.append("| ").append(e.getKey()).append(" | ").append(m.path("denominator")).append(" | ").append(m.path("mapped")).append(" | ").append(m.path("executed")).append(" | ").append(m.path("satisfied")).append(" | ").append(m.path("satisfied_percent")).append(" |\n");});
        s.append("| risks | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN |\n");
        s.append("\nOracle SOURCE_BOUND describes historical source identity only. A declared assertion reference does not prove assertion execution, evaluated expected values, or semantic independence. Independence remains UNKNOWN without a separate attestation contract.\n\n");
        for(String section:List.of("subject","selection","inventory","risk_inventory","entities","cases","oracle_trace","knowledge_trace","source_case_selection","sources","breakdowns","code_coverage","gaps")) s.append("\n## ").append(section).append("\n\n```json\n").append(d.path(section).toPrettyString()).append("\n```\n");
        s.append("\n## Evidence links\n\n");for(JsonNode l:d.path("evidence_links")) {String p=l.path("path").asText();s.append("- [").append(p.replace("[","\\[").replace("]","\\]")).append("](<").append(p.replace(">","%3E").replace("<","%3C")).append(">) SHA256 `").append(l.path("sha256").asText()).append("`\n");}return s.toString();
    }
    private void gap(String reason,String id,Path source,String action,boolean fatal) {invalid|=fatal;gaps.add(obj("reason",reason,"subject_id",id,"source_ref",source==null?null:source.toString(),"owner_action",action));}
    private static boolean scopeMatches(JsonNode expected,JsonNode actual) {
        if (!expected.isObject()) return false;
        // Framework result contracts provide product-test evidence. They cannot establish a native-agent domain.
        if (expected.has("domain") && !expected.path("domain").equals(actual.path("domain"))) return false;
        for (String field : List.of("project_ref", "workspace_ref", "repository_revisions"))
            if (!expected.hasNonNull(field) || !expected.path(field).equals(actual.path(field))) return false;
        return true;
    }
    private static Map<String,JsonNode> unique(List<JsonNode> list) {Map<String,JsonNode> m=new LinkedHashMap<>();for(JsonNode e:list)require(m.putIfAbsent(required(e,"id"),e)==null,"duplicate catalog entity id");return m;}
    private static List<JsonNode> array(JsonNode d,String field,int max) {JsonNode a=d.path(field);require(a.isArray()&&a.size()<=max,"invalid or oversized array: "+field);List<JsonNode> r=new ArrayList<>();a.forEach(r::add);return r;}
    private static List<String> strings(JsonNode a,boolean optional) {if(optional&&a.isMissingNode())return List.of();require(a.isArray()&&a.size()<=10000,"invalid string array");List<String> r=new ArrayList<>();for(JsonNode e:a){require(e.isTextual()&&!e.asText().isBlank()&&e.asText().length()<=4096,"invalid string array entry");require(!r.contains(e.asText()),"duplicate array entry");r.add(e.asText());}return r;}
    private static ObjectNode obj(Object... pairs) {ObjectNode n=JSON.createObjectNode();for(int i=0;i<pairs.length;i+=2)n.set((String)pairs[i],JSON.valueToTree(pairs[i+1]));return n;}
    private static String text(JsonNode d,String f) {return d.path(f).isTextual()?d.path(f).asText():"";}
    private static String required(JsonNode d,String f) {String v=text(d,f);require(!v.isBlank()&&v.length()<=4096,"missing/invalid "+f);return v;}
    private static String digestField(JsonNode d,String f) {String v=required(d,f);require(v.matches("[a-f0-9]{64}"),"invalid SHA256: "+f);return v;}
    private static String nullable(String value) {return value.isEmpty()?null:value;}
    private static String unknown(String value) {return value.isEmpty()?"UNKNOWN":value;}
    private static String outcome(String s) {return switch(s) {case "passed"->"PASS";case "failed"->"FAIL";case "blocked"->"BLOCKED";case "skipped"->"SKIPPED";default->"NOT_RUN";};}
    private static Instant time(JsonNode d,String f) {String s=text(d,f);return s.isEmpty()?null:Instant.parse(s);}
    private static JsonNode read(Path path,int limit) throws IOException { return read(path, limit, JSON); }
    private static JsonNode readCanonical(Path path) throws IOException {
        return JSON.valueToTree(ResultDocumentReader.readJson(path).fields());
    }
    private static JsonNode read(Path path,int limit,ObjectMapper mapper) throws IOException {
        JsonNode d=mapper.readTree(bytes(regular(path),limit));
        require(d!=null&&d.isObject(),"expected JSON object: "+path);
        return d;
    }
    private static byte[] bytes(Path path,int limit) throws IOException {require(Files.size(path)<=limit,"input size limit exceeded: "+path);try(InputStream in=Files.newInputStream(path)){byte[] b=in.readNBytes(limit+1);require(b.length<=limit,"input size limit exceeded");return b;}}
    private static Path regular(Path path) throws IOException {Path p=path.toAbsolutePath().normalize();noSymlinks(p);require(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS),"missing regular file: "+p);return p;}
    private static Path safeOutput(Path path) throws IOException {Path p=path.toAbsolutePath().normalize();noSymlinks(p);return p;}
    private static void noSymlinks(Path path) {for(Path p=path;p!=null;p=p.getParent())require(!Files.isSymbolicLink(p),"symlink prohibited: "+p);}
    private static Path contained(Path root,String ref) throws IOException {Path p=root.resolve(ref).toAbsolutePath().normalize();require(!Path.of(ref).isAbsolute()&&p.startsWith(root.toAbsolutePath().normalize()),"reference escaped canonical tree: "+ref);return regular(p);}
    private static Path resolve(Path parent,String ref) throws IOException {return regular(parent.resolve(ref));}
    private static String sha(Path path) throws Exception {MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(path)){byte[] b=new byte[8192];for(int n;(n=in.read(b))!=-1;)md.update(b,0,n);}return HexFormat.of().formatHex(md.digest());}
    private static void require(boolean condition,String message) {if(!condition)throw new IllegalArgumentException(message);}
}
