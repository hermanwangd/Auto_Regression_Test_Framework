package com.specdriven.regression.runner;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Observes only this loaded class's CodeSource. Observation supplies no trust authority. */
public final class RunnerProducerObserver {
 private static final long JAR_LIMIT=96L*1024*1024;
 private static final String RESOURCE="schemas/source_bindings.v1.schema.yaml";
 public static Map<String,Object> observe(){
  try {
   URL location=RunnerProducerObserver.class.getProtectionDomain().getCodeSource().getLocation();
   Path jar=outerPath(location.toExternalForm());
   if(jar==null)return unbound("PRODUCER_UNBOUND");
   if(Files.isDirectory(jar,LinkOption.NOFOLLOW_LINKS))return unbound("SOURCE_CLASSES_UNBOUND_UNTIL_ACCEPTED_D2_JAR");
   long deadline=System.nanoTime()+30_000_000_000L;
   Map<String,Object> before=identity(jar);
   String artifact=hashFile(jar,deadline);
   boolean boot=location.toExternalForm().startsWith("jar:nested:")||location.toExternalForm().endsWith("!/BOOT-INF/classes!/");
   String prefix=boot?"BOOT-INF/classes/":"";
   byte[] schema,manifest;
   try(ZipFile zip=new ZipFile(jar.toFile())){
    manifest=entry(zip,"META-INF/MANIFEST.MF",16384);
    schema=entry(zip,prefix+RESOURCE,2*1024*1024);
   }
   String version=version(manifest);
   URL loaded=RunnerProducerObserver.class.getClassLoader().getResource(RESOURCE);
   if(loaded==null)throw new IOException();
   String expected=boot&&location.toExternalForm().startsWith("jar:nested:")?location.toExternalForm()+RESOURCE:"jar:"+jar.toUri().toURL().toExternalForm()+"!/"+prefix+RESOURCE;
   if(!loaded.toExternalForm().equals(expected))throw new IOException();
   byte[] actual;
   URLConnection connection=loaded.openConnection();connection.setUseCaches(false);
   try(InputStream in=connection.getInputStream()){actual=bounded(in,2*1024*1024,deadline);}
   if(!Arrays.equals(schema,actual)||!before.equals(identity(jar))||System.nanoTime()>=deadline)throw new IOException();
   Map<String,Object> result=unbound("JAR_OBSERVED");result.put("version",version);result.put("artifact_sha256",artifact);result.put("schema_sha256",sha(actual));return result;
  }catch(Exception invalid){return unbound("PRODUCER_UNBOUND");}
 }
 private static Map<String,Object> unbound(String state){Map<String,Object> r=new LinkedHashMap<>();r.put("version",null);r.put("identity_state",state);r.put("artifact_sha256",null);r.put("source_commit",null);r.put("source_tree",null);r.put("schema_sha256",null);return r;}
 /** Finite, local-only CodeSource forms, including the observed Spring Boot 3 nested form. */
 private static Path outerPath(String value){
  try {
   Path p;
   if(value.startsWith("jar:nested:")&&value.endsWith("/!BOOT-INF/classes/!/")){
    String raw=value.substring(11,value.length()-"/!BOOT-INF/classes/!/".length());if(raw.contains("!")||raw.contains("%")||raw.contains("?"))return null;p=Path.of(raw);
   }else{
    String raw=value;if(raw.startsWith("jar:file:")&&raw.endsWith("!/BOOT-INF/classes!/"))raw=raw.substring(4,raw.length()-"!/BOOT-INF/classes!/".length());
    URI uri=URI.create(raw);if(!"file".equals(uri.getScheme())||uri.getAuthority()!=null||uri.getQuery()!=null||uri.getFragment()!=null)return null;p=Path.of(uri);
   }
   if(!p.isAbsolute()||!p.normalize().equals(p))return null;return p;
  }catch(RuntimeException invalid){return null;}
 }
 private static Map<String,Object> identity(Path p)throws IOException{
  for(Path cursor=p;cursor!=null;cursor=cursor.getParent())if(Files.isSymbolicLink(cursor))throw new IOException();
  var a=Files.readAttributes(p,"unix:dev,ino,nlink,size,lastModifiedTime,ctime,isRegularFile",LinkOption.NOFOLLOW_LINKS);
  if(!Boolean.TRUE.equals(a.get("isRegularFile"))||((Number)a.get("nlink")).longValue()!=1||((Number)a.get("size")).longValue()<1||((Number)a.get("size")).longValue()>JAR_LIMIT)throw new IOException();return a;
 }
 private static String hashFile(Path p,long deadline)throws Exception{
  MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;byte[] chunk=new byte[8192];
  try(InputStream in=Files.newInputStream(p,LinkOption.NOFOLLOW_LINKS)){for(int n;(n=in.read(chunk))!=-1;){total+=n;if(total>JAR_LIMIT||System.nanoTime()>=deadline)throw new IOException();digest.update(chunk,0,n);}}return HexFormat.of().formatHex(digest.digest());
 }
 private static byte[] entry(ZipFile zip,String name,int limit)throws IOException{
  ZipEntry found=null;int count=0;var entries=zip.entries();while(entries.hasMoreElements()){if(++count>32768)throw new IOException();ZipEntry e=entries.nextElement();if(e.getName().equals(name)){if(found!=null)throw new IOException();found=e;}}
  if(found==null||found.isDirectory()||found.getSize()<0||found.getSize()>limit)throw new IOException();try(InputStream in=zip.getInputStream(found)){return bounded(in,limit,System.nanoTime()+30_000_000_000L);}
 }
 private static byte[] bounded(InputStream in,int limit,long deadline)throws IOException{
  ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] chunk=new byte[8192];for(int n;(n=in.read(chunk))!=-1;){if(out.size()+n>limit||System.nanoTime()>=deadline)throw new IOException();out.write(chunk,0,n);}return out.toByteArray();
 }
 private static String version(byte[] bytes)throws IOException{
  String text=new String(bytes,StandardCharsets.UTF_8);if(text.indexOf('\0')>=0)throw new IOException();Map<String,String> attrs=new HashMap<>();String key=null;
  for(String line:text.split("\r?\n",-1)){if(line.isEmpty())break;if(line.startsWith(" ")){if(key==null)throw new IOException();attrs.put(key,attrs.get(key)+line.substring(1));continue;}int colon=line.indexOf(": ");if(colon<1)throw new IOException();key=line.substring(0,colon).toLowerCase(Locale.ROOT);if(attrs.putIfAbsent(key,line.substring(colon+2))!=null)throw new IOException();}
  String v=attrs.get("implementation-version");if(!"1.0".equals(attrs.get("manifest-version"))||v==null||!v.matches("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,127}"))throw new IOException();return v;
 }
 private static String sha(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
 private RunnerProducerObserver(){}
}
