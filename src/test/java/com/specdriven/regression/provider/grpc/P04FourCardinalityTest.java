package com.specdriven.regression.provider.grpc;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class P04FourCardinalityTest {
 @TempDir Path temporary;
 @Test void ownedFourCardinalitiesHaveRealUnaryAndThreeNoChannelRejections()throws Exception {P04FourCardinalityProbe.main(new String[]{temporary.resolve("four").toString()});}
}
