package com.featuredeliveryintelligence.fdi.producttest;
import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** Public synthetic sentinels only. No driver, endpoint, real credential or database. */
class ReviewedJdbcChannelTest {
 @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;
 static final String ID="69e168da-0c39-4c55-9dc3-e6380885c768", HAND="a".repeat(64), LEASE="b".repeat(64), USER="public_probe", PASSWORD="PUBLIC_SYNTHETIC_PIPE_SENTINEL_123456";
 Class<?> type(String suffix){try{return Class.forName("com.featuredeliveryintelligence.fdi.producttest."+suffix);}catch(ClassNotFoundException e){throw new AssertionError("Missing protected JDBC behavior: "+suffix,e);}}
 Object invoke(String name,Class<?>[] signature,Object...args)throws Exception {try{return type("JdbcCredentialFrame").getMethod(name,signature).invoke(null,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception x)throw x;throw e;}}
 byte[] frame()throws Exception{return (byte[])invoke("encode",new Class<?>[]{String.class,String.class,String.class,char[].class,char[].class},ID,HAND,LEASE,USER.toCharArray(),PASSWORD.toCharArray());}
 Object read(InputStream input,String id,String hand,String lease,long deadline)throws Exception{return invoke("read",new Class<?>[]{InputStream.class,String.class,String.class,String.class,long.class},input,id,hand,lease,deadline);}
 @Test void actualAnonymousPipeCarriesBoundIdentityAndClearsOwnedLease()throws Exception {
  byte[] bytes=frame();var in=new PipedInputStream(1024);var out=new PipedOutputStream(in);
  Thread writer=new Thread(()->{try(out){out.write(bytes);}catch(IOException e){throw new AssertionError(e);}finally{Arrays.fill(bytes,(byte)0);}});writer.start();
  Object lease=read(in,ID,HAND,LEASE,System.nanoTime()+TimeUnit.SECONDS.toNanos(5));writer.join(2000);assertThat(writer.isAlive()).isFalse();
  assertThat((char[])lease.getClass().getMethod("copyUsername").invoke(lease)).containsExactly(USER.toCharArray());
  assertThat((char[])lease.getClass().getMethod("copyPassword").invoke(lease)).containsExactly(PASSWORD.toCharArray());
  assertThat(lease.toString()).doesNotContain(USER,PASSWORD);((AutoCloseable)lease).close();
  assertThatThrownBy(()->lease.getClass().getMethod("copyPassword").invoke(lease)).isInstanceOf(InvocationTargetException.class);
 }
 @Test void wrongInvocationHandoffLeaseAndTrailingBytesCannotResolve()throws Exception {
  byte[] bytes=frame();long d=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
  assertThatThrownBy(()->read(new ByteArrayInputStream(bytes),UUID.randomUUID().toString(),HAND,LEASE,d)).hasMessageContaining("identity");
  assertThatThrownBy(()->read(new ByteArrayInputStream(bytes),ID,"c".repeat(64),LEASE,d)).hasMessageContaining("identity");
  assertThatThrownBy(()->read(new ByteArrayInputStream(bytes),ID,HAND,"c".repeat(64),d)).hasMessageContaining("identity");
  byte[] trailing=Arrays.copyOf(bytes,bytes.length+1);assertThatThrownBy(()->read(new ByteArrayInputStream(trailing),ID,HAND,LEASE,d)).hasMessageContaining("frame");
  assertThatThrownBy(()->read(new ByteArrayInputStream(Arrays.copyOf(bytes,bytes.length-2)),ID,HAND,LEASE,d)).hasMessageContaining("frame");
  assertThatThrownBy(()->read(new ByteArrayInputStream(new byte[1025]),ID,HAND,LEASE,d)).hasMessageContaining("frame");
 }
 @Test void blockedPipeReadIsCancelledAndClosedWithinSourceAndSharedBudget()throws Exception {
  type("JdbcCredentialFrame");var in=new PipedInputStream(1024);var out=new PipedOutputStream(in);long t=System.nanoTime();
  try(out){assertThatThrownBy(()->read(in,ID,HAND,LEASE,t+TimeUnit.MILLISECONDS.toNanos(100))).hasMessageContaining("deadline");}
  assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-t)).isLessThan(2300);
 }
 @Test void sourceThrowableIsTypedAndNeverReachesAnUncaughtOutputHandler()throws Exception {
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var escaped=new java.util.concurrent.atomic.AtomicReference<Throwable>();
  var binding=new ReviewedJdbcBinding(java.nio.file.Path.of("/public-synthetic-unused-driver.jar"),"85c4ba2f221d0dfd439c26affbb294f784960763544263c65aba9c2c76858706",LEASE,(lease,request)->{},request->{entered.countDown();release.await();throw new AssertionError(PASSWORD);});
  var constructor=JdbcProcessChannel.class.getDeclaredConstructor(ReviewedJdbcBinding.class,ReviewedJdbcBinding.CredentialRequest.class,com.fasterxml.jackson.databind.JsonNode.class);constructor.setAccessible(true);
  var lease=Json.MAPPER.createObjectNode().put("issued_at",java.time.Instant.now().minusSeconds(5).toString()).put("expires_at",java.time.Instant.now().plusSeconds(60).toString());
  try(var channel=constructor.newInstance(binding,new ReviewedJdbcBinding.CredentialRequest(ID,HAND,LEASE),lease)) {
   Process observationDouble=new Process(){public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}public InputStream getInputStream(){return InputStream.nullInputStream();}public InputStream getErrorStream(){return InputStream.nullInputStream();}public int waitFor(){return 0;}public int exitValue(){return 0;}public void destroy(){}public long pid(){return ProcessHandle.current().pid();}public ProcessHandle.Info info(){return ProcessHandle.current().info();}};
   channel.start(observationDouble,System.nanoTime()+TimeUnit.SECONDS.toNanos(5));assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
   var field=JdbcProcessChannel.class.getDeclaredField("writer");field.setAccessible(true);Thread writer=(Thread)field.get(channel);writer.setUncaughtExceptionHandler((thread,failure)->escaped.set(failure));release.countDown();writer.join(1000);
   assertThat(writer.isAlive()).isFalse();assertThat(escaped.get()).as("callback errors cannot escape to output handlers").isNull();assertThatThrownBy(channel::check).hasMessage("JDBC protected owner/source/pipe failed");channel.finish(observationDouble,new Thread(),new Thread(),System.nanoTime());assertThatThrownBy(channel::requireFinished).hasMessage("JDBC child termination/pipe cleanup unconfirmed");
  } finally {release.countDown();}
 }

 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"exception","error","normal"})
 void parentPipeClosureRequiresSuccessfulClosesAsWellAsStoppedWorkers(String mode)throws Exception {
  var escaped=new java.util.concurrent.atomic.AtomicReference<Throwable>();var caught=new java.util.concurrent.atomic.AtomicReference<Throwable>();
  var b=new ReviewedJdbcBinding(java.nio.file.Path.of("/public-unused-driver.jar"),"85c4ba2f221d0dfd439c26affbb294f784960763544263c65aba9c2c76858706",LEASE,(lease,request)->{},request->null);
  var constructor=JdbcProcessChannel.class.getDeclaredConstructor(ReviewedJdbcBinding.class,ReviewedJdbcBinding.CredentialRequest.class,com.fasterxml.jackson.databind.JsonNode.class);constructor.setAccessible(true);
  try(var channel=constructor.newInstance(b,new ReviewedJdbcBinding.CredentialRequest(ID,HAND,LEASE),Json.MAPPER.createObjectNode())) {
   Process model=new Process(){public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}public InputStream getInputStream(){return new ByteArrayInputStream(new byte[0]){@Override public void close()throws IOException{if(mode.equals("error"))throw new AssertionError(PASSWORD);if(mode.equals("exception"))throw new IOException(PASSWORD);}};}public InputStream getErrorStream(){return InputStream.nullInputStream();}public int waitFor(){return 0;}public int exitValue(){return 0;}public void destroy(){}};
   ThreadGroup group=new ThreadGroup("synthetic-parent-pipes"){@Override public void uncaughtException(Thread thread,Throwable failure){escaped.set(failure);}};
   Thread caller=new Thread(group,()->{try{channel.finish(model,new Thread(),new Thread(),System.nanoTime());}catch(Throwable e){caught.set(e);}},"synthetic-parent-finish");caller.start();caller.join(1500);assertThat(caller.isAlive()).isFalse();assertThat(caught.get()).isNull();assertThat(escaped.get()).isNull();
   channel.observation(temporary);assertThat(java.nio.file.Files.readString(temporary.resolve("jdbc-child-observation.json"))).doesNotContain(PASSWORD);var observation=Json.read(temporary.resolve("jdbc-child-observation.json"));assertThat(observation.path("terminated").booleanValue()).isTrue();assertThat(observation.path("pipes_closed").booleanValue()).isEqualTo(mode.equals("normal"));
   if(mode.equals("normal"))assertThatCode(channel::requireFinished).doesNotThrowAnyException();else assertThatThrownBy(channel::requireFinished).hasMessage("JDBC child termination/pipe cleanup unconfirmed");
  }
 }

}
