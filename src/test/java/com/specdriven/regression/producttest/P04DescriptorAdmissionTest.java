package com.specdriven.regression.producttest;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class P04DescriptorAdmissionTest {
    @TempDir Path temporary;
    @Test void preservesUnaryGuardsAndAdmitsOnlyTheSeparateDescriptorCapability() { P04DescriptorAdmissionProbe.main(new String[0]); }
    @Test void realRejectionAndCanonicalProofRejectMutationsWithoutNetwork() throws Exception { P04DescriptorAdmissionBehaviorProbe.main(new String[]{temporary.toString()}); }
}
