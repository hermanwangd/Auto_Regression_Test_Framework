package com.specdriven.regression.cli;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Public CLI contracts over synthetic reviewed metadata; preparation never approves or runs the suite. */
class PrepareHandoffCliBoundaryTest {
    @TempDir Path temp;
    private static final ObjectMapper JSON=new ObjectMapper();
    private record Result(int exit,String stdout,String stderr){}
    private Result cli(Map<String,String> environment,String...args){
        var out=new ByteArrayOutputStream();var err=new ByteArrayOutputStream();
        int exit=new RegressionCommand(new ProductRepoService(),new ReleasePackageService(),environment)
                .execute(args,new PrintStream(out),new PrintStream(err));
        return new Result(exit,out.toString(),err.toString());
    }
    private Path fixture()throws Exception {
        Path source=Path.of("samples/41-product-testing/reviewed_local"),root=Files.createDirectory(temp.resolve("suite")).toRealPath();
        try(var paths=Files.walk(source)){for(Path path:paths.toList()){
            Path target=root.resolve(source.relativize(path));if(Files.isDirectory(path))Files.createDirectories(target);else Files.copy(path,target);
        }}
        Path review=root.resolve("review-input.json");ObjectNode input=(ObjectNode)JSON.readTree(review.toFile());Instant now=Instant.now();
        input.put("reviewed_at",now.minusSeconds(1).toString()).put("valid_until",now.plusSeconds(300).toString());JSON.writeValue(review.toFile(),input);
        Files.deleteIfExists(root.resolve("handoff/reviewed-tests.json"));Files.deleteIfExists(root.resolve("handoff/reviewed-tests.json.sha256"));return root;
    }
    private String[] prepare(Path root){return new String[]{"prepare-product-test-handoff","--root",root.toString(),"--suite","suite_manifest.yaml","--profile","local_v03","--review-input","review-input.json","--out","handoff/reviewed-tests.json"};}
    private static String sha(Path file)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
    private static Map<String,String> files(Path root)throws Exception {
        Map<String,String> result=new TreeMap<>();try(var paths=Files.walk(root)){for(Path path:paths.filter(Files::isRegularFile).toList())result.put(root.relativize(path).toString(),sha(path));}return result;
    }
    private static Map<String,String> fields(String text){
        Map<String,String> result=new LinkedHashMap<>();for(String line:text.lines().toList()){
            int split=line.indexOf(": ");assertThat(split).as(line).isPositive();assertThat(result.put(line.substring(0,split),line.substring(split+2))).isNull();
        }return result;
    }
    private static void noExecutionArtifacts(Path root){assertThat(root.resolve("runs")).doesNotExist();assertThat(root.resolve("target")).doesNotExist();}

    @Test void preparesReviewedClosureViaPublicCli()throws Exception {
        Path root=fixture();Map<String,String> before=files(root);Result result=cli(Map.of(),prepare(root));
        assertThat(result.exit()).as(result.stderr()).isZero();assertThat(result.stderr()).isEmpty();Map<String,String> output=fields(result.stdout());
        assertThat(output).containsOnlyKeys("preparation_status","approval_status","status","handoff_path","sha256_path","sha256");
        assertThat(output).containsEntry("preparation_status","prepared").containsEntry("approval_status","not_approved").containsEntry("status","prepared_not_approved");
        Path handoff=root.resolve("handoff/reviewed-tests.json"),pin=root.resolve("handoff/reviewed-tests.json.sha256");
        assertThat(output).containsEntry("handoff_path",handoff.toString()).containsEntry("sha256_path",pin.toString()).containsEntry("sha256",sha(handoff));assertThat(Files.readString(pin)).isEqualTo(sha(handoff)+"\n");
        var document=JSON.readTree(handoff.toFile());assertThat(document.path("contract_version").asText()).isEqualTo("product-test-reviewed-handoff.v1");assertThat(document.path("profile").asText()).isEqualTo("local_v03");assertThat(document.path("knowledge_mode").asText()).isEqualTo("not_used");
        for(String section:List.of("reviewed_files","reviewed_contracts"))for(var reviewed:document.path(section))assertThat(reviewed.path("sha256").asText()).isEqualTo(sha(root.resolve(reviewed.path("path").asText())));
        Map<String,String> after=files(root);before.forEach((path,digest)->assertThat(after.get(path)).as(path).isEqualTo(digest));Set<String> added=new TreeSet<>(after.keySet());added.removeAll(before.keySet());assertThat(added).containsExactlyInAnyOrder("handoff/reviewed-tests.json","handoff/reviewed-tests.json.sha256");noExecutionArtifacts(root);
    }
    @ParameterizedTest @ValueSource(strings={"--suite","--profile","--review-input","--out"})
    void rejectsMissingPrepareInputBeforeExecution(String missing)throws Exception {
        Path root=fixture();Map<String,String> before=files(root);List<String> args=new ArrayList<>(List.of(prepare(root)));int index=args.indexOf(missing);args.remove(index);args.remove(index);
        Result result=cli(Map.of(),args.toArray(String[]::new));assertThat(result.exit()).isEqualTo(2);assertThat(result.stdout()).isEmpty();assertThat(result.stderr()).contains("Missing required option: "+missing);assertThat(files(root)).isEqualTo(before);noExecutionArtifacts(root);
    }
    @ParameterizedTest @ValueSource(strings={"malformed_review","forbidden_receipt"})
    void rejectsInvalidReviewedPrepareInputWithoutPublishing(String mode)throws Exception {
        Path root=fixture();List<String> args=new ArrayList<>(List.of(prepare(root)));
        if(mode.equals("malformed_review"))Files.writeString(root.resolve("review-input.json"),"{");else args.addAll(List.of("--selection-receipt","handoff/selection-receipt.json"));
        Map<String,String> before=files(root);Result result=cli(Map.of(),args.toArray(String[]::new));assertThat(result.exit()).isEqualTo(1);assertThat(result.stdout()).isEmpty();assertThat(result.stderr()).contains("preparation_status: blocked","owner_action: Correct the reviewed input bundle:").contains(mode.equals("malformed_review")?"invalid_json":"not_used_knowledge_fields");assertThat(files(root)).isEqualTo(before);noExecutionArtifacts(root);
    }
    @Test void reportsInvalidCanonicalInputAtPublicBoundary()throws Exception {
        Path invalid=temp.resolve("result.json");Files.writeString(invalid,"{");String before=sha(invalid);Result result=cli(Map.of(),"report","--root",temp.toString(),"--result","result.json","--format","json");
        assertThat(result.exit()).isEqualTo(1);assertThat(result.stderr()).isEmpty();var output=JSON.readTree(result.stdout());assertThat(output.path("report_status").asText()).isEqualTo("failed");assertThat(output.path("failure_code").asText()).isEqualTo("VALIDATION_INVALID_RESULT_JSON");assertThat(output.path("findings").size()).isPositive();assertThat(sha(invalid)).isEqualTo(before);noExecutionArtifacts(temp);
    }
    @ParameterizedTest @ValueSource(strings={"missing","non_map"})
    void reviewedRunRejectsUnavailableManifestBeforeProviderDispatch(String kind)throws Exception {
        if(kind.equals("non_map"))Files.writeString(temp.resolve("suite.yaml"),"- not-a-suite-map\n");Map<String,String> before=files(temp);
        Result result=cli(Map.of("REGRESS_REVIEWED_HANDOFF_SHA256","a".repeat(64)),"run","--root",temp.toString(),"--suite","suite.yaml","--profile","local_v03");
        assertThat(result.exit()).isEqualTo(1);assertThat(result.stderr()).isEmpty();assertThat(result.stdout()).contains("run_status: blocked","provider_runtime_invoked: false","failure_code: REVIEWED_HANDOFF_REQUIRED");assertThat(files(temp)).isEqualTo(before);noExecutionArtifacts(temp);
    }
}
