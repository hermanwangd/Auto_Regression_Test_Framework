package com.specdriven.regression.runner;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Shared finite admission; never follows source symlinks or executes source files. */
final class RunnerFiles {
    static final int TEXT_LIMIT=2*1024*1024;
    static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(50).maxStringLength(TEXT_LIMIT).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static void require(boolean valid,String reason) { if(!valid)throw new IllegalArgumentException(reason); }
    static String sha(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);} }
    static byte[] bytes(JsonNode value)throws IOException { return (JSON.writeValueAsString(value)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8); }
    static ObjectNode object(){return JSON.createObjectNode();}
    static void fields(JsonNode value,String... allowed){require(value.isObject(),"object_required");var keys=new HashSet<String>();value.fieldNames().forEachRemaining(keys::add);require(keys.equals(Set.of(allowed)),"unknown_or_missing_fields");}
    static String text(JsonNode value,String field){JsonNode n=value.get(field);require(n!=null&&n.isTextual()&&!n.asText().isBlank()&&n.asText().length()<=(Set.of("artifact_id","suite_artifact_id","from_artifact_id","to_ref").contains(field)?2048:512),"missing_or_invalid_"+field);return n.asText();}
    static String digest(JsonNode value,String field){String s=text(value,field);require(s.matches("[a-f0-9]{64}"),"invalid_sha256_"+field);return s;}
    static List<JsonNode> array(JsonNode value,String field,int min,int max){JsonNode n=value.get(field);require(n!=null&&n.isArray()&&n.size()>=min&&n.size()<=max,"invalid_array_"+field);var list=new ArrayList<JsonNode>();n.forEach(list::add);return list;}
    static JsonNode document(byte[] bytes)throws IOException{
        require(bytes.length<=TEXT_LIMIT,"metadata_byte_limit");
        // JSON is preferred; strict YAML is admitted for product-owned suite/lock files.
        String raw=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
        if(raw.stripLeading().startsWith("{"))return JSON.readTree(bytes);
        LoaderOptions opts=new LoaderOptions();opts.setAllowDuplicateKeys(false);opts.setMaxAliasesForCollections(0);opts.setNestingDepthLimit(50);opts.setCodePointLimit(TEXT_LIMIT);
        Object value=new Yaml(new SafeConstructor(opts)).load(raw);require(value instanceof Map<?,?>,"object_required");return JSON.valueToTree(value);
    }
    static Path relative(String value){
        require(!value.isBlank()&&value.length()<=512&&!value.contains("\\")&&!value.contains(":")&&!value.contains("\0"),"unsafe_relative_path");
        Path p=Path.of(value);require(!p.isAbsolute()&&p.getNameCount()<=16&&p.normalize().equals(p),"unsafe_relative_path");
        for(String part:value.split("/",-1))require(!part.isBlank()&&!part.equals(".")&&!part.equals(".."),"unsafe_relative_path");return p;
    }
    @SuppressWarnings("unchecked")
    private static SecureDirectoryStream<Path> secureRoot(Path root)throws IOException{
        Path absolute=root.toAbsolutePath();require(absolute.normalize().equals(absolute),"unnormalized_root");
        DirectoryStream<Path> opened=Files.newDirectoryStream(absolute.getRoot());
        require(opened instanceof SecureDirectoryStream<?>,"secure_directory_operations_unavailable");
        SecureDirectoryStream<Path> current=(SecureDirectoryStream<Path>)opened;
        try{
            for(Path part:absolute){SecureDirectoryStream<Path> next=current.newDirectoryStream(part,LinkOption.NOFOLLOW_LINKS);current.close();current=next;}
            return current;
        }catch(IOException|RuntimeException e){current.close();throw e;}
    }
    static byte[] read(Path path,long maximum)throws IOException{
        Path p=path.toAbsolutePath();return read(p.getParent(),p.getFileName().toString(),maximum);
    }
    static byte[] read(Path root,String ref,long maximum)throws IOException{
        Path relative=relative(ref);require(maximum>=0&&maximum<=100663296,"invalid_read_limit");
        SecureDirectoryStream<Path> dir=secureRoot(root);
        try{
            for(int i=0;i<relative.getNameCount()-1;i++){var next=dir.newDirectoryStream(relative.getName(i),LinkOption.NOFOLLOW_LINKS);dir.close();dir=next;}
            Path name=relative.getFileName();var view=dir.getFileAttributeView(name,BasicFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);var before=view.readAttributes();
            require(before.isRegularFile()&&!before.isSymbolicLink()&&before.fileKey()!=null&&before.size()<=maximum,"unsafe_or_oversized_file: "+ref);
            // Hard links are not portable evidence/input aliases. Fail closed if unsupported.
            Path lexical=root.resolve(relative);Object links=Files.getAttribute(lexical,"unix:nlink",LinkOption.NOFOLLOW_LINKS);require(((Number)links).longValue()==1,"hardlink_forbidden: "+ref);
            try(var channel=dir.newByteChannel(name,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));var out=new ByteArrayOutputStream()){
                ByteBuffer buffer=ByteBuffer.allocate(8192);long count=0;
                while(channel.read(buffer)>=0){buffer.flip();int n=buffer.remaining();count+=n;require(count<=maximum,"stream_byte_limit: "+ref);out.write(buffer.array(),0,n);buffer.clear();}
                var after=view.readAttributes();require(before.fileKey().equals(after.fileKey())&&before.size()==after.size()&&before.lastModifiedTime().equals(after.lastModifiedTime())&&count==before.size(),"file_changed_during_read: "+ref);return out.toByteArray();
            }
        }finally{dir.close();}
    }
    static JsonNode pinned(Path path,String expected)throws IOException{require(expected!=null&&expected.matches("[a-f0-9]{64}"),"independent_pin_required");byte[] raw=read(path,TEXT_LIMIT);require(sha(raw).equals(expected),"independent_pin_mismatch");return document(raw);}
    private static final java.util.regex.Pattern SECRET=java.util.regex.Pattern.compile(
        "(?i)(?:-----BEGIN[^\\r\\n]*PRIVATE KEY-----|(?:https?|grpcs?|nats|mongodb(?:\\+srv)?)://[^\\s/@:]+:[^\\s/@]+@|\\bjdbc:[^\\s\"']+|[\"']?(?:password|authorization|access_token|api_key|token|credential)[\"']?\\s*[:=]\\s*[\"']?(?!secret://|vault://|\\$\\{|\\*\\*\\*MASKED|\\[MASKED\\])[^\\s\"'{}]{4,})");
    private static final java.util.regex.Pattern PRIVATE_KEY=java.util.regex.Pattern.compile("(?i)-----\\s*(?:BEGIN|END)[^\\r\\n]*PRIVATE\\s+KEY");
    static boolean privateKeyMaterial(byte[] raw){return PRIVATE_KEY.matcher(new String(raw,java.nio.charset.StandardCharsets.UTF_8)).find();}
    static void noSecret(byte[] raw){require(!privateKeyMaterial(raw)&&!SECRET.matcher(new String(raw,java.nio.charset.StandardCharsets.UTF_8)).find(),"secret_or_private_key_excluded");}
    static void fresh(Path output)throws IOException{fresh(output,RunnerDurability.SYSTEM);}
    static void fresh(Path output,RunnerDurability durability)throws IOException{require(!Files.exists(output,LinkOption.NOFOLLOW_LINKS),"fresh_output_required");try(var ignored=secureRoot(output.toAbsolutePath().getParent())){Files.createDirectory(output,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));durability.forceDirectory(output.toAbsolutePath());durability.forceDirectory(output.toAbsolutePath().getParent());}}
    static void sync(Path dir)throws IOException{try(var checked=secureRoot(dir);var ch=FileChannel.open(dir,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){ch.force(true);}}
    static void forceFile(Path path)throws IOException{
        Path p=path.toAbsolutePath();try(var dir=secureRoot(p.getParent());var channel=dir.newByteChannel(p.getFileName(),Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))){require(channel instanceof FileChannel,"durable_file_channel_unavailable");((FileChannel)channel).force(true);}
    }
    static void save(Path root,String ref,byte[] bytes)throws IOException{save(root,ref,bytes,RunnerDurability.SYSTEM);}
    private static void directories(Path root,Path parent,RunnerDurability durability)throws IOException{
        Path base=root.toAbsolutePath();require(parent.startsWith(base),"saved_directory_escapes_root");
        try(var checked=secureRoot(base)){}
        Path current=base;
        for(Path component:base.relativize(parent)){
            if(component.toString().isEmpty())continue;
            Path next=current.resolve(component);
            if(!Files.exists(next,LinkOption.NOFOLLOW_LINKS)){
                Files.createDirectory(next,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                // Persist the new child entry in its parent before creating deeper descendants.
                durability.forceDirectory(current);durability.forceDirectory(next);
            }
            try(var checked=secureRoot(next)){}
            current=next;
        }
    }
    static void forceSaved(Path root,String ref,String expectedSha,long maximum,RunnerDurability durability)throws IOException{
        Path base=root.toAbsolutePath(),file=base.resolve(relative(ref));
        byte[] before=durability.readSaved(base,ref,maximum);require(sha(before).equals(expectedSha),"saved_reread_mismatch");
        durability.forceFile(file);
        for(Path dir=file.getParent();;dir=dir.getParent()){
            require(dir.startsWith(base),"saved_directory_escapes_root");durability.forceDirectory(dir);if(dir.equals(base))break;
        }
        byte[] after=durability.readSaved(base,ref,maximum);require(Arrays.equals(before,after)&&sha(after).equals(expectedSha),"saved_reread_mismatch");
    }
    static void save(Path root,String ref,byte[] bytes,RunnerDurability durability)throws IOException{
        Path rel=relative(ref);Path base=root.toAbsolutePath(),dest=base.resolve(rel),parent=dest.getParent();directories(base,parent,durability);
        try(var checked=secureRoot(parent);var channel=checked.newByteChannel(dest.getFileName(),Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS))){ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())channel.write(b);}
        forceSaved(base,ref,sha(bytes),Math.max(bytes.length,1),durability);
    }
    /** A failed completion publication must not leave an observable success marker. */
    static void saveCompletion(Path root,byte[] bytes,RunnerDurability durability)throws IOException{
        try{save(root,"SAVE.complete",bytes,durability);}
        catch(IOException|RuntimeException failed){
            // Only this fresh controller-owned publication marker is invalidated; all
            // original source and collected evidence remain available for recovery.
            try(var dir=secureRoot(root)){
                try{dir.deleteFile(Path.of("SAVE.complete"));}
                catch(NoSuchFileException absent){/* No marker was created. */}
            }catch(IOException|RuntimeException invalidationFailure){failed.addSuppressed(invalidationFailure);}
            try{durability.forceDirectory(root.toAbsolutePath());}
            catch(IOException|RuntimeException invalidationSyncFailure){failed.addSuppressed(invalidationSyncFailure);}
            throw failed;
        }
    }
    private RunnerFiles(){}
}
