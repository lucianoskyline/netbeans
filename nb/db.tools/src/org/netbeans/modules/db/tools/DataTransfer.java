/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.db.tools;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;
import org.netbeans.api.db.sql.support.SQLIdentifiers;

/** Table data to/from files: CSV, JSON and SQL INSERT export; CSV import. */
final class DataTransfer {

    enum Format {
        CSV("csv"), JSON("json"), SQL("sql"); // NOI18N

        final String extension;

        Format(String extension) {
            this.extension = extension;
        }

        static Format of(File f) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            for (Format fmt : values()) {
                if (n.endsWith("." + fmt.extension)) { // NOI18N
                    return fmt;
                }
            }
            return CSV;
        }
    }

    private DataTransfer() {
    }

    /** @return number of rows written */
    static int export(Connection c, String qualifiedTable, File file, Format format, IntConsumer progress)
            throws SQLException, IOException {
        try (Statement st = c.createStatement()) {
            st.setFetchSize(1000);
            try (ResultSet rs = st.executeQuery("SELECT * FROM " + qualifiedTable); // NOI18N
                    Writer w = new BufferedWriter(Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8))) {
                return write(rs, w, c, qualifiedTable, format, progress);
            }
        }
    }

    private static int write(ResultSet rs, Writer w, Connection c, String qualifiedTable, Format format,
            IntConsumer progress) throws SQLException, IOException {
        {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            String[] names = new String[n];
            for (int i = 0; i < n; i++) {
                names[i] = md.getColumnLabel(i + 1);
            }
            SQLIdentifiers.Quoter q = SQLIdentifiers.createQuoter(c.getMetaData());
            String insertPrefix = null;
            if (format == Format.SQL) {
                List<String> cols = new ArrayList<>();
                for (String name : names) {
                    cols.add(q.quoteIfNeeded(name));
                }
                insertPrefix = "INSERT INTO " + qualifiedTable + " (" + String.join(", ", cols) + ") VALUES ("; // NOI18N
            }
            if (format == Format.CSV) {
                for (int i = 0; i < n; i++) {
                    w.write((i > 0 ? "," : "") + csv(names[i])); // NOI18N
                }
                w.write("\r\n"); // NOI18N
            } else if (format == Format.JSON) {
                w.write("[\n"); // NOI18N
            }
            int rows = 0;
            while (rs.next()) {
                switch (format) {
                    case CSV -> {
                        for (int i = 0; i < n; i++) {
                            Object v = rs.getObject(i + 1);
                            w.write((i > 0 ? "," : "") + (v == null ? "" : csv(text(v)))); // NOI18N
                        }
                        w.write("\r\n"); // NOI18N
                    }
                    case JSON -> {
                        w.write(rows > 0 ? ",\n  {" : "  {"); // NOI18N
                        for (int i = 0; i < n; i++) {
                            w.write((i > 0 ? ", " : "") + json(names[i]) + ": " + jsonValue(rs.getObject(i + 1), md.getColumnType(i + 1))); // NOI18N
                        }
                        w.write("}"); // NOI18N
                    }
                    case SQL -> {
                        w.write(insertPrefix);
                        for (int i = 0; i < n; i++) {
                            w.write((i > 0 ? ", " : "") + sqlLiteral(rs.getObject(i + 1), md.getColumnType(i + 1))); // NOI18N
                        }
                        w.write(");\n"); // NOI18N
                    }
                }
                rows++;
                if (rows % 1000 == 0) {
                    progress.accept(rows);
                }
            }
            if (format == Format.JSON) {
                w.write(rows > 0 ? "\n]\n" : "]\n"); // NOI18N
            }
            return rows;
        }
    }

    private static String text(Object v) {
        if (v instanceof byte[]) {
            StringBuilder sb = new StringBuilder();
            for (byte b : (byte[]) v) {
                sb.append(String.format("%02x", b)); // NOI18N
            }
            return sb.toString();
        }
        return String.valueOf(v);
    }

    static String csv(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) { // NOI18N
            return "\"" + s.replace("\"", "\"\"") + "\""; // NOI18N
        }
        return s;
    }

    static String json(String s) {
        StringBuilder sb = new StringBuilder("\""); // NOI18N
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\""); // NOI18N
                case '\\' -> sb.append("\\\\"); // NOI18N
                case '\n' -> sb.append("\\n"); // NOI18N
                case '\r' -> sb.append("\\r"); // NOI18N
                case '\t' -> sb.append("\\t"); // NOI18N
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch)); // NOI18N
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static boolean isNumeric(int type) {
        return switch (type) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.REAL, Types.FLOAT,
                    Types.DOUBLE, Types.DECIMAL, Types.NUMERIC -> true;
            default -> false;
        };
    }

    static String jsonValue(Object v, int type) {
        if (v == null) {
            return "null"; // NOI18N
        }
        if (v instanceof Boolean) {
            return v.toString();
        }
        if (isNumeric(type) && v instanceof Number) {
            String s = v instanceof BigDecimal ? ((BigDecimal) v).toPlainString() : v.toString();
            return s.equals("NaN") || s.contains("Infinity") ? json(s) : s; // NOI18N
        }
        return json(text(v));
    }

    static String sqlLiteral(Object v, int type) {
        if (v == null) {
            return "NULL"; // NOI18N
        }
        if (v instanceof Boolean) {
            return ((Boolean) v) ? "TRUE" : "FALSE"; // NOI18N
        }
        if (isNumeric(type) && v instanceof Number) {
            return v instanceof BigDecimal ? ((BigDecimal) v).toPlainString() : v.toString();
        }
        return "'" + text(v).replace("'", "''") + "'"; // NOI18N
    }

    /** RFC 4180 reader; returns null at end of input. */
    static List<String> readCsvRecord(BufferedReader r) throws IOException {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        int ch;
        while ((ch = r.read()) != -1) {
            any = true;
            if (quoted) {
                if (ch == '"') {
                    r.mark(1);
                    int next = r.read();
                    if (next == '"') {
                        field.append('"');
                    } else {
                        quoted = false;
                        if (next != -1) {
                            r.reset();
                        }
                    }
                } else {
                    field.append((char) ch);
                }
            } else if (ch == '"' && field.length() == 0) {
                quoted = true;
            } else if (ch == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n') {
                break;
            } else if (ch != '\r') {
                field.append((char) ch);
            }
        }
        if (!any) {
            return null;
        }
        fields.add(field.toString());
        return fields;
    }

    /** Inserts all rows in one transaction; any failure rolls everything back. @return rows inserted */
    static int importCsv(Connection c, String qualifiedTable, File file, IntConsumer progress)
            throws SQLException, IOException {
        Map<String, Integer> types = new HashMap<>();
        Map<String, String> realNames = new HashMap<>();
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT * FROM " + qualifiedTable + " WHERE 1 = 0")) { // NOI18N
            ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                String name = md.getColumnName(i);
                types.put(name.toLowerCase(Locale.ROOT), md.getColumnType(i));
                realNames.put(name.toLowerCase(Locale.ROOT), name);
            }
        }
        try (BufferedReader in = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            in.mark(1);
            if (in.read() != '﻿') { // skip UTF-8 BOM written by spreadsheets
                in.reset();
            }
            List<String> header = readCsvRecord(in);
            if (header == null) {
                return 0;
            }
            SQLIdentifiers.Quoter q = SQLIdentifiers.createQuoter(c.getMetaData());
            List<String> cols = new ArrayList<>();
            List<Integer> colTypes = new ArrayList<>();
            for (String h : header) {
                String key = h.trim().toLowerCase(Locale.ROOT);
                if (!types.containsKey(key)) {
                    throw new SQLException("CSV column \"" + h + "\" does not exist in " + qualifiedTable); // NOI18N
                }
                cols.add(q.quoteIfNeeded(realNames.get(key)));
                colTypes.add(types.get(key));
            }
            String sql = "INSERT INTO " + qualifiedTable + " (" + String.join(", ", cols) + ") VALUES (" // NOI18N
                    + String.join(", ", java.util.Collections.nCopies(cols.size(), "?")) + ")"; // NOI18N
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            int rows = 0;
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                List<String> record;
                int line = 1;
                while ((record = readCsvRecord(in)) != null) {
                    line++;
                    if (record.size() == 1 && record.get(0).isEmpty()) {
                        continue; // blank line
                    }
                    if (record.size() != cols.size()) {
                        throw new SQLException("Line " + line + ": expected " + cols.size() + " values, found " + record.size()); // NOI18N
                    }
                    for (int i = 0; i < cols.size(); i++) {
                        bind(ps, i + 1, record.get(i), colTypes.get(i), line);
                    }
                    ps.addBatch();
                    rows++;
                    if (rows % 500 == 0) {
                        ps.executeBatch();
                        progress.accept(rows);
                    }
                }
                ps.executeBatch();
                c.commit();
                return rows;
            } catch (SQLException | IOException | RuntimeException ex) {
                c.rollback();
                throw ex;
            } finally {
                c.setAutoCommit(autoCommit);
            }
        }
    }

    private static void bind(PreparedStatement ps, int index, String value, int type, int line) throws SQLException {
        if (value.isEmpty()) {
            ps.setNull(index, type);
            return;
        }
        try {
            switch (type) {
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> ps.setInt(index, Integer.parseInt(value.trim()));
                case Types.BIGINT -> ps.setLong(index, Long.parseLong(value.trim()));
                case Types.REAL, Types.FLOAT, Types.DOUBLE -> ps.setDouble(index, Double.parseDouble(value.trim()));
                case Types.DECIMAL, Types.NUMERIC -> ps.setBigDecimal(index, new BigDecimal(value.trim()));
                case Types.BIT, Types.BOOLEAN -> ps.setBoolean(index, parseBoolean(value.trim()));
                case Types.DATE -> ps.setDate(index, Date.valueOf(value.trim()));
                case Types.TIME -> ps.setTime(index, Time.valueOf(value.trim()));
                case Types.TIMESTAMP -> ps.setTimestamp(index, Timestamp.valueOf(value.trim().replace('T', ' ')));
                default -> ps.setString(index, value);
            }
        } catch (IllegalArgumentException ex) {
            throw new SQLException("Line " + line + ": invalid value \"" + value + "\" (" + ex.getMessage() + ")", ex); // NOI18N
        }
    }

    private static boolean parseBoolean(String v) {
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "1", "true", "t", "yes", "y" -> true; // NOI18N
            case "0", "false", "f", "no", "n" -> false; // NOI18N
            default -> throw new IllegalArgumentException("not a boolean"); // NOI18N
        };
    }
}
