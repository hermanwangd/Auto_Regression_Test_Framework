package com.specdriven.regression.provider.jdbc;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
/** Event-controlled synthetic RAM only; no process, endpoint, driver or real credential. */
class JdbcLateCredentialOwnershipTest {
 static final String ID="69e168da-0c39-4c55-9dc3-e6380885c768",H="a".repeat(64),L="b".repeat(64),CANARY="PUBLIC_SYNTHETIC_CLOSE_CANARY_123456";
 @Test void lateDecodeAfterTimeoutCleanupIsClosedRatherThanPublished()throws Exception {late(false);}
 @Test void interruptedReaderStillAbandonsLateDecodeAndPreservesInterruption()throws Exception {late(true);}
 void late(boolean interrupt)throws Exception {
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var decoded=new AtomicReference<JdbcCredentialLease>();var worker=new AtomicReference<Thread>();var failure=new AtomicReference<Throwable>();var interrupted=new AtomicBoolean();
  Thread caller=new Thread(()->{try{JdbcCredentialFrame.readDecoded(InputStream.nullInputStream(),System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(interrupt?2000:50),()->{worker.set(Thread.currentThread());entered.countDown();while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}var lease=new JdbcCredentialLease("public_probe".toCharArray(),CANARY.toCharArray());decoded.set(lease);return lease;});}catch(Throwable e){failure.set(e);}finally{interrupted.set(Thread.currentThread().isInterrupted());}},"synthetic-frame-caller");
  caller.start();assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();if(interrupt)caller.interrupt();caller.join(1500);assertThat(caller.isAlive()).isFalse();assertThat(failure.get()).isNotNull();release.countDown();worker.get().join(1000);assertThat(worker.get().isAlive()).isFalse();assertThat(decoded.get()).isNotNull();assertThatThrownBy(()->decoded.get().copyPassword()).hasMessageContaining("closed");var owned=JdbcCredentialLease.class.getDeclaredField("password");owned.setAccessible(true);assertThat((char[])owned.get(decoded.get())).containsOnly((char)0);if(interrupt)assertThat(interrupted).isTrue();
 }
 @Test void closeErrorIsContainedAndCleanupCannotBeConfirmed()throws Exception {
  byte[] bytes=JdbcCredentialFrame.encode(ID,H,L,"public_probe".toCharArray(),CANARY.toCharArray());var escaped=new AtomicReference<Throwable>();var failure=new AtomicReference<Throwable>();var delivered=new AtomicReference<JdbcCredentialLease>();
  ThreadGroup group=new ThreadGroup("synthetic-close-group"){@Override public void uncaughtException(Thread thread,Throwable error){escaped.set(error);}};
  Thread caller=new Thread(group,()->{try{delivered.set(JdbcCredentialFrame.read(new ByteArrayInputStream(bytes){@Override public void close(){throw new AssertionError(CANARY);}},ID,H,L,System.nanoTime()+TimeUnit.SECONDS.toNanos(2)));}catch(Throwable e){failure.set(e);}},"synthetic-close-caller");caller.start();caller.join(1500);if(delivered.get()!=null)delivered.get().close();assertThat(caller.isAlive()).isFalse();assertThat(escaped.get()).as("close callback cannot escape to an output handler").isNull();assertThat(failure.get()).isInstanceOf(IllegalStateException.class).hasMessageContaining("cleanup unconfirmed").hasMessageNotContaining(CANARY);
 }
 @Test void normalDecodedLeaseTransfersToExactlyOneCaller()throws Exception {var lease=new JdbcCredentialLease("public_probe".toCharArray(),CANARY.toCharArray());var result=JdbcCredentialFrame.readDecoded(InputStream.nullInputStream(),System.nanoTime()+TimeUnit.SECONDS.toNanos(1),()->lease);assertThat(result).isSameAs(lease);assertThat(result.copyUsername()).containsExactly("public_probe".toCharArray());result.close();assertThatThrownBy(result::copyPassword).hasMessageContaining("closed");}
}
