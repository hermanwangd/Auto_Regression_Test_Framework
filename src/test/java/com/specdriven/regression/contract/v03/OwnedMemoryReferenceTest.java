package com.specdriven.regression.contract.v03;
import static org.assertj.core.api.Assertions.*;
import com.specdriven.regression.contract.v03.ref.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
/** Reserved nonsecret marker; no credential values, environment access or runtime provider. */
class OwnedMemoryReferenceTest {
 @TempDir Path root;final ObjectMapper json=new ObjectMapper();
 @Test void exactFixedTokenIsAReferenceMarkerRatherThanLiteralData() {
  var marker=new V03ReferenceParser().parse("memory://owned-mariadb");assertThat(marker.getClass().getSimpleName()).isEqualTo("OwnedMemory");assertThat(marker.toString()).doesNotContain("password","PUBLIC_SYNTHETIC");
 }
 @ParameterizedTest @ValueSource(strings={"memory://other","memory://owned-mariadb/password","memory://owned-mariadb?password=PUBLIC_SYNTHETIC","memory://owned-mariadb#fragment","memory://"})
 void alternateMemoryUrisAreRejectedWithoutEchoingContent(String uri) {assertThatThrownBy(()->new V03ReferenceParser().parse(uri)).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(uri);}
 @Test void ordinaryResolverDoesNotResolveTheMarkerAndLegacyEnvStillResolves() {
  var parser=new V03ReferenceParser();var marker=parser.parse("memory://owned-mariadb");assertThat(marker.getClass().getSimpleName()).isEqualTo("OwnedMemory");var reads=new AtomicInteger();var resolver=new V03ReferenceResolver(path->{reads.incrementAndGet();return Map.of();});var context=new V03ReferenceResolutionContext(root,Map.of(),"TC",Map.of(),Map.of(),Map.of("PUBLIC_ENV","PUBLIC_SYNTHETIC_ENV_VALUE"));
  assertThatThrownBy(()->resolver.resolveValue(marker,context)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("protected");assertThat(reads).hasValue(0);assertThat(resolver.resolveValue(parser.parse("env://PUBLIC_ENV"),context)).isEqualTo("PUBLIC_SYNTHETIC_ENV_VALUE");
 }
 Map<String,Object> contract(){return new LinkedHashMap<>(Map.of("contract_version","v0.3","provider_contract","jdbc.v0.3","provider_type","jdbc","runtime_modes",List.of("native"),"binding_keys",new LinkedHashMap<>(Map.of("connection.secret_ref",new LinkedHashMap<>(Map.of("required",false,"source","env_profile_secret_ref","value_type","string","reference_kinds",List.of("env","owned_memory"),"sensitivity","masked")))),"operation_defaults",new LinkedHashMap<>(Map.of("input",new LinkedHashMap<>(Map.of("value_type","string","reference_kinds",List.of("literal"),"sensitivity","public")),"output",Map.of("value_type","string","sensitivity","public"),"phases",List.of("execute"))),"operations",Map.of("db_query",Map.of("inputs",Map.of("query_ref",new LinkedHashMap<>(Map.of("required",true,"value_type","string","reference_kinds",List.of("artifact"),"sensitivity","public"))),"outputs",Map.of("result",Map.of("value_type","string","sensitivity","public"))))));}
 @SuppressWarnings("unchecked") Map<String,Object> map(Object value){return (Map<String,Object>)value;}
 void save(Map<String,Object> c)throws Exception {Files.createDirectories(root.resolve("catalog"));json.writeValue(root.resolve("catalog/jdbc.yaml").toFile(),c);}
 void positiveCatalog()throws Exception {save(contract());assertThatCode(()->new V03ProviderContractCatalog().loadDirectory(root.resolve("catalog"))).doesNotThrowAnyException();}
 @Test void onlyMaskedSecretRefDeclaresTheFiniteMemoryKind()throws Exception {positiveCatalog();var c=new V03ProviderContractCatalog().loadDirectory(root.resolve("catalog")).get("jdbc.v0.3");assertThat(c.bindings().get("connection.secret_ref").referenceKinds().stream().map(Enum::name)).containsExactlyInAnyOrder("ENV","OWNED_MEMORY").doesNotContain("LITERAL");}
 @ParameterizedTest @ValueSource(strings={"contract","provider","public","source","binding","literal","input_direct","input_effective_default","unused_default","output_default"})
 void memoryKindCannotEscapeThroughOtherMetadataOrEffectiveDefaults(String change)throws Exception {
  positiveCatalog();var c=contract();var bindings=map(c.get("binding_keys"));var secret=map(bindings.get("connection.secret_ref"));var defaults=map(c.get("operation_defaults"));var input=map(map(map(c.get("operations")).get("db_query")).get("inputs"));
  switch(change){case "contract"->c.put("provider_contract","rest_client.v0.3");case "provider"->c.put("provider_type","rest_client");case "public"->secret.put("sensitivity","public");case "source"->secret.put("source","env_profile");case "binding"->{bindings.remove("connection.secret_ref");bindings.put("other",secret);}case "literal"->secret.put("reference_kinds",List.of("env","literal","owned_memory"));case "input_direct"->map(input.get("query_ref")).put("reference_kinds",List.of("owned_memory"));case "input_effective_default"->{map(defaults.get("input")).put("reference_kinds",List.of("owned_memory"));map(input.get("query_ref")).remove("reference_kinds");}case "unused_default"->map(defaults.get("input")).put("reference_kinds",List.of("owned_memory"));case "output_default"->defaults.put("output",Map.of("value_type","string","sensitivity","public","reference_kinds",List.of("owned_memory")));default->throw new AssertionError();}
  save(c);assertThatThrownBy(()->new V03ProviderContractCatalog().loadDirectory(root.resolve("catalog"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("owned_memory");
 }
}
