package com.specdriven.regression.contract.v03;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.specdriven.regression.contract.ContractBaselineService;
import com.specdriven.regression.producttest.ProductTestHandoffService;
import com.specdriven.regression.producttest.ProductTestHandoffService.Snapshot;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Offline PUBLIC_SYNTHETIC source/metadata/plan contracts only. No provider, SUT, query or real clock. */
class ReviewedSnapshotProfileLayoutTest {
    @TempDir Path temporary;
    final ObjectMapper json=new ObjectMapper();
    private Snapshot snapshot()throws Exception{
        Path src=Path.of("src/test/resources/reviewed-v2-layout"),root=Files.createTempDirectory(temporary,"source-");try(var files=Files.walk(src)){for(Path p:files.toList()){Path target=root.resolve(src.relativize(p));if(Files.isDirectory(p))Files.createDirectories(target);else Files.copy(p,target);}}
        Path h=root.resolve("handoff/reviewed-tests.json");JsonNode facts=json.readTree(h.toFile());Instant recorded=Instant.parse(facts.path("reviewed_at").asText()).plusMillis(1);
        return new ProductTestHandoffService(Clock.fixed(recorded,ZoneOffset.UTC)).snapshot(root.resolve("suite.yaml"),"local",new ProductTestHandoffService.InvocationRequirement(sha(h),null,sha(root.resolve("handoff/runtime-binding.json"))));
    }
    private ContractBaselineService selected(Snapshot s)throws Exception{
        // Baseline path exercises the real current directory-routing defect as a behavioral RED.
        try{return (ContractBaselineService)ContractBaselineService.class.getMethod("fromReviewedSnapshot",Snapshot.class).invoke(null,s);}
        catch(NoSuchMethodException beforeFix){return new ContractBaselineService(s.contractDirectory());}
        catch(InvocationTargetException wrapped){if(wrapped.getCause() instanceof Exception e)throw e;throw wrapped;}
    }
    @Test void exactCasesConfigDataSnapshotValidatesAndCompilesWithoutAliases()throws Exception{
        try(Snapshot s=snapshot()){
            Path root=s.suiteManifest().getParent();String manifest=sha(s.suiteManifest()),profile=sha(root.resolve("config/local.yaml"));
            var baseline=selected(s);var checked=baseline.validateSuite(s.suiteManifest());assertThat(checked.valid()).as(checked.findings().toString()).isTrue();
            var plan=new V03ExecutionPlanBuilder(baseline,s.contractDirectory()).build(s.suiteManifest(),"local",checked);assertThat(plan.steps()).hasSize(3);
            assertThat(root.resolve("env_profiles")).doesNotExist();assertThat(root.resolve("execution_profiles")).doesNotExist();assertThat(root.resolve("environment_bindings")).doesNotExist();assertThat(sha(s.suiteManifest())).isEqualTo(manifest);assertThat(sha(root.resolve("config/local.yaml"))).isEqualTo(profile);
        }
    }
    @Test void defaultAndPathOnlyConstructorsKeepConventionalRouting()throws Exception{
        try(Snapshot s=snapshot()){var checked=new ContractBaselineService(s.contractDirectory()).validateSuite(s.suiteManifest());assertThat(checked.valid()).isFalse();assertThat(checked.findings().toString()).contains("missing_required_directory","execution_profiles","environment_bindings");}
    }
    @ParameterizedTest @ValueSource(strings={"profile","manifest","case","oracle","contract","profile_missing","profile_symlink","wrong_manifest","root_replaced"})
    void alteredVerifiedClosureCannotBecomeAProfileCapability(String kind)throws Exception{
        try(Snapshot s=snapshot()){
            Path root=s.suiteManifest().getParent(),moved=null;
            try{
                switch(kind){
                    case "profile"->Files.writeString(root.resolve("config/local.yaml"),"changed");case "manifest"->Files.writeString(s.suiteManifest(),"changed");case "case"->Files.writeString(root.resolve("cases/total.yaml"),"changed");case "oracle"->Files.writeString(root.resolve("data/independent-oracle.json"),"changed");case "contract"->Files.writeString(s.contractDirectory().resolve("rest.yaml"),"changed");case "profile_missing"->Files.delete(root.resolve("config/local.yaml"));
                    case "profile_symlink"->{Path external=temporary.resolve("unapproved-profile.yaml");Files.writeString(external,"unapproved");Files.delete(root.resolve("config/local.yaml"));Files.createSymbolicLink(root.resolve("config/local.yaml"),external);}
                    case "root_replaced"->{moved=root.resolveSibling(root.getFileName()+"-moved");Files.move(root,moved);Files.createDirectory(root);try(var files=Files.walk(moved)){for(Path p:files.toList()){Path target=root.resolve(moved.relativize(p));if(Files.isDirectory(p))Files.createDirectories(target);else Files.copy(p,target);}}}
                    default->{}
                }
                assertThatThrownBy(()->selected(s).validateSuite(kind.equals("wrong_manifest")?root.resolve("other.yaml"):s.suiteManifest())).isInstanceOf(Exception.class);
            }finally{if(moved!=null)remove(moved);}
        }
    }
    @Test void closedSnapshotCannotBeRecreatedAsAnAuthority()throws Exception{
        Snapshot s=snapshot();Path root=s.suiteManifest().getParent();s.close();Files.createDirectory(root);
        try{assertThatThrownBy(()->selected(s).validateSuite(s.suiteManifest())).isInstanceOf(Exception.class);}finally{remove(root);}
    }
    @Test void returnedSelectedProfileAndNestedBindingsAreImmutable()throws Exception{
        try(Snapshot s=snapshot()){
            var method=Snapshot.class.getMethod("verifiedSelectedEnvProfiles",Path.class);Map<Path,Map<String,Object>> profiles=(Map<Path,Map<String,Object>>)method.invoke(s,s.suiteManifest());assertThat(profiles).hasSize(1);Map<String,Object> profile=profiles.values().iterator().next();assertThatThrownBy(()->profiles.clear()).isInstanceOf(UnsupportedOperationException.class);assertThatThrownBy(()->profile.put("profile_id","wrong")).isInstanceOf(UnsupportedOperationException.class);Map nested=(Map)((Map)profile.get("targets")).get("public_api");assertThatThrownBy(()->nested.put("runtime_mode","stub")).isInstanceOf(UnsupportedOperationException.class);
        }
    }
    private static String sha(Path p)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));}
    private static void remove(Path root)throws Exception{try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
}
