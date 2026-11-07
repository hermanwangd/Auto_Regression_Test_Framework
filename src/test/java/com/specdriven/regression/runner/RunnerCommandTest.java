package com.specdriven.regression.runner;
import static org.assertj.core.api.Assertions.assertThat;
import com.specdriven.regression.cli.RegressionCommand;
import com.specdriven.regression.discovery.ReleasePackageService;
import com.specdriven.regression.productrepo.ProductRepoService;
import java.io.*;
import org.junit.jupiter.api.Test;
class RunnerCommandTest {
 @Test void runnerPackageReportsMissingIndependentBindingRatherThanUnknownCommand() {
  var out=new ByteArrayOutputStream(); var err=new ByteArrayOutputStream();
  int code=new RegressionCommand(new ProductRepoService(),new ReleasePackageService()).execute(new String[]{"runner-package"},new PrintStream(out),new PrintStream(err));
  assertThat(code).isEqualTo(1); assertThat(out.toString()).contains("BLOCKED"); assertThat(err.toString()).doesNotContain("Unknown command");
 }
 @Test void remoteRunnerCannotFallBackToLocalOrMockNativePass() {
  var out=new ByteArrayOutputStream(); var err=new ByteArrayOutputStream();
  int code=new RegressionCommand(new ProductRepoService(),new ReleasePackageService()).execute(new String[]{"runner-execute"},new PrintStream(out),new PrintStream(err));
  assertThat(code).isEqualTo(1); assertThat(out.toString()).contains("BLOCKED"); assertThat(err.toString()).doesNotContain("Unknown command");
 }
 @Test void producerObservationHasNoCallerControlledArtifactPath()throws Exception{
  var out=new ByteArrayOutputStream();var err=new ByteArrayOutputStream();int code=new RunnerCommand().execute(new String[]{"runner-observe-producer"},new PrintStream(out),new PrintStream(err));assertThat(code).isZero();var d=RunnerFiles.JSON.readTree(out.toByteArray());assertThat(d.path("identity_state").asText()).isEqualTo("SOURCE_CLASSES_UNBOUND_UNTIL_ACCEPTED_D2_JAR");assertThat(d.path("version").isNull()).isTrue();
  out.reset();err.reset();code=new RunnerCommand().execute(new String[]{"runner-observe-producer","--jar","/PUBLIC_PATH_CANARY/private.jar"},new PrintStream(out),new PrintStream(err));assertThat(code).isEqualTo(1);assertThat(out.toString()+err).doesNotContain("PUBLIC_PATH_CANARY");assertThat(err.size()).isLessThan(512);
 }
 @Test void finiteDiagnosticCodesNeverEchoExceptionMessagesOrPaths(){
  for(Throwable e:java.util.List.of(new java.io.IOException("/PUBLIC_EXCEPTION_CANARY/private"),new IllegalArgumentException("PUBLIC_EXCEPTION_CANARY"),new IllegalStateException()))assertThat(RunnerDiagnostic.code(e)).matches("[A-Z_]{1,80}").doesNotContain("CANARY");
 }
}
