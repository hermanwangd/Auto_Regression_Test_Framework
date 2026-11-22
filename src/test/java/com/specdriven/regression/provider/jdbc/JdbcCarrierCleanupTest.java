package com.specdriven.regression.provider.jdbc;
import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
class JdbcCarrierCleanupTest {
 @Test void blockingInputCloseCannotExtendTheFrameCleanupBudget() {
  InputStream input=new InputStream(){@Override public int read()throws IOException{throw new IOException("PUBLIC_SYNTHETIC_IO_ERROR");}@Override public void close(){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(System.nanoTime()<end){try{Thread.sleep(5);}catch(InterruptedException ignored){}}}};
  long t=System.nanoTime();assertThatThrownBy(()->JdbcCredentialFrame.read(input,ReviewedJdbcOfflinePrimitivesTest.ID,ReviewedJdbcOfflinePrimitivesTest.HAND,ReviewedJdbcOfflinePrimitivesTest.LEASE,t+TimeUnit.SECONDS.toNanos(5))).hasMessageContaining("cleanup unconfirmed");
  assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-t)).isLessThan(2300);
 }
}
