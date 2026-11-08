package com.specdriven.regression.build;

import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Immutable build-resource availability; never accepts mission, environment or JVM enable flags. */
public final class RunnerBuildVariant {
    private static final String RESOURCE = "META-INF/regression/runner-build.properties";
    private static final Descriptor DESCRIPTOR = load();
    private RunnerBuildVariant() { }

    public static boolean ibmMqAvailable() {
        return DESCRIPTOR.valid() && DESCRIPTOR.ibmMqAvailable()
                && RunnerBuildVariant.class.getClassLoader().getResource("com/ibm/mq/MQException.class") != null;
    }
    public static String mqUnsupportedReason() {
        return "IBM MQ UNSUPPORTED in runner build variant `" + DESCRIPTOR.variant() + "`"
                + (DESCRIPTOR.valid() ? "; MQ client capability is omitted or not packaged." : "; build descriptor is unknown or invalid.");
    }
    public static Map<String,Object> describe() {
        Map<String,Object> info = new LinkedHashMap<>();
        info.put("format_version", "runner-build-info.v1");
        info.put("variant", DESCRIPTOR.variant());
        info.put("descriptor_status", DESCRIPTOR.valid() ? "VALID" : "UNKNOWN");
        info.put("descriptor_sha256", DESCRIPTOR.sha256());
        info.put("ibm_mq", ibmMqAvailable() ? "PACKAGED_UNVERIFIED" : "UNSUPPORTED");
        info.put("availability_scope", "PACKAGING_NOT_PROTOCOL_ACCEPTANCE");
        return Collections.unmodifiableMap(info);
    }
    private static Descriptor load() {
        try {
            var found = RunnerBuildVariant.class.getClassLoader().getResources(RESOURCE);
            if (!found.hasMoreElements()) return unknown("missing_descriptor", "");
            URL url = found.nextElement();
            if (found.hasMoreElements()) return unknown("duplicate_descriptor_resource", "");
            try (InputStream in = url.openStream()) { return parse(in.readNBytes(4097)); }
        } catch (Exception error) { return unknown("unreadable_descriptor", ""); }
    }
    static Descriptor parse(byte[] bytes) {
        if (bytes == null || bytes.length > 4096) return unknown("missing_or_oversized_descriptor", "");
        String digest = digest(bytes);
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            Map<String,String> values = new LinkedHashMap<>();
            for (String line : text.split("\\r?\\n", -1)) {
                if (line.isEmpty()) continue;
                int split = line.indexOf('=');
                if (split < 1 || values.putIfAbsent(line.substring(0, split), line.substring(split + 1)) != null)
                    return unknown("malformed_or_duplicate_key", digest);
            }
            if (!values.keySet().equals(Set.of("schema", "variant", "ibm_mq"))
                    || !"runner-build.v1".equals(values.get("schema"))) return unknown("unknown_descriptor_shape", digest);
            if ("full".equals(values.get("variant")) && "available".equals(values.get("ibm_mq")))
                return new Descriptor("full", true, true, "", digest);
            if ("no-ibm-mq".equals(values.get("variant")) && "unsupported".equals(values.get("ibm_mq")))
                return new Descriptor("no-ibm-mq", false, true, "", digest);
            return unknown("inconsistent_or_unknown_variant", digest);
        } catch (Exception error) { return unknown("malformed_descriptor_text", digest); }
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static Descriptor unknown(String reason, String digest) {
        return new Descriptor("UNKNOWN", false, false, reason, digest);
    }
    record Descriptor(String variant, boolean ibmMqAvailable, boolean valid, String reason, String sha256) { }
}
