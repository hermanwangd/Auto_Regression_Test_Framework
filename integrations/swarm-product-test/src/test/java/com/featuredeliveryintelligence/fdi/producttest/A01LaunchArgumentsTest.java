package com.featuredeliveryintelligence.fdi.producttest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
class A01LaunchArgumentsTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;
    private static final String PIN="a".repeat(64),LEASE="b".repeat(64),PROFILE="microservices-demo.getproduct.v1";
    @Test void forwardsOnlyExactIndependentlyBoundProperty(){assertEquals(List.of("-Dswarm.grpc.a01.owner_launch_sha256="+PIN),FrameworkCliTransport.a01JavaArguments(PIN,LEASE,PROFILE,PIN));}
    @ParameterizedTest @ValueSource(strings={"", "ABCDEF", "short", "-javaagent:/tmp/other.jar"})
    void rejectsMalformedPin(String pin){assertThrows(IllegalArgumentException.class,()->FrameworkCliTransport.a01JavaArguments(pin,LEASE,PROFILE,PIN));}
    @Test void rejectsMissingRequiredCatalogPin(){assertThrows(IllegalArgumentException.class,()->FrameworkCliTransport.a01JavaArguments(null,LEASE,PROFILE,PIN));}
    @Test void rejectsMissingIndependentLease(){assertThrows(IllegalArgumentException.class,()->FrameworkCliTransport.a01JavaArguments(PIN,null,PROFILE,PIN));}
    @Test void rejectsLeaseLaunchMismatch(){assertThrows(IllegalArgumentException.class,()->FrameworkCliTransport.a01JavaArguments(PIN,LEASE,PROFILE,"c".repeat(64)));}
    @Test void preservesOldProfileArgumentListWhenAbsent(){assertEquals(List.of(),FrameworkCliTransport.a01JavaArguments(null,LEASE,"routeguide.getfeature.v1",null));}
    @Test void rejectsPinForUnrelatedProfile(){assertThrows(IllegalArgumentException.class,()->FrameworkCliTransport.a01JavaArguments(PIN,LEASE,"routeguide.getfeature.v1",PIN));}
    private java.nio.file.Path realSuite()throws Exception {
        var root=temporary.resolve("suite");java.nio.file.Files.createDirectories(root.resolve("fixtures"));
        var suite=root.resolve("suite_manifest.yaml");java.nio.file.Files.writeString(suite,"{}\n");
        java.nio.file.Files.writeString(root.resolve("fixtures/lease.json"),"{\"profile\":\""+PROFILE+"\",\"owner_launch_sha256\":\""+PIN+"\"}");
        assertNotEquals(java.nio.file.Path.of("").toAbsolutePath().normalize(),root.toAbsolutePath().normalize());
        return suite;
    }
    @Test void resolvesLeaseAgainstActualSuiteFileNotProcessCwd()throws Exception {
        var suite=realSuite();String leasePin=LocalFiles.sha256(suite.getParent().resolve("fixtures/lease.json"));
        assertEquals(List.of("-Dswarm.grpc.a01.owner_launch_sha256="+PIN),FrameworkCliTransport.reviewedA01JavaArguments(suite,PIN,leasePin));
    }
    @Test void rejectsMissingSuiteLease()throws Exception {
        var suite=realSuite();java.nio.file.Files.delete(suite.getParent().resolve("fixtures/lease.json"));
        assertThrows(java.io.IOException.class,()->FrameworkCliTransport.reviewedA01JavaArguments(suite,PIN,LEASE));
    }
    @Test void rejectsActualSuiteLeaseHashMismatch()throws Exception {
        var suite=realSuite();assertThrows(IllegalArgumentException.class,()->FrameworkCliTransport.reviewedA01JavaArguments(suite,PIN,LEASE));
    }
    @Test void rejectsEscapingSymlinkLease()throws Exception {
        var suite=realSuite();var lease=suite.getParent().resolve("fixtures/lease.json");var outside=temporary.resolve("outside-lease.json");
        java.nio.file.Files.move(lease,outside);java.nio.file.Files.createSymbolicLink(lease,outside);
        assertThrows(java.io.IOException.class,()->FrameworkCliTransport.reviewedA01JavaArguments(suite,PIN,LocalFiles.sha256(outside)));
    }
}
