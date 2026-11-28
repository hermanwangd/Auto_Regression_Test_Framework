package com.specdriven.regression.contract.v03.assertion;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class V03SchemaReferenceVectorsTest {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    // Zero-based schema group indices in the pinned, unmodified files. See the adjacent resource README.
    private record Fixture(String keyword, int valid, int invalid, String sha256, int... groups) {}

    private static final List<Fixture> FIXTURES = List.of(
            new Fixture("type", 21, 59, "ba9818a7b63bba299573c9c5bc7b4c9d97298a9539d84a2377800b0947ad4ecd",
                    0, 1, 2, 3, 4, 5, 6, 7, 9, 10, 11, 12),
            new Fixture("enum", 24, 31, "978cebb8d71c947e777739b3e3eaa9dbe6470f0911187c1abe3c3839ea850928",
                    0, 1, 2, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17),
            new Fixture("items", 5, 3, "c1a810f014fe0588ad66fb5787bc99f18d004b7d53f928030bf5f5ee10603308",
                    0, 10, 11),
            new Fixture("properties", 10, 6, "65d22eba201bf311aa8d95682d858795389eca1a109ba6972f7bd2cda3a9c563",
                    0, 3, 4, 5),
            new Fixture("required", 12, 6, "8741afa4d6ba986c0ff72f20ccffd0d1213445e88f784cfd9025ee766f426c1e",
                    1, 2, 4, 5, 6));

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    void matchesUpstreamVerdict(String name, Object schema, Object instance, boolean valid) {
        String mismatch = V03SchemaMatcher.mismatch(schema, instance);
        if (valid) {
            assertThat(mismatch).as(name).isEmpty();
        } else {
            assertThat(mismatch).as(name).startsWith("instance at ");
        }
    }

    static List<Arguments> vectors() throws IOException, NoSuchAlgorithmException {
        var vectors = new ArrayList<Arguments>();
        for (Fixture fixture : FIXTURES) {
            String file = fixture.keyword() + ".json";
            JsonNode groups = readPinnedFixture(file, fixture.sha256()).required("tests");
            int valid = 0;
            int invalid = 0;
            for (int index : fixture.groups()) {
                JsonNode group = groups.required(index);
                Object schema = JSON.treeToValue(group.required("schema"), Object.class);
                for (JsonNode test : group.required("tests")) {
                    String name = file + "[" + index + "] " + group.required("description").textValue()
                            + " / " + test.required("description").textValue();
                    Object instance = JSON.treeToValue(test.required("data"), Object.class);
                    boolean expected = test.required("valid").booleanValue();
                    vectors.add(Arguments.of(name, schema, instance, expected));
                    if (expected) valid++;
                    else invalid++;
                }
            }
            assertThat(valid).as("%s valid instances", file).isEqualTo(fixture.valid());
            assertThat(invalid).as("%s invalid instances", file).isEqualTo(fixture.invalid());
        }
        assertThat(vectors).hasSize(177);
        return vectors;
    }

    private static JsonNode readPinnedFixture(String file, String sha256) throws IOException, NoSuchAlgorithmException {
        String resource = "/json-schema-test-suite/" + file;
        try (InputStream stream = V03SchemaReferenceVectorsTest.class.getResourceAsStream(resource)) {
            byte[] bytes = Objects.requireNonNull(stream, resource).readAllBytes();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            assertThat(HexFormat.of().formatHex(digest)).as("pinned bytes of %s", file).isEqualTo(sha256);
            return JSON.readTree(bytes);
        }
    }
}
