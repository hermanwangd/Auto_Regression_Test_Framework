package com.specdriven.regression.provider.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Bounded typed conversion for the fixed three-column SELECT only. */
public final class ReviewedJdbcResult {
    private ReviewedJdbcResult() {}
    public static Map<String,Object> read(ResultSet input)throws SQLException {return read(input,value->{});}
    /** Protect bounded raw VARCHAR values before any JSON serialization. */
    static Map<String,Object> read(ResultSet input,java.util.function.Consumer<String> rawValueGuard)throws SQLException {
        Objects.requireNonNull(rawValueGuard,"raw result guard required");
        String[] names={"probe_id","probe_value","total_rows"};int[] types={Types.INTEGER,Types.VARCHAR,Types.BIGINT};
        ResultSetMetaData metadata=input.getMetaData();require(metadata.getColumnCount()==3,"RESULT_TYPE");
        List<Map<String,Object>> columns=new ArrayList<>();
        for(int i=0;i<3;i++) {require(names[i].equals(metadata.getColumnLabel(i+1))&&metadata.getColumnType(i+1)==types[i],"RESULT_TYPE");columns.add(Map.of("name",names[i],"jdbc_type",types[i]));}
        List<Map<String,Object>> rows=new ArrayList<>();
        while(input.next()) {
            require(rows.size()<2,"RESULT_LIMIT");int id=input.getInt(1);require(!input.wasNull(),"RESULT_TYPE");
            String value=input.getString(2);boolean isNull=input.wasNull();require(isNull?value==null:value!=null,"RESULT_TYPE");
            require(value==null||value.getBytes(StandardCharsets.UTF_8).length<=4096,"RESULT_LIMIT");if(value!=null)rawValueGuard.accept(value);
            long count=input.getLong(3);require(!input.wasNull(),"RESULT_TYPE");
            Map<String,Object> row=new LinkedHashMap<>();row.put("probe_id",id);row.put("probe_value",value);row.put("total_rows",count);row.put("null_columns",isNull?List.of("probe_value"):List.of());rows.add(Collections.unmodifiableMap(row));
        }
        Map<String,Object> result=Map.of("row_count",rows.size(),"columns",List.copyOf(columns),"rows",List.copyOf(rows));
        try {require(new ObjectMapper().writeValueAsBytes(result).length<=16384,"RESULT_LIMIT");}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalArgumentException("reviewed_mariadb: RESULT_TYPE");}
        return result;
    }
    private static void require(boolean valid,String code){if(!valid)throw new IllegalArgumentException("reviewed_mariadb: "+code);}
}
