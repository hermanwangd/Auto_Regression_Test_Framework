package com.specdriven.regression.provider.jdbc;
import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** Public synthetic sentinels only. No driver, endpoint, real credential or database. */
class ReviewedJdbcOfflinePrimitivesTest {
 static final String ID="69e168da-0c39-4c55-9dc3-e6380885c768", HAND="a".repeat(64), LEASE="b".repeat(64), USER="public_probe", PASSWORD="PUBLIC_SYNTHETIC_PIPE_SENTINEL_123456";
 Class<?> type(String suffix){try{return Class.forName("com.specdriven.regression.provider.jdbc."+suffix);}catch(ClassNotFoundException e){throw new AssertionError("Missing protected JDBC behavior: "+suffix,e);}}
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
 @Test void oneMonotonicBudgetDoesNotResetAndCleanupCannotExceedSevenSeconds()throws Exception {
  Class<?> c=type("JdbcDeadline");long t=System.nanoTime()-TimeUnit.SECONDS.toNanos(4);Object d=c.getConstructor(long.class).newInstance(t);
  long remaining=(long)c.getMethod("remainingNanos").invoke(d);assertThat(remaining).isPositive().isLessThanOrEqualTo(TimeUnit.SECONDS.toNanos(1));
  long cleanup=(long)c.getMethod("cleanupDeadlineNanos",long.class).invoke(d,t+TimeUnit.SECONDS.toNanos(6));assertThat(cleanup).isEqualTo(t+TimeUnit.SECONDS.toNanos(7));
  Object expired=c.getConstructor(long.class).newInstance(System.nanoTime()-TimeUnit.SECONDS.toNanos(6));assertThatThrownBy(()->c.getMethod("remainingNanos").invoke(expired)).isInstanceOf(InvocationTargetException.class);
 }
}
