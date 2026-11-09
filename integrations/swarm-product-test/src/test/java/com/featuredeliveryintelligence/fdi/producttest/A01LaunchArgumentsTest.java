package com.featuredeliveryintelligence.fdi.producttest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
class A01LaunchArgumentsTest {
    private static final String PIN="a".repeat(64),LEASE="b".repeat(64),PROFILE="microservices-demo.getproduct.v1";
    @Test void forwardsOnlyExactIndependentlyBoundProperty(){assertEquals(List.of("-Dswarm.grpc.a01.owner_launch_sha256="+PIN),FrameworkCliTransport.a01JavaArguments(PIN,LEASE,PROFILE,PIN));}
    @ParameterizedTest @ValueSource(strings={"", "ABCDEF", "short", "-javaagent:/tmp/other.jar"})
    void rejectsMalformedPin(String pin){assertThrows(IllegalArgumentException.class,()->FrameworkCliTransport.a01JavaArguments(pin,LEASE,PROFILE,PIN));}
    @Test void rejectsMissingRequiredCatalogPin(){assertThrows(IllegalArgumentException.class,()->FrameworkCliTransport.a01JavaArguments(null,LEASE,PROFILE,PIN));}
    @Test void rejectsMissingIndependentLease(){assertThrows(IllegalArgumentException.class,()->FrameworkCliTransport.a01JavaArguments(PIN,null,PROFILE,PIN));}
    @Test void rejectsLeaseLaunchMismatch(){assertThrows(IllegalArgumentException.class,()->FrameworkCliTransport.a01JavaArguments(PIN,LEASE,PROFILE,"c".repeat(64)));}
    @Test void preservesOldProfileArgumentListWhenAbsent(){assertEquals(List.of(),FrameworkCliTransport.a01JavaArguments(null,LEASE,"routeguide.getfeature.v1",null));}
    @Test void rejectsPinForUnrelatedProfile(){assertThrows(IllegalArgumentException.class,()->FrameworkCliTransport.a01JavaArguments(PIN,LEASE,"routeguide.getfeature.v1",PIN));}
}
