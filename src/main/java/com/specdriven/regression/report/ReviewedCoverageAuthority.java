package com.specdriven.regression.report;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Optional externally authorized historical declarations; never executes or establishes business truth. */
final class ReviewedCoverageAuthority {
    private final ObjectMapper json;
    private final Path path;
    private final JsonNode input;
    private final String digest;
    private final Map<Path,String> snapshots=new LinkedHashMap<>();
    private final Map<Path,byte[]> consumed=new LinkedHashMap<>();
    private final Map<String,JsonNode> statements=new LinkedHashMap<>();
    private int bytes;
    private Path retainedRoot;
    private static final Set<String> BINDING=Set.of("qualified_case_ref","case_sha256","assertion_ref","expected_ref","qualified_oracle_ref","oracle_sha256","source_repo","source_commit","review_ref");
    ReviewedCoverageAuthority(Path path,String expected,ObjectMapper json)throws Exception {
        this.json=json;
        try {
        require(path!=null&&expected!=null&&expected.matches("[a-f0-9]{64}"),"independent authority path and pin required");
        this.path=regular(path);byte[] raw=consume(this.path);digest=hash(raw);require(expected.equals(digest),"independent authority pin mismatch");input=object(raw);
        exact(input,Set.of("schema_version","scope","authority","catalog","assertion_bindings","independent_attestations"));require("coverage_authority.v1".equals(text(input,"schema_version")),"unsupported authority schema");
        exact(input.path("scope"),Set.of("domain","project_ref","workspace_ref","repository_revisions"));
        for(String f:List.of("domain","project_ref","workspace_ref"))text(input.path("scope"),f);
        JsonNode revisions=input.path("scope").path("repository_revisions");require(revisions.isObject()&&!revisions.isEmpty(),"authority revisions missing");for(var it=revisions.fields();it.hasNext();)require(it.next().getValue().isTextual(),"invalid authority revision");
        JsonNode approval=input.path("authority");exact(approval,Set.of("review_ref","reviewed_by","approved_at"));text(approval,"review_ref");text(approval,"reviewed_by");instant(approval,"approved_at");
        if(!input.path("catalog").isNull()){exact(input.path("catalog"),Set.of("path","sha256"));Path catalog=artifact(input.path("catalog"));validateCatalogInput(object(consumed.get(catalog)));}
        Set<String> unique=new HashSet<>();
        for(JsonNode binding:array(input,"assertion_bindings")){validateBinding(binding);require(unique.add(key(binding)),"duplicate or ambiguous assertion binding");}
        require(input.path("assertion_bindings").size()+input.path("independent_attestations").size()<=10000,"authority row limit");
        for(JsonNode admission:array(input,"independent_attestations")) {
            exact(admission,Set.of("path","sha256","attestor","review_ref"));text(admission,"review_ref");Path p=artifact(admission);JsonNode statement=object(consumed.get(p));
            exact(statement,Set.of("schema_version","binding","attestor","issued_at","expires_at","origin_statement","origin_evidence_refs"));require("oracle_independence_attestation.v1".equals(text(statement,"schema_version")),"unsupported attestation schema");
            validateBinding(statement.path("binding"));require(text(admission,"attestor").equals(text(statement,"attestor")),"attestation attestor mismatch");text(statement,"origin_statement");
            Set<String> refs=new HashSet<>();JsonNode evidence=statement.path("origin_evidence_refs");require(evidence.isArray()&&!evidence.isEmpty()&&evidence.size()<=10000,"origin evidence references missing");for(JsonNode ref:evidence)require(ref.isTextual()&&!ref.asText().isBlank()&&refs.add(ref.asText()),"invalid origin evidence reference");
            Instant issued=instant(statement,"issued_at");if(!statement.path("expires_at").isNull())require(instant(statement,"expires_at").isAfter(issued),"invalid attestation time ordering");
            String key=key(statement.path("binding"));require(unique.contains(key),"attestation has no admitted assertion binding");JsonNode binding=null;for(JsonNode b:input.path("assertion_bindings"))if(key(b).equals(key))binding=b;
            require(statement.path("binding").equals(binding),"attestation binding mismatch");require(statements.putIfAbsent(key,obj("admission",admission,"statement",statement,"path",p.toString()))==null,"duplicate attestation binding");
        }
        } catch(Exception failure) {throw rejection(failure);}
    }
    Path catalogPath(){return input.path("catalog").isNull()?null:resolve(text(input.path("catalog"),"path"));}
    boolean active(Instant asOf){return !instant(input.path("authority"),"approved_at").isAfter(asOf);}
    boolean catalogActive(Instant asOf){if(!active(asOf))return false;try{return catalogPath()==null||!instant(object(consumed.get(catalogPath())).path("authority"),"approved_at").isAfter(asOf);}catch(RuntimeException e){throw e;}catch(Exception e){throw rejection(e);}}
    void verifyCatalogTiming(Instant asOf)throws Exception {
        if(catalogPath()==null||!catalogActive(asOf))return;
        for(JsonNode ac:array(object(consumed.get(catalogPath())),"acceptance_criteria")) {
            if(!Set.of("manual_only","waived").contains(ac.path("classification").asText()))continue;
            JsonNode exclusion=ac.path("exclusion");boolean complete=exclusion.isObject();
            for(String field:List.of("reason","review_ref","approved_by","approved_at"))complete&=exclusion.path(field).isTextual()&&!exclusion.path(field).asText().isBlank();
            if(!complete)continue;
            Instant approved;try{approved=Instant.parse(exclusion.path("approved_at").asText());}catch(java.time.format.DateTimeParseException unapproved){continue;}
            if(approved.isAfter(asOf))throw new Rejected("COVERAGE_AUTHORITY_EXCLUSION_FUTURE");
        }
    }
    void verifyScope(JsonNode subject){for(String field:List.of("domain","project_ref","workspace_ref","repository_revisions"))require(input.path("scope").path(field).equals(subject.path(field)),"authority scope/revision mismatch: "+field);}
    void apply(ObjectNode report,Map<String,JsonNode> provenance)throws Exception {
        verifyScope(report.path("subject"));
        Instant asOf=instant(report.path("selection"),"as_of");verifyCatalogTiming(asOf);boolean active=active(asOf);
        ObjectNode published=obj("state",active?"BOUND_REVIEWED":"UNKNOWN","reason",active?null:"authority_approval_future","path",path.toString(),"sha256",digest,"scope",input.path("scope"),"authority",input.path("authority"),"catalog",input.path("catalog"));
        ArrayNode files=published.putArray("artifacts");for(var e:snapshots.entrySet())files.add(obj("path",e.getKey().toString(),"sha256",e.getValue()));report.set("coverage_authority",published);
        Map<String,JsonNode> rowBindings=new HashMap<>();
        for(JsonNode relation:input.path("assertion_bindings")) {
            int matches=0;
            for(JsonNode value:report.path("oracle_trace")) {
                if(!text(relation,"qualified_case_ref").equals(value.path("qualified_case_ref").asText()))continue;
                if(!contains(value.path("declared_oracle_refs"),text(relation,"assertion_ref"))||!contains(value.path("declared_oracle_refs"),text(relation,"expected_ref"))||!text(relation,"qualified_oracle_ref").equals(value.path("oracle").path("qualified_ref").asText()))continue;
                JsonNode p=provenance.get(text(value,"source_result"));require(p!=null,"authority canonical provenance missing");JsonNode c=null;
                for(JsonNode source:p.path("executed_source_bindings"))if(text(relation,"qualified_case_ref").equals(source.path("artifact_id").asText())){require(c==null,"ambiguous authority case source");c=source;}
                require(c!=null&&"case".equals(c.path("role").asText())&&text(relation,"case_sha256").equals(c.path("sha256").asText()),"authority case/source hash mismatch");
                String assertion=text(relation,"assertion_ref");require(assertion.startsWith(text(c,"reviewed_path")+"#")&&assertion.length()>text(c,"reviewed_path").length()+1,"assertion is not in admitted case source");
                JsonNode oracle=value.path("oracle");for(String[] fields:List.of(new String[]{"qualified_oracle_ref","qualified_ref"},new String[]{"expected_ref","path"},new String[]{"oracle_sha256","sha256"},new String[]{"source_repo","source_repo"},new String[]{"source_commit","source_commit"}))require(text(relation,fields[0]).equals(oracle.path(fields[1]).asText()),"authority oracle/source identity mismatch: "+fields[0]);
                // Case qualified identity carries its own artifact revision; oracle source fields never become root composition X.
                matches++;ObjectNode row=(ObjectNode)value;JsonNode prior=rowBindings.putIfAbsent(text(row,"trace_id"),relation);require(prior==null||prior.equals(relation),"ambiguous reviewed assertion bindings for projected row");
                if(!active||!"VALID".equals(row.path("evidence_state").asText())){row.set("independence",obj("state","UNKNOWN","reason",!active?"authority_approval_future":"selected_evidence_"+row.path("evidence_state").asText().toLowerCase(Locale.ROOT)));continue;}
                require("SOURCE_BOUND".equals(row.path("binding_state").asText()),"authority cannot promote unbound source");
                row.put("expected_ref",text(relation,"expected_ref")).put("expected_ref_state","DECLARED_REVIEWED").putNull("expected_ref_reason");row.set("reviewed_binding",relation.deepCopy());
                JsonNode entry=statements.get(key(relation));
                if(entry==null){row.set("independence",obj("state","UNKNOWN","reason","independence_attestation_missing"));continue;}
                JsonNode statement=entry.path("statement"),admission=entry.path("admission");String reason=instant(statement,"issued_at").isAfter(asOf)?"attestation_issued_in_future":!statement.path("expires_at").isNull()&&!asOf.isBefore(instant(statement,"expires_at"))?"attestation_expired":null;
                row.set("independence",reason==null?obj("state","REVIEW_ATTESTED","reason",null,"attestation_ref",entry.get("path"),"attestation_sha256",admission.get("sha256"),"attestor",admission.get("attestor"),"review_ref",admission.get("review_ref"),"issued_at",statement.get("issued_at"),"expires_at",statement.get("expires_at"),"qualification","Accepted reviewed origin statement only; not execution or proof of business correctness."):obj("state","UNKNOWN","reason",reason));
            }
            require(matches>0,"authority binding has no admitted canonical case/oracle operands");
        }
        verifyUnchanged();
    }
    ReviewedCoverageAuthority snapshotTo(Path destination)throws Exception {
        verifyUnchanged();destination=destination.toAbsolutePath().normalize();require(!Files.exists(destination,LinkOption.NOFOLLOW_LINKS),"authority snapshot directory already exists");Files.createDirectories(destination.getParent());Files.createDirectory(destination,java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
        for(var entry:consumed.entrySet()){Path relative=path.getParent().relativize(entry.getKey()),target=destination.resolve(relative);Files.createDirectories(target.getParent());Files.write(target,entry.getValue(),StandardOpenOption.CREATE_NEW);Files.setPosixFilePermissions(target,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));require(Arrays.equals(entry.getValue(),bounded(regular(target))),"authority snapshot readback mismatch");}
        ReviewedCoverageAuthority frozen=new ReviewedCoverageAuthority(destination.resolve(path.getFileName()),digest,json);frozen.retainedRoot=destination;frozen.verifyUnchanged();verifyUnchanged();return frozen;
    }
    void verifyRetainedClosure(Path root)throws Exception {
        root=root.toAbsolutePath().normalize();require(path.getParent().equals(root),"authority snapshot root mismatch");int count=0;Set<Path>seen=new HashSet<>();try(var walk=Files.walk(root)){var iterator=walk.iterator();while(iterator.hasNext()){Path entry=iterator.next();require(++count<=1153,"authority retained path count limit");require(!Files.isSymbolicLink(entry),"authority retained symlink");if(Files.isDirectory(entry,LinkOption.NOFOLLOW_LINKS)){boolean ancestor=false;for(Path expected:snapshots.keySet())if(expected.startsWith(entry)){ancestor=true;break;}require(ancestor,"unexpected authority snapshot directory");}else{require(snapshots.containsKey(entry),"unreferenced authority snapshot file");require(snapshots.get(entry).equals(hash(bounded(regular(entry)))),"authority snapshot digest mismatch");seen.add(entry);}}}require(seen.equals(snapshots.keySet()),"authority snapshot reference missing");
    }
    void verifyUnchanged()throws Exception {if(retainedRoot!=null)verifyRetainedClosure(retainedRoot);for(var e:snapshots.entrySet())require(e.getValue().equals(hash(bounded(regular(e.getKey())))),"authority input changed while consumed");}
    Map<Path,String> snapshots(){return Collections.unmodifiableMap(snapshots);}
    private void validateCatalogInput(JsonNode catalog){
        require(!catalog.has("code_coverage_artifacts"),"code_coverage_artifacts unsupported by coverage_authority.v1 inventory profile; use the existing separate pinned code coverage input");
        closedCatalog(catalog,Set.of("schema_version","catalog_revision","scope","authority","requirements","acceptance_criteria"),Set.of());
        closedCatalog(catalog.path("scope"),Set.of("domain","project_ref","workspace_ref","repository_revisions"),Set.of());
        closedCatalog(catalog.path("authority"),Set.of("kind","source_ref","source_sha256","review_ref","approved_by","approved_at"),Set.of());
        for(JsonNode requirement:array(catalog,"requirements"))closedCatalog(requirement,Set.of("id","ac_ids"),Set.of());
        for(JsonNode criterion:array(catalog,"acceptance_criteria")){
            closedCatalog(criterion,Set.of("id","requirement_id","classification"),Set.of("test_case_ids","review_ref","exclusion"));
            if(criterion.hasNonNull("review_ref"))text(criterion,"review_ref");
            if(criterion.hasNonNull("exclusion")){JsonNode exclusion=criterion.path("exclusion");closedCatalog(exclusion,Set.of(),Set.of("reason","review_ref","approved_by","approved_at"));for(var fields=exclusion.fieldNames();fields.hasNext();)text(exclusion,fields.next());}
        }
        require("coverage-catalog.v1".equals(text(catalog,"schema_version")),"catalog schema mismatch");text(catalog,"catalog_revision");JsonNode approval=catalog.path("authority");require("reviewed_inventory".equals(text(approval,"kind")),"catalog authority kind mismatch");for(String f:List.of("source_ref","review_ref","approved_by"))text(approval,f);require(text(approval,"source_sha256").matches("[a-f0-9]{64}"),"catalog authority hash invalid");instant(approval,"approved_at");
        for(String f:List.of("domain","project_ref","workspace_ref","repository_revisions"))require(catalog.path("scope").path(f).equals(input.path("scope").path(f)),"catalog scope mismatch: "+f);
        Map<String,JsonNode> requirements=new LinkedHashMap<>(),criteria=new LinkedHashMap<>();for(JsonNode row:array(catalog,"requirements"))require(requirements.putIfAbsent(text(row,"id"),row)==null,"catalog duplicate requirement");for(JsonNode row:array(catalog,"acceptance_criteria"))require(criteria.putIfAbsent(text(row,"id"),row)==null,"catalog duplicate AC");
        for(var entry:criteria.entrySet()){JsonNode ac=entry.getValue();require(requirements.containsKey(text(ac,"requirement_id")),"catalog orphan AC");require(Set.of("automatable","partial","manual_only","waived").contains(text(ac,"classification")),"catalog classification invalid");if(!inputStrings(ac.path("test_case_ids"),true).isEmpty())text(ac,"review_ref");}
        for(var entry:requirements.entrySet()){List<String> ids=inputStrings(entry.getValue().path("ac_ids"),false);for(String id:ids)require(criteria.containsKey(id)&&entry.getKey().equals(criteria.get(id).path("requirement_id").asText()),"catalog requirement/AC linkage mismatch");for(var ac:criteria.entrySet())if(entry.getKey().equals(ac.getValue().path("requirement_id").asText()))require(ids.contains(ac.getKey()),"catalog missing child AC linkage");}
    }
    private static void closedCatalog(JsonNode node,Set<String> required,Set<String> optional){Set<String> actual=new HashSet<>(),allowed=new HashSet<>(required);node.fieldNames().forEachRemaining(actual::add);allowed.addAll(optional);require(node.isObject()&&actual.containsAll(required)&&allowed.containsAll(actual),"catalog fields invalid for inventory profile");}
    private static List<String> inputStrings(JsonNode node,boolean optional){if(optional&&node.isMissingNode())return List.of();require(node.isArray()&&node.size()<=10000,"catalog string array invalid");List<String> values=new ArrayList<>();for(JsonNode value:node)require(value.isTextual()&&!value.asText().isBlank()&&!values.contains(value.asText())&&values.add(value.asText()),"catalog duplicate/invalid string");return values;}
    private void validateBinding(JsonNode b){exact(b,BINDING);for(String f:BINDING)text(b,f);for(String f:List.of("case_sha256","oracle_sha256"))require(text(b,f).matches("[a-f0-9]{64}"),"invalid binding hash");relative(text(b,"expected_ref"));String a=text(b,"assertion_ref");require(a.indexOf('#')>0&&a.indexOf('#')==a.lastIndexOf('#'),"invalid assertion reference");relative(a.substring(0,a.indexOf('#')));}
    private String key(JsonNode b){return text(b,"qualified_case_ref")+"\n"+text(b,"assertion_ref");}
    private Path artifact(JsonNode entry)throws Exception{Path p=regular(resolve(text(entry,"path")));require(text(entry,"sha256").matches("[a-f0-9]{64}")&&text(entry,"sha256").equals(hash(consume(p))),"authority artifact hash mismatch");return p;}
    private Path resolve(String ref){relative(ref);return path.getParent().resolve(ref).normalize();}
    private static void relative(String ref){try{Path p=Path.of(ref);require(!p.isAbsolute()&&!ref.contains("\\")&&p.normalize().toString().equals(ref)&&!ref.startsWith("../")&&!ref.equals("..")&&!ref.equals(".")&&p.getNameCount()<=8,"unsafe authority relative reference");}catch(Rejected safe){throw rejection(safe);}catch(Exception invalid){throw new Rejected("COVERAGE_AUTHORITY_INVALID_REFERENCE");}}
    private static byte[] bounded(Path p)throws Exception{require(Files.size(p)<=2*1024*1024,"authority metadata byte limit");try(var in=Files.newInputStream(p)){byte[] raw=in.readNBytes(2*1024*1024+1);require(raw.length<=2*1024*1024,"authority metadata byte limit");return raw;}}
    private byte[] consume(Path p)throws Exception{if(consumed.containsKey(p))return consumed.get(p);require(consumed.size()<128,"authority artifact count limit");require(Files.size(p)<=2*1024*1024&&bytes+Files.size(p)<=2*1024*1024,"authority closure byte limit");byte[] raw;try(var in=Files.newInputStream(p)){raw=in.readNBytes(2*1024*1024+1);}bytes+=raw.length;require(bytes<=2*1024*1024,"authority closure byte limit");snapshots.put(p,hash(raw));consumed.put(p,raw);return raw;}
    private static Path regular(Path path)throws Exception {Path p=path.toAbsolutePath().normalize();require(p.toString().length()<=1024,"authority path length limit");for(Path q=p;q!=null;q=q.getParent())require(!Files.isSymbolicLink(q),"authority symlink prohibited");require(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS),"authority regular file missing");require(((Number)Files.getAttribute(p,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue()==1,"authority hardlink prohibited");return p;}
    private JsonNode object(byte[] raw)throws Exception {
        try {
            String decoded=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(raw)).toString();
            JsonNode n=json.readTree(decoded);require(n!=null&&n.isObject(),"authority JSON object required");metadata(n,0);return n;
        } catch(Rejected safe) {throw rejection(safe);}
        catch(Exception invalid) {throw new Rejected("COVERAGE_AUTHORITY_INVALID_JSON");}
    }

    private static void metadata(JsonNode n,int depth){require(depth<=80,"authority metadata depth");if(n.isTextual())require(n.asText().length()<=4096,"authority metadata string limit");else if(n.isContainerNode()){if(n.isObject())for(var keys=n.fieldNames();keys.hasNext();)require(keys.next().length()<=4096,"authority metadata key limit");for(JsonNode v:n)metadata(v,depth+1);}}
    private ObjectNode obj(Object...values){ObjectNode n=json.createObjectNode();for(int i=0;i<values.length;i+=2)n.set((String)values[i],json.valueToTree(values[i+1]));return n;}
    private static List<JsonNode> array(JsonNode n,String f){JsonNode a=n.path(f);require(a.isArray()&&a.size()<=10000,"invalid authority array: "+f);List<JsonNode> out=new ArrayList<>();a.forEach(out::add);return out;}
    private static boolean contains(JsonNode values,String target){for(JsonNode v:values)if(v.isTextual()&&target.equals(v.asText()))return true;return false;}
    private static String text(JsonNode n,String f){JsonNode v=n.path(f);require(v.isTextual()&&!v.asText().isBlank()&&v.asText().length()<=4096,"invalid authority text: "+f);return v.asText();}
    private static Instant instant(JsonNode n,String f){try{return Instant.parse(text(n,f));}catch(Rejected safe){throw rejection(safe);}catch(Exception invalid){throw new Rejected("COVERAGE_AUTHORITY_INVALID_TIMESTAMP");}}
    private static void exact(JsonNode n,Set<String> fields){Set<String> actual=new HashSet<>();n.fieldNames().forEachRemaining(actual::add);require(n.isObject()&&actual.equals(fields),"invalid/untyped authority fields");}
    private static String hash(byte[] raw)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));}
    static IllegalArgumentException rejection(Exception failure){return new Rejected(failure instanceof Rejected?failure.getMessage():"COVERAGE_AUTHORITY_INPUT_REJECTED");}
    private static final class Rejected extends IllegalArgumentException {private Rejected(String message){super(message,null);}}
    private static void require(boolean condition,String message){if(!condition)throw new Rejected("COVERAGE_AUTHORITY_REJECTED: coverage authority: "+message);}
}
