package com.specdriven.regression.contract.v03;
import static org.assertj.core.api.Assertions.*;
import com.specdriven.regression.contract.ContractBaselineService;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
/** Metadata compilation only. Possessing the marker supplies no source, driver or backend permission. */
class OwnedMemoryPlanAdmissionTest {
 @TempDir Path root;ReviewedMariaDbHandoffTest owner;Path suite;
 @BeforeEach void fixture()throws Exception {owner=new ReviewedMariaDbHandoffTest();owner.temporary=root;suite=owner.fixture();}
 V03ExecutionPlan build(){return new V03ExecutionPlanBuilder(new ContractBaselineService(suite.resolve("contracts")),suite.resolve("contracts")).build(suite.resolve("suite_manifest.yaml"),"local_native");}
 @Test void exactNativeFiniteTargetCompilesWithoutResolvingCredentials() {assertThatCode(()->build()).doesNotThrowAnyException();assertThat(build().targets().get("todo_api").bindings().get("connection.secret_ref")).isEqualTo("memory://owned-mariadb");}
 @ParameterizedTest @ValueSource(strings={"profile","dialect","mode","remote","plaintext","nested","extra_binding","dsl_marker","provider","public_metadata","default_alias","knowledge","shared","dependency","stage"})
 void fixedReferenceCannotAdmitAnotherProfileOrPlaintext(String variant)throws Exception {
  assertThatCode(()->build()).doesNotThrowAnyException();var h=owner.h;var env=h.read(suite.resolve("env_profiles/local_native.yaml"));var target=h.map(h.map(env.get("targets")).get("todo_api"));var values=h.map(target.get("bindings"));
  switch(variant){case "profile"->values.put("profile","generic");case "dialect"->values.put("dialect","oracle");case "mode"->target.put("runtime_mode","ephemeral");case "remote"->values.put("endpoint","192.0.2.1:3306");case "plaintext"->values.put("connection.secret_ref","PUBLIC_SYNTHETIC_PLAINTEXT_MUST_REJECT");case "nested"->values.put("connection.secret_ref",Map.of("secret_ref","memory://owned-mariadb","password","PUBLIC_SYNTHETIC_PLAINTEXT_MUST_REJECT"));case "extra_binding"->{values.put("extra","unreviewed");var contract=h.read(suite.resolve("contracts/jdbc_v0_3.yaml"));h.map(contract.get("binding_keys")).put("extra",Map.of("required",false,"source","env_profile","value_type","string","reference_kinds",List.of("literal"),"sensitivity","public"));h.json.writeValue(suite.resolve("contracts/jdbc_v0_3.yaml").toFile(),contract);}case "provider","public_metadata","default_alias"->{var contract=h.read(suite.resolve("contracts/jdbc_v0_3.yaml"));if(variant.equals("provider"))contract.put("provider_type","rest_client");if(variant.equals("public_metadata"))h.map(h.map(contract.get("binding_keys")).get("connection.secret_ref")).put("sensitivity","public");if(variant.equals("default_alias"))h.map(h.map(contract.get("operation_defaults")).get("input")).put("reference_kinds",List.of("owned_memory"));h.json.writeValue(suite.resolve("contracts/jdbc_v0_3.yaml").toFile(),contract);}case "knowledge"->h.map(env.get("product_test")).put("knowledge_mode","required");case "shared"->h.map(env.get("product_test")).put("shared_state",Map.of("environment_id","fixture","namespace","fixture","owner","fixture"));case "dependency"->h.map(env.get("product_test")).put("dependency_modes",Map.of("other","real"));case "stage"->h.map(env.get("product_test")).put("environment_stage","sit");case "dsl_marker"->{var document=h.read(suite.resolve("test_cases/items.yaml"));h.map(h.map(((List<?>)document.get("execute")).get(0)).get("with")).put("query_ref","memory://owned-mariadb");h.write(suite.resolve("test_cases/items.yaml"),document);}default->throw new AssertionError();}
  h.write(suite.resolve("env_profiles/local_native.yaml"),env);assertThatThrownBy(()->build()).isInstanceOf(IllegalArgumentException.class);
 }
 @ParameterizedTest @ValueSource(strings={"credential","targets.todo_api.bindings.connection.secret_ref"})
 void admittedPointerCannotSuppressAnotherFieldOrDottedKeyCollision(String field)throws Exception {
  assertThatCode(()->build()).doesNotThrowAnyException();var env=owner.h.read(suite.resolve("env_profiles/local_native.yaml"));env.put(field,"memory://owned-mariadb");owner.h.write(suite.resolve("env_profiles/local_native.yaml"),env);
  var checked=new ContractBaselineService(suite.resolve("contracts")).validateSuite(suite.resolve("suite_manifest.yaml"));assertThat(checked.findings()).anySatisfy(f->{assertThat(f.reason()).isEqualTo("raw_secret");assertThat(f.fieldPath()).isEqualTo(field);});assertThatThrownBy(()->build()).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void testDocumentNeverReceivesTheEnvProfileReferenceException()throws Exception {
  assertThatCode(()->build()).doesNotThrowAnyException();var document=owner.h.read(suite.resolve("test_cases/items.yaml"));document.put("credential","memory://owned-mariadb");owner.h.write(suite.resolve("test_cases/items.yaml"),document);var checked=new ContractBaselineService(suite.resolve("contracts")).validateSuite(suite.resolve("suite_manifest.yaml"));assertThat(checked.findings()).anySatisfy(f->{assertThat(f.reason()).isEqualTo("raw_secret");assertThat(f.fieldPath()).isEqualTo("credential");});
 }
}
