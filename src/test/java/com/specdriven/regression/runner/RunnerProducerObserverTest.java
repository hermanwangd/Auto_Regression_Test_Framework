package com.specdriven.regression.runner;
import static org.assertj.core.api.Assertions.*;
import java.io.*;import java.lang.reflect.*;import java.net.*;import java.nio.file.*;import java.security.*;import java.util.*;import java.util.jar.*;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;
class RunnerProducerObserverTest {
 static final String NAME="com.specdriven.regression.runner.RunnerProducerObserver",RESOURCE="schemas/source_bindings.v1.schema.yaml";
 static final byte[] SCHEMA="{\"public_schema\":\"versioned-fixture-1\"}\n".getBytes();@TempDir Path temp;
 Class<?> subject(){Class<?> c=null;try{c=Class.forName(NAME);}catch(ClassNotFoundException missing){}assertThat(c).as("actual-CodeSource-only producer observation interface required").isNotNull();return c;}
 static String sha(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
 Path fixture(byte[] schema,boolean version,int manifestPad)throws Exception{
  Class<?> c=subject();Manifest m=new Manifest();m.getMainAttributes().putValue("Manifest-Version","1.0");if(version)m.getMainAttributes().putValue("Implementation-Version","7.8.9");m.getMainAttributes().putValue("Git-Commit","1".repeat(40));if(manifestPad>0)m.getMainAttributes().putValue("Public-Padding","x".repeat(manifestPad));
  Path jar=temp.resolve("public-observer-"+UUID.randomUUID()+".jar");try(var stream=new JarOutputStream(Files.newOutputStream(jar),m)){
   stream.putNextEntry(new JarEntry(NAME.replace('.','/')+".class"));try(var in=c.getResourceAsStream("RunnerProducerObserver.class")){assertThat(in).isNotNull();byte[] bytes=in.readNBytes(1024*1024+1);assertThat(bytes.length).isLessThanOrEqualTo(1024*1024);stream.write(bytes);}stream.closeEntry();
   if(schema!=null){stream.putNextEntry(new JarEntry(RESOURCE));stream.write(schema);stream.closeEntry();}
  }return jar;
 }
 class Loader extends URLClassLoader {
  final Path jar;boolean mutate;
  Loader(Path jar)throws Exception{super(new URL[]{jar.toUri().toURL()},RunnerProducerObserverTest.class.getClassLoader());this.jar=jar;}
  @Override protected synchronized Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{if(name.equals(NAME)){Class<?> c=findLoadedClass(name);if(c==null)c=findClass(name);if(resolve)resolveClass(c);return c;}return super.loadClass(name,resolve);}
  @Override public URL getResource(String name){if(name.equals(RESOURCE)||name.equals(NAME.replace('.','/')+".class")){
   URL original=findResource(name);if(mutate&&name.equals(RESOURCE)&&original!=null)try{return new URL(null,original.toExternalForm(),new URLStreamHandler(){protected URLConnection openConnection(URL ignored)throws IOException{return new URLConnection(ignored){public void connect(){}public InputStream getInputStream()throws IOException{return new FilterInputStream(original.openStream()){@Override public void close()throws IOException{super.close();Files.write(jar,new byte[]{42},StandardOpenOption.APPEND);}};}};}});}catch(MalformedURLException error){throw new IllegalStateException(error);}return original;
  }return super.getResource(name);}
 }
 @SuppressWarnings("unchecked") Map<String,Object> observe(Loader loader)throws Exception{return (Map<String,Object>)loader.loadClass(NAME).getMethod("observe").invoke(null);}
 @Test void sourceClassDirectoryNeverFabricatesJarVersionOrCommit()throws Exception{Map<?,?> d=(Map<?,?>)subject().getMethod("observe").invoke(null);assertThat(d.get("identity_state")).isEqualTo("SOURCE_CLASSES_UNBOUND_UNTIL_ACCEPTED_D2_JAR");assertThat(d.get("artifact_sha256")).isNull();assertThat(d.get("version")).isNull();assertThat(d.get("source_commit")).isNull();}
 @Test void actualTemporaryJarHasObservedIdentityAndExactLoadedSchemaBytes()throws Exception{Path p=fixture(SCHEMA,true,0);try(var loader=new Loader(p)){var d=observe(loader);assertThat(d.get("identity_state")).isEqualTo("JAR_OBSERVED");assertThat(d.get("artifact_sha256")).isEqualTo(sha(Files.readAllBytes(p)));assertThat(d.get("version")).isEqualTo("7.8.9");assertThat(d.get("schema_sha256")).isEqualTo(sha(SCHEMA));assertThat(d.get("source_commit")).isNull();assertThat(d.get("source_tree")).isNull();assertThat(d.values()).doesNotContain("PINNED_JAR","READY");}}
 @Test void missingLoadedSchemaStaysUnbound()throws Exception{try(var loader=new Loader(fixture(null,true,0))){assertThat(observe(loader).get("identity_state")).isEqualTo("PRODUCER_UNBOUND");}}
 @Test void oversizeInflatedSchemaStaysUnbound()throws Exception{try(var loader=new Loader(fixture(new byte[2*1024*1024+1],true,0))){assertThat(observe(loader).get("identity_state")).isEqualTo("PRODUCER_UNBOUND");}}
 @Test void oversizedManifestIsBoundedBeforeParsing()throws Exception{try(var loader=new Loader(fixture(SCHEMA,true,20000))){assertThat(observe(loader).get("identity_state")).isEqualTo("PRODUCER_UNBOUND");}}
 @Test void missingManifestVersionNeverFallsBackToPomOrCwd()throws Exception{try(var loader=new Loader(fixture(SCHEMA,false,0))){var d=observe(loader);assertThat(d.get("identity_state")).isEqualTo("PRODUCER_UNBOUND");assertThat(d.get("version")).isNull();}}
 @Test void hardlinkedJarIsNotObservedAsSafeArtifact()throws Exception{Path p=fixture(SCHEMA,true,0);Files.createLink(temp.resolve("second-link.jar"),p);try(var loader=new Loader(p)){assertThat(observe(loader).get("identity_state")).isEqualTo("PRODUCER_UNBOUND");}}
 @Test void mutationDuringResourceObservationCannotProduceIdentity()throws Exception{Path p=fixture(SCHEMA,true,0);try(var loader=new Loader(p)){loader.loadClass(NAME);loader.mutate=true;assertThat(observe(loader).get("identity_state")).isEqualTo("PRODUCER_UNBOUND");}}
 @Test void oversizedActualCodeSourceNeverReadsOrHashesBeyondCeiling()throws Exception{Path p=fixture(SCHEMA,true,0);try(var loader=new Loader(p)){loader.loadClass(NAME);try(var file=new RandomAccessFile(p.toFile(),"rw")){file.setLength(96L*1024*1024+1);}assertThat(observe(loader).get("identity_state")).isEqualTo("PRODUCER_UNBOUND");}}
 @Test void finiteNestedParserUsesOnlyKnownActualBootSyntaxAndNeverNetwork()throws Exception{
  Method method=subject().getDeclaredMethod("outerPath",String.class);method.setAccessible(true);String file=temp.resolve("public.jar").toString();
  assertThat(method.invoke(null,"jar:nested:"+file+"/!BOOT-INF/classes/!/")).isEqualTo(Path.of(file));
  for(String bad:List.of("https://example.invalid/untrusted.jar","jar:nested:"+file+"/!BOOT-INF/lib/unknown.jar!/","jar:nested:../guessed.jar/!BOOT-INF/classes/!/","file://remote.invalid/path.jar","jar:nested:"+file+"/!arbitrary/!/"))assertThat(method.invoke(null,bad)).isNull();
 }
}
