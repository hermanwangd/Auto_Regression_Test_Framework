package com.specdriven.regression.provider.jdbc;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** One externally owned local database profile. This grants neither ownership nor credentials. */
public final class ReviewedOwnedMariaDbPolicy {
    public static final String PROFILE="owned-mariadb-readonly.v1", SECRET_REF="memory://owned-mariadb";
    public static final String QUERY_PATH="queries/owned-readonly.sql", LEASE_PATH="fixtures/lease.json";
    public static final String SQL="SELECT probe_id, probe_value, (SELECT COUNT(*) FROM swarm_probe.read_only_rows) AS total_rows FROM swarm_probe.read_only_rows ORDER BY probe_id LIMIT 3";
    public static final String DRIVER_SHA="85c4ba2f221d0dfd439c26affbb294f784960763544263c65aba9c2c76858706";
    public static final String IMAGE_SHA="ec1794c8cd442f795b838f60379d7851acf8fef04cc39a8ecf74e7b1d6a3b6d7";
    private static final Set<String> FIELDS=Set.of("version","profile","owner_run_id","container_id","image_digest","container_started_at","owner_pid","owner_start_instant","db_pid","db_start_instant","endpointIPv4","port","schema","query_sha256","driver_sha256","issued_at","expires_at");
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private ReviewedOwnedMariaDbPolicy() {}
    public static void validateBindings(Map<String,?> b) {
        require(b!=null&&b.keySet().equals(Set.of("dialect","profile","connection.secret_ref","endpoint","owner_run_id")),"exact bindings required");
        require("mariadb".equals(b.get("dialect"))&&PROFILE.equals(b.get("profile"))&&SECRET_REF.equals(b.get("connection.secret_ref")),"finite profile and RAM reference required");
        require(b.get("endpoint") instanceof String s&&s.endsWith(":3306")&&privateIpv4(s.substring(0,s.length()-5)),"owned literal private IPv4:3306 required");
        require(b.get("owner_run_id") instanceof String s&&s.matches("[a-f0-9]{32}"),"owner nonce required");
    }
    public static void validateInputs(Map<String,?> inputs) {
        require(inputs!=null&&inputs.equals(Map.of("query_ref","artifact://"+QUERY_PATH)),"one exact query artifact without parameters required");
    }
    public record Fixture(Map<String,Object> lease,String leaseSha256) {}
    public static Fixture fixture(Path root,Map<String,?> bindings,boolean live) {
        validateBindings(bindings);
        try {
            byte[] query=read(root,QUERY_PATH,1024); require(Arrays.equals(query,SQL.getBytes(StandardCharsets.UTF_8)),"query bytes differ");
            byte[] bytes=read(root,LEASE_PATH,16384);JsonNode l=JSON.readTree(bytes);
            ReviewedJdbcProof.validateLease(l);
            require(l!=null&&l.isObject()&&l.size()==FIELDS.size(),"exact lease fields required");l.fieldNames().forEachRemaining(k->require(FIELDS.contains(k),"unknown lease field"));
            require("reviewed-mariadb-lease.v1".equals(text(l,"version"))&&PROFILE.equals(text(l,"profile")),"lease profile mismatch");
            require(bindings.get("owner_run_id").equals(text(l,"owner_run_id"))&&text(l,"container_id").matches("[a-f0-9]{64}"),"owner identity mismatch");
            require(IMAGE_SHA.equals(text(l,"image_digest"))&&DRIVER_SHA.equals(text(l,"driver_sha256"))&&digest(query).equals(text(l,"query_sha256")),"source pin mismatch");
            require(privateIpv4(text(l,"endpointIPv4"))&&l.path("port").isInt()&&l.path("port").intValue()==3306&&bindings.get("endpoint").equals(text(l,"endpointIPv4")+":3306")&&"swarm_probe".equals(text(l,"schema")),"endpoint/schema mismatch");
            Instant.parse(text(l,"container_started_at"));Instant issue=Instant.parse(text(l,"issued_at")),expiry=Instant.parse(text(l,"expires_at"));
            require(expiry.isAfter(issue)&&Duration.between(issue,expiry).compareTo(Duration.ofMinutes(10))<=0,"lease TTL exceeds ten minutes");
            for(String role:List.of("owner","db")) {
                require(l.path(role+"_pid").isIntegralNumber()&&l.path(role+"_pid").canConvertToLong()&&l.path(role+"_pid").longValue()>0,"positive process PID required");
                Instant start=Instant.parse(text(l,role+"_start_instant"));
                if(live) {var process=ProcessHandle.of(l.path(role+"_pid").longValue()).orElseThrow(()->invalid("owned process missing"));require(process.isAlive()&&process.info().startInstant().filter(start::equals).isPresent(),"owned process start mismatch");
                    if(role.equals("owner"))require(Arrays.asList(process.info().arguments().orElse(new String[0])).contains("swarm.mariadb.owner_nonce="+bindings.get("owner_run_id")),"owner nonce argument missing");}
            }
            if(live)require(!Instant.now().isBefore(issue)&&Instant.now().isBefore(expiry),"lease expired or future");
            @SuppressWarnings("unchecked") Map<String,Object> map=JSON.convertValue(l,Map.class);return new Fixture(Map.copyOf(map),digest(bytes));
        } catch(java.io.IOException|java.time.DateTimeException e) {throw invalid("invalid bounded fixture");}
    }
    public static byte[] read(Path root,String relative,int max)throws java.io.IOException {
        Path base=root.toRealPath(),path=base.resolve(relative).normalize();require(path.startsWith(base),"outside approved root");
        Path cursor=base;for(Path part:base.relativize(path)){cursor=cursor.resolve(part);require(!Files.isSymbolicLink(cursor),"symlink fixture rejected");}
        require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS),"regular fixture required");
        try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=in.readNBytes(max+1);require(bytes.length>0&&bytes.length<=max,"fixture bound exceeded");return bytes;}
    }
    public static String digest(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    static boolean privateIpv4(String s) {String[] p=s.split("\\.",-1);if(p.length!=4)return false;int[] n=new int[4];for(int i=0;i<4;i++){if(!p[i].matches("0|[1-9][0-9]{0,2}"))return false;n[i]=Integer.parseInt(p[i]);if(n[i]>255)return false;}return n[3]>0&&n[3]<255&&(n[0]==10||n[0]==172&&n[1]>=16&&n[1]<=31||n[0]==192&&n[1]==168);}
    private static String text(JsonNode n,String k){require(n.path(k).isTextual()&&!n.path(k).textValue().isBlank(),"typed lease text required");return n.path(k).textValue();}
    public static void require(boolean ok,String reason){if(!ok)throw invalid(reason);}
    private static IllegalArgumentException invalid(String reason){return new IllegalArgumentException("reviewed_mariadb: "+reason);}
}
