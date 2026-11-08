package com.specdriven.regression.provider.jdbc;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
class PinnedMariaDbDriverConfinementTest {
 @Test void cachedRealDriverInitializesOnlyInOwnedShortLivedChildWithoutConnecting()throws Exception {
  String asset=System.getProperty("reviewed.mariadb.driver.test");Assumptions.assumeTrue(asset!=null,"external cached-driver path is a bounded test binding");
  String id=UUID.randomUUID().toString();var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx64m","-XX:-HeapDumpOnOutOfMemoryError","-XX:ErrorFile=/dev/null","-Dswarm.jdbc.invocation_id="+id,"-cp",System.getProperty("java.class.path"),InitChild.class.getName(),asset).start();
  long pid=process.pid();
  try {
   var read=new java.util.concurrent.FutureTask<String>(()->new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream())).readLine());
   Thread reader=new Thread(read,"pinned-driver-ready");reader.setDaemon(true);reader.start();String ready=read.get(3,TimeUnit.SECONDS);
   var start=process.info().startInstant().orElseThrow();
   assertThat(ready).isEqualTo("PINNED_DRIVER_CHILD_READY "+id+" "+pid+" "+start);
   process.getOutputStream().close();assertThat(process.waitFor(8,TimeUnit.SECONDS)).isTrue();String output=new String(process.getInputStream().readNBytes(1025),java.nio.charset.StandardCharsets.UTF_8);
   assertThat(process.exitValue()).as(output+new String(process.getErrorStream().readNBytes(1025))).isZero();assertThat(output).isEqualTo("PINNED_DRIVER_INITIALIZED_WITHOUT_CONNECT\n");
   assertThat(process.isAlive()).isFalse();assertThat(ProcessHandle.of(pid).map(p->p.isAlive()&&p.info().startInstant().filter(start::equals).isPresent()).orElse(false)).isFalse();
  } finally {if(process.isAlive())process.destroyForcibly();assertThat(process.waitFor(2,TimeUnit.SECONDS)).isTrue();process.getOutputStream().close();process.getInputStream().close();process.getErrorStream().close();}
 }

 public static class InitChild {
  public static void main(String[] args)throws Exception {
   String nonce=System.getProperty("swarm.jdbc.invocation_id");
   if(!java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-Dswarm.jdbc.invocation_id="+nonce))System.exit(4);
   System.out.println("PINNED_DRIVER_CHILD_READY "+nonce+" "+ProcessHandle.current().pid()+" "+ProcessHandle.current().info().startInstant().orElseThrow());System.out.flush();System.in.read();
   try {
    Class<?> factory=Class.forName("com.specdriven.regression.provider.jdbc.PinnedMariaDbDriver");
    try(var handle=(AutoCloseable)factory.getMethod("open",Path.class).invoke(null,Path.of(args[0]))) {System.out.println("PINNED_DRIVER_INITIALIZED_WITHOUT_CONNECT");}
   } catch(Exception e) {System.out.println("PINNED_DRIVER_INITIALIZATION_UNAVAILABLE");System.exit(3);}
  }
 }
}
