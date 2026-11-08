package com.specdriven.regression.producttest;
import static org.assertj.core.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
class ReviewedMariaDbCapabilityTest {
 @Test void reviewedJdbcAdmitsOnlyFiniteOwnedReadonlyProfile() {
  assertThatCode(()->ReviewedNativeCapability.fromContract("jdbc.v0.3")).doesNotThrowAnyException();
  var c=ReviewedNativeCapability.fromContract("jdbc.v0.3");
  assertThat(c.providerType()).isEqualTo("jdbc"); assertThat(c.operation()).isEqualTo("db_query");
  c.validateBindings(Map.of("dialect","mariadb","profile","owned-mariadb-readonly.v1","connection.secret_ref","memory://owned-mariadb","endpoint","172.17.0.2:3306","owner_run_id","a".repeat(32)));
  c.validateInputs(Map.of("query_ref","artifact://queries/owned-readonly.sql"));
 }
 @Test void reviewedJdbcRejectsArbitraryQueryOrSecretTransport() {
  assertThatCode(()->ReviewedNativeCapability.fromContract("jdbc.v0.3")).doesNotThrowAnyException();
  var c=ReviewedNativeCapability.fromContract("jdbc.v0.3");
  assertThatThrownBy(()->c.validateInputs(Map.of("query_ref","artifact://other.sql"))).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->c.validateInputs(Map.of("query_ref","artifact://queries/owned-readonly.sql","params.x","1"))).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->c.validateBindings(Map.of("dialect","mariadb","profile","owned-mariadb-readonly.v1","connection.secret_ref","env://PASSWORD","endpoint","172.17.0.2:3306","owner_run_id","a".repeat(32)))).isInstanceOf(IllegalArgumentException.class);
 }
}
