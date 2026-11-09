package com.featuredeliveryintelligence.fdi.producttest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
class GrpcFourCardinalityProofTest {
 @ParameterizedTest @ValueSource(strings={"DoThing","DoManyThings","GetThingVersions","GetDifferentThings"})
 void independentStrictProfileConsumerRejectsMutations(String method)throws Exception {assertEquals(48,GrpcFourCardinalityProofProbe.synthetic(method));}
}
