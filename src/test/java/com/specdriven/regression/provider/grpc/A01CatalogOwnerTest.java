package com.specdriven.regression.provider.grpc;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
/** Unit-only fake Process checks; never substituted for a real catalog or counted as application evidence. */
class A01CatalogOwnerTest {
    @TempDir Path root;
    static final class Child extends Process {
        boolean alive=true;int waits,destroys;
        public java.io.OutputStream getOutputStream(){return java.io.OutputStream.nullOutputStream();}
        public java.io.InputStream getInputStream(){return java.io.InputStream.nullInputStream();}
        public java.io.InputStream getErrorStream(){return java.io.InputStream.nullInputStream();}
        public int waitFor(){waits++;alive=false;return 0;}
        public boolean waitFor(long time,TimeUnit unit){waits++;alive=false;return true;}
        public int exitValue(){if(alive)throw new IllegalThreadStateException();return 0;}
        public void destroy(){destroys++;alive=false;}
        public Process destroyForcibly(){destroy();return this;}
        public boolean isAlive(){return alive;}
        public long pid(){return 123;}
    }
    private A01CatalogOwner.Lifecycle lifecycle(Child child,long milliseconds)throws Exception {Path log=root.resolve("child.log");Files.writeString(log,"");return new A01CatalogOwner.Lifecycle(child,root,log,"1".repeat(32),milliseconds);}
    @Test void deadlineDoesNotDependOnStdin()throws Exception {var child=new Child();var state=lifecycle(child,0);assertThrows(IllegalArgumentException.class,state::check);state.fail("DEADLINE");state.cleanup();assertTrue(state.finished.get());assertFalse(child.alive);assertTrue(child.waits>0);assertFalse(state.success);}
    @Test void missingLogObservationRemainsFailureAfterCleanup()throws Exception {var child=new Child();var state=lifecycle(child,180000);Files.delete(root.resolve("child.log"));assertThrows(Exception.class,state::check);state.fail("GUARD_OBSERVATION_UNKNOWN");state.cleanup();assertEquals("GUARD_OBSERVATION_UNKNOWN",state.failure.get());assertFalse(state.success);}
    @Test void cleanupIsIdempotentAndReaps()throws Exception {var child=new Child();var state=lifecycle(child,180000);state.cleanup();int waits=child.waits;state.cleanup();assertEquals(waits,child.waits);assertEquals(1,child.destroys);assertTrue(Files.exists(root.resolve("owner-teardown.json")));}
    @Test void shutdownIsNeverReclassifiedAsGracefulStop()throws Exception {var state=lifecycle(new Child(),180000);state.fail("JVM_SHUTDOWN");state.cleanup();state.fail("later stop");var receipt=ReviewedA01CatalogPolicy.JSON.readTree(root.resolve("owner-teardown.json").toFile());assertTrue(receipt.path("guard_failure").asBoolean());assertFalse(receipt.path("graceful").asBoolean());assertEquals("JVM_SHUTDOWN",receipt.path("failure_reason").asText());}
    @Test void unobservedSocketRemainsUnknown()throws Exception {var state=lifecycle(new Child(),180000);state.cleanup();var receipt=ReviewedA01CatalogPolicy.JSON.readTree(root.resolve("owner-teardown.json").toFile());assertEquals("UNKNOWN",receipt.path("socket_state").asText());assertFalse(state.success);}
}
