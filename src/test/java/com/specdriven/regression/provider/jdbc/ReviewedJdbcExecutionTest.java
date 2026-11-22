package com.specdriven.regression.provider.jdbc;
import static org.assertj.core.api.Assertions.*;
import com.specdriven.regression.provider.runtime.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
/** Driver/SQL boundary doubles only. No real Driver.connect, socket, credential or backend. */
class ReviewedJdbcExecutionTest {
 @TempDir Path root;Path runDir;
 final AtomicInteger connects=new AtomicInteger(),sql=new AtomicInteger(),closed=new AtomicInteger(),sources=new AtomicInteger();
 final java.util.concurrent.CountDownLatch cleanupStarted=new java.util.concurrent.CountDownLatch(1);
 final String sentinel="PUBLIC_SYNTHETIC_PIPE_SENTINEL_123456";
 ReviewedJdbcExecution runtime(String mode) {return runtime(mode,"swarm-probe");}
 ReviewedJdbcExecution runtime(String mode,String publicValue) {
  Driver driver=(Driver)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Driver.class},(p,m,a)->{
   if(!m.getName().equals("connect"))throw new UnsupportedOperationException();connects.incrementAndGet();
   if(mode.startsWith("late_connect_close_")){long end=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(6500);while(cleanupStarted.getCount()>0&&System.nanoTime()<end)try{cleanupStarted.await(10,java.util.concurrent.TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){}if(cleanupStarted.getCount()>0)throw new SQLException("PUBLIC_SYNTHETIC_TEST_CONTROL_TIMEOUT","08000",0);}
   if(mode.startsWith("slow_connect")){long stop=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(6100);while(System.nanoTime()<stop)try{Thread.sleep(5);}catch(InterruptedException ignored){}}
   if(mode.equals("connect_failure"))throw new SQLTimeoutException(sentinel,"08000",0);
   return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(cp,cm,ca)->switch(cm.getName()){
    case "setReadOnly"->{assertThat(ca[0]).isEqualTo(true);yield null;}
    case "prepareStatement"->{assertThat(ca[0]).isEqualTo(ReviewedOwnedMariaDbPolicy.SQL);yield Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(sp,sm,sa)->switch(sm.getName()){
     case "setMaxRows"->{assertThat(sa[0]).isEqualTo(3);yield null;}case "setQueryTimeout"->{assertThat((int)sa[0]).isBetween(1,2);yield null;}
     case "executeQuery"->{sql.incrementAndGet();yield ReviewedJdbcResultTest.rows(List.of(Arrays.asList(1,publicValue,2L),Arrays.asList(2,null,2L)),Types.BIGINT);}
     case "close"->{closed.incrementAndGet();yield null;}default->throw new UnsupportedOperationException(sm.getName());});}
    case "close","abort"->{closed.incrementAndGet();if(mode.equals("blocked_close")){long stop=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(4);while(System.nanoTime()<stop)try{Thread.sleep(5);}catch(InterruptedException ignored){}}if(mode.equals("close_error")||mode.equals("late_connect_close_error"))throw new AssertionError(sentinel);if(mode.equals("close_failure")||mode.equals("slow_connect_close_failure")||mode.startsWith("late_connect_close_"))throw new SQLException(sentinel);yield null;}
    default->throw new UnsupportedOperationException(cm.getName());});
  });
  return new ReviewedJdbcExecution(new ReviewedJdbcExecution.DriverFactory(){public void admit(Path path){}public ReviewedJdbcExecution.DriverHandle open(Path path){return new ReviewedJdbcExecution.DriverHandle(){public Driver driver(){return driver;}public void close(){closed.incrementAndGet();if(mode.startsWith("late_connect_close_"))cleanupStarted.countDown();}};}},(path,b)->new ReviewedOwnedMariaDbPolicy.Fixture(Map.of("owner_run_id","a".repeat(32),"endpointIPv4","172.17.0.2","schema","swarm_probe","query_sha256",ReviewedJdbcProof.querySha(),"driver_sha256",ReviewedOwnedMariaDbPolicy.DRIVER_SHA,"profile",ReviewedOwnedMariaDbPolicy.PROFILE),"b".repeat(64)));
 }
 ReviewedJdbcInvocation invocation(long started) {return invocation(started,"public_probe",sentinel);}
 ReviewedJdbcInvocation invocation(long started,String username,String password) {return new ReviewedJdbcInvocation(root.resolve("external-pinned.jar"),(request,deadline)->{sources.incrementAndGet();return new JdbcCredentialLease(username.toCharArray(),password.toCharArray());},new JdbcCredentialSource.CredentialRequest("69e168da-0c39-4c55-9dc3-e6380885c768","a".repeat(64),"b".repeat(64)),started);}
 ProviderExecutionContext context(){return new ProviderExecutionContext("owned_db","jdbc","local","native",root,runDir==null?root:runDir,Map.of(),Map.of(),Map.of("dialect","mariadb","profile",ReviewedOwnedMariaDbPolicy.PROFILE,"connection.secret_ref",ReviewedOwnedMariaDbPolicy.SECRET_REF,"endpoint","172.17.0.2:3306","owner_run_id","a".repeat(32)));}
 ProviderOperationResult call(ReviewedJdbcExecution runner,ReviewedJdbcInvocation input){var observed=new java.util.concurrent.atomic.AtomicReference<ProviderOperationResult>();assertThatCode(()->observed.set(runner.execute(context(),request(),input))).doesNotThrowAnyException();return observed.get();}
 ProviderOperationRequest request(){return new ProviderOperationRequest("db_query",List.of(Map.of("bind_as","query_ref","ref",ReviewedOwnedMariaDbPolicy.QUERY_PATH)),Map.of());}
 @Test void fixedSelectHasOneSyntheticConnectTypedNullAndCheckedClosure()throws Exception {
  var result=call(runtime("ok"),invocation(System.nanoTime()));assertThat(result.passed()).as(String.valueOf(result.failure())).isTrue();assertThat(connects).hasValue(1);assertThat(sql).hasValue(1);assertThat(sources).hasValue(1);assertThat(closed.get()).isGreaterThanOrEqualTo(3);assertThat(result.outputs().get("result").toString()).contains("null_columns","total_rows").doesNotContain(sentinel);
  var json=new com.fasterxml.jackson.databind.ObjectMapper();var proof=json.readTree(Files.readAllBytes(root.resolve(ReviewedJdbcProof.PATH)));var lease=json.createObjectNode().put("version","reviewed-mariadb-lease.v1").put("profile",ReviewedOwnedMariaDbPolicy.PROFILE).put("owner_run_id","a".repeat(32)).put("container_id","b".repeat(64)).put("image_digest",ReviewedOwnedMariaDbPolicy.IMAGE_SHA).put("container_started_at","2000-01-01T00:00:00Z").put("owner_pid",2).put("owner_start_instant","2000-01-01T00:00:00Z").put("db_pid",3).put("db_start_instant","2000-01-01T00:00:00Z").put("endpointIPv4","172.17.0.2").put("port",3306).put("schema","swarm_probe").put("query_sha256",ReviewedJdbcProof.querySha()).put("driver_sha256",ReviewedOwnedMariaDbPolicy.DRIVER_SHA).put("issued_at",java.time.Instant.now().minusSeconds(60).toString()).put("expires_at",java.time.Instant.now().plusSeconds(60).toString());
  assertThatCode(()->ReviewedJdbcProof.validate(lease,"b".repeat(64),proof,true,result.outputs().get("result").toString(),"owned_db","db_query",java.time.Instant.parse(proof.path("operation_started_at").textValue()),java.time.Instant.parse(proof.path("operation_ended_at").textValue()))).doesNotThrowAnyException();
 }
 @Test void actualConnectionFailureRetainsTypedConnectStageAndNoSqlWithoutRawError()throws Exception {
  var result=call(runtime("connect_failure"),invocation(System.nanoTime()));assertThat(result.passed()).isFalse();assertThat(connects).hasValue(1);assertThat(sql).hasValue(0);String proof=Files.readString(root.resolve(ReviewedJdbcProof.PATH));assertThat(proof).contains("08000","CONNECT_FAILED").doesNotContain(sentinel);
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"close_failure","close_error"}) void cleanupFailureNeverPromotesRawSuccessfulQueryToPass(String mode)throws Exception {
  var result=call(runtime(mode),invocation(System.nanoTime()));assertThat(result.passed()).isFalse();assertThat(Files.readString(root.resolve(ReviewedJdbcProof.PATH))).contains("CLEANUP_UNCONFIRMED").doesNotContain(sentinel);assertThat(closed.get()).isGreaterThanOrEqualTo(3);
 }
 @Test void expiredSharedBudgetAndReplayNeverMakeASecondConnect()throws Exception {
  assertThatCode(()->runtime("ok").execute(context(),request(),invocation(System.nanoTime()-java.util.concurrent.TimeUnit.SECONDS.toNanos(6)))).doesNotThrowAnyException();assertThat(connects).hasValue(0);assertThat(sources).hasValue(0);
  runDir=root.resolve("fresh");var invocation=invocation(System.nanoTime());var runner=runtime("ok");assertThat(call(runner,invocation).passed()).isTrue();assertThat(call(runner,invocation).passed()).isFalse();assertThat(connects).hasValue(1);assertThat(sources).hasValue(1);
 }
 @Test void realBlockingConnectConsumesTheSameFiveSecondBudgetAndCheckedTail()throws Exception {
  long start=System.nanoTime();var result=call(runtime("slow_connect"),invocation(start));long millis=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
  assertThat(result.passed()).isFalse();assertThat(connects).hasValue(1);assertThat(sql).hasValue(0);assertThat(millis).isLessThan(7300);
  assertThat(Files.readString(root.resolve(ReviewedJdbcProof.PATH))).contains("DEADLINE_EXCEEDED").doesNotContain(sentinel);
 }
 @Test void realBlockingCloseIsUnprovenAndCannotBecomePass()throws Exception {
  long start=System.nanoTime();var result=call(runtime("blocked_close"),invocation(start));long millis=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
  assertThat(result.passed()).isFalse();assertThat(millis).isLessThan(2600);assertThat(Files.readString(root.resolve(ReviewedJdbcProof.PATH))).contains("CLEANUP_UNCONFIRMED").doesNotContain(sentinel);
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"late_connect_close_failure","late_connect_close_error"}) void failedCloseOfAConnectionReturningAfterDeadlineCannotClaimCleanup(String mode)throws Exception {
  var result=call(runtime(mode),invocation(System.nanoTime()));assertThat(cleanupStarted.getCount()).isZero();assertThat(closed.get()).isGreaterThanOrEqualTo(2);assertThat(result.passed()).isFalse();
  var proof=new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(root.resolve(ReviewedJdbcProof.PATH)));assertThat(proof.path("cleanup_confirmed").booleanValue()).isFalse();assertThat(proof.path("status").textValue()).isEqualTo("CLEANUP_UNCONFIRMED");
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"password_quote","password_backslash","username_quote","username_backslash","plain_password"})
 void rawCredentialEchoIsRejectedBeforeJsonCanEscapeIt(String kind)throws Exception {
  String punctuation=kind.endsWith("quote")?"\"":"\\";String username=kind.startsWith("username")?"public_"+punctuation+"probe":"public_probe";String password=kind.startsWith("password")?"PUBLIC_SYNTHETIC_"+punctuation+"_PIPE_SENTINEL_123456":sentinel;String echoed=kind.startsWith("username")?username:password;
  var result=call(runtime("ok",echoed),invocation(System.nanoTime(),username,password));assertThat(result.passed()).as("raw typed result echo must not become encoded output").isFalse();assertThat(connects).hasValue(1);assertThat(sql).hasValue(1);assertThat(closed.get()).isGreaterThanOrEqualTo(3);
  var json=new com.fasterxml.jackson.databind.ObjectMapper();var proof=json.readTree(Files.readAllBytes(root.resolve(ReviewedJdbcProof.PATH)));assertThat(proof.path("status").textValue()).isEqualTo("SECRET_OUTPUT_BLOCKED");assertThat(proof.path("result").textValue()).isEmpty();assertThat(result.outputs()).doesNotContainKey("result");
  String returned=json.writeValueAsString(result.outputs());assertNoCredentialLayer(returned,username,password,json);try(var files=Files.walk(root,5)){for(Path file:files.filter(Files::isRegularFile).toList()){assertThat(Files.size(file)).isLessThanOrEqualTo(65536);assertNoCredentialLayer(Files.readString(file),username,password,json);}}
 }
 void assertNoCredentialLayer(String text,String username,String password,com.fasterxml.jackson.databind.ObjectMapper json)throws Exception {
  for(String value:List.of(username,password)){assertThat(text.contains(value)).as("raw credential occurrence").isFalse();String encoded=json.writeValueAsString(value);encoded=encoded.substring(1,encoded.length()-1);assertThat(text.contains(encoded)).as("JSON credential occurrence").isFalse();encoded=json.writeValueAsString(encoded);encoded=encoded.substring(1,encoded.length()-1);assertThat(text.contains(encoded)).as("nested JSON credential occurrence").isFalse();}
 }
 @Test void punctuationCredentialsRemainAcceptedWhenTheTypedPublicRowsAreUnrelated()throws Exception {
  String username="public_\"\\probe",password="PUBLIC_SYNTHETIC_\"\\_PIPE_SENTINEL_123456";var result=call(runtime("ok"),invocation(System.nanoTime(),username,password));assertThat(result.passed()).as(String.valueOf(result.failure())).isTrue();assertThat(result.outputs().get("result").toString()).contains("swarm-probe","null_columns");assertNoCredentialLayer(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result.outputs()),username,password,new com.fasterxml.jackson.databind.ObjectMapper());
 }

 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={1,2})
 void knownJsonCredentialLayersAreRejectedBeforeTheirNextSerialization(int layer)throws Exception {
  var json=new com.fasterxml.jackson.databind.ObjectMapper();String password="PUBLIC_SYNTHETIC_\"\\_PIPE_SENTINEL_123456",encoded=password;for(int i=0;i<layer;i++){encoded=json.writeValueAsString(encoded);encoded=encoded.substring(1,encoded.length()-1);}var result=call(runtime("ok",encoded),invocation(System.nanoTime(),"public_probe",password));assertThat(result.passed()).as("known bounded JSON encoding remains protected").isFalse();var proof=json.readTree(Files.readAllBytes(root.resolve(ReviewedJdbcProof.PATH)));assertThat(proof.path("status").textValue()).isEqualTo("SECRET_OUTPUT_BLOCKED");assertThat(proof.path("result").textValue()).isEmpty();assertNoCredentialLayer(json.writeValueAsString(result.outputs()),"public_probe",password,json);assertNoCredentialLayer(Files.readString(root.resolve(ReviewedJdbcProof.PATH)),"public_probe",password,json);
 }

}
