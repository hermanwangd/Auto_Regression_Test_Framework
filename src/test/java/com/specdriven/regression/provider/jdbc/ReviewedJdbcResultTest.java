package com.specdriven.regression.provider.jdbc;
import static org.assertj.core.api.Assertions.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class ReviewedJdbcResultTest {
 Object normalize(ResultSet rs)throws Exception {Class<?> c;try{c=Class.forName("com.specdriven.regression.provider.jdbc.ReviewedJdbcResult");}catch(ClassNotFoundException e){throw new AssertionError("Missing bounded JDBC result conversion",e);}try{return c.getMethod("read",ResultSet.class).invoke(null,rs);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception x)throw x;throw e;}}
 @Test void explicitSqlNullCountAndColumnTypesSurviveNormalization()throws Exception {
  Map<?,?> payload=(Map<?,?>)normalize(rows(List.of(Arrays.asList(1,"swarm-probe",2L),Arrays.asList(2,null,2L)),Types.BIGINT));
  assertThat(payload.get("row_count")).isEqualTo(2);List<?> result=(List<?>)payload.get("rows");Map<?,?> second=(Map<?,?>)result.get(1);
  assertThat(second.containsKey("probe_value")).isTrue();assertThat(second.get("probe_value")).isNull();assertThat(second.get("null_columns")).isEqualTo(List.of("probe_value"));assertThat(second.get("total_rows")).isEqualTo(2L);
  assertThat(payload.get("columns")).isEqualTo(List.of(Map.of("name","probe_id","jdbc_type",4),Map.of("name","probe_value","jdbc_type",12),Map.of("name","total_rows","jdbc_type",-5)));
 }
 @Test void thirdRowIsRejectedInsteadOfSamplingSilently()throws Exception {
  assertThatThrownBy(()->normalize(rows(List.of(List.of(1,"a",3L),List.of(2,"b",3L),List.of(3,"c",3L)),Types.BIGINT))).hasMessageContaining("RESULT_LIMIT");
 }
 @Test void wrongTypeAndOversizedUtf8CellAreRejected()throws Exception {
  assertThatThrownBy(()->normalize(rows(List.of(List.of(1,"a",2L)),Types.VARCHAR))).hasMessageContaining("RESULT_TYPE");
  assertThatThrownBy(()->normalize(rows(List.of(List.of(1,"é".repeat(2049),2L)),Types.BIGINT))).hasMessageContaining("RESULT_LIMIT");
 }
 static ResultSet rows(List<List<Object>> rows,int countType) {
  int[] position={-1};boolean[] nil={false};String[] names={"probe_id","probe_value","total_rows"};int[] types={Types.INTEGER,Types.VARCHAR,countType};
  var metadata=(ResultSetMetaData)Proxy.newProxyInstance(ReviewedJdbcResultTest.class.getClassLoader(),new Class<?>[]{ResultSetMetaData.class},(p,m,a)->switch(m.getName()){case "getColumnCount"->3;case "getColumnLabel"->names[(int)a[0]-1];case "getColumnType"->types[(int)a[0]-1];default->throw new UnsupportedOperationException(m.getName());});
  return (ResultSet)Proxy.newProxyInstance(ReviewedJdbcResultTest.class.getClassLoader(),new Class<?>[]{ResultSet.class},(p,m,a)->switch(m.getName()){
   case "close"->null;case "getMetaData"->metadata;case "next"->++position[0]<rows.size();case "wasNull"->nil[0];
   case "getString","getInt","getLong"->{Object value=rows.get(position[0]).get((int)a[0]-1);nil[0]=value==null;if(m.getName().equals("getString"))yield value;if(m.getName().equals("getInt"))yield value==null?0:((Number)value).intValue();yield value==null?0L:((Number)value).longValue();}
   default->throw new UnsupportedOperationException(m.getName());});
 }
}
