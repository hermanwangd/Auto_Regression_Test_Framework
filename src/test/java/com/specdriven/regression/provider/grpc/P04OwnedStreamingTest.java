package com.specdriven.regression.provider.grpc;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class P04OwnedStreamingTest {
 @TempDir Path temporary;
 @Test void originalProviderRequiresRealLeaseAndRejectsStreamingBeforeChannel()throws Exception {
  Path evidence=temporary.resolve("owned");
  P04OwnedStreamingProbe.main(new String[]{evidence.toString()});
  P04EvidenceBoundaryProbe.main(new String[]{Files.readString(evidence.resolve("result-path.txt")),evidence.resolve("suite").toString()});
 }
}
