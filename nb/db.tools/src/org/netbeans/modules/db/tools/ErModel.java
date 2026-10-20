/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.db.tools;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Tables, columns and foreign keys of one schema, read from JDBC metadata. */
final class ErModel {

    static final int MAX_TABLES = 400;
    private static final Logger LOG = Logger.getLogger(ErModel.class.getName());

    static final class Column {
        final String name;
        final String type;
        final boolean nullable;
        boolean primaryKey;
        boolean foreignKey;

        Column(String name, String type, boolean nullable) {
            this.name = name;
            this.type = type;
            this.nullable = nullable;
        }
    }

    static final class Table {
        final String name;
        final List<Column> columns = new ArrayList<>();

        Table(String name) {
            this.name = name;
        }

        Column column(String name) {
            for (Column c : columns) {
                if (c.name.equalsIgnoreCase(name)) {
                    return c;
                }
            }
            return null;
        }
    }

    static final class ForeignKey {
        final Table from;
        final Table to;
        final List<String> fromColumns = new ArrayList<>();

        ForeignKey(Table from, Table to) {
            this.from = from;
            this.to = to;
        }
    }

    final Map<String, Table> tables = new LinkedHashMap<>();
    final List<ForeignKey> foreignKeys = new ArrayList<>();
    boolean truncated;

    static ErModel load(DatabaseMetaData md, String catalog, String schema) throws SQLException {
        ErModel m = new ErModel();
        // all table types, not just "TABLE": MySQL reports its system databases as
        // "SYSTEM TABLE", PostgreSQL has "PARTITIONED TABLE"; views are left out
        try (ResultSet rs = md.getTables(catalog, schema, "%", null)) { // NOI18N
            while (rs.next()) {
                String kind = rs.getString("TABLE_TYPE"); // NOI18N
                if (kind == null || !kind.toUpperCase(java.util.Locale.ROOT).contains("TABLE")) { // NOI18N
                    continue;
                }
                if (m.tables.size() >= MAX_TABLES) {
                    m.truncated = true;
                    break;
                }
                String name = rs.getString("TABLE_NAME"); // NOI18N
                m.tables.put(name, new Table(name));
            }
        }
        for (Table t : m.tables.values()) {
            try (ResultSet rs = md.getColumns(catalog, schema, t.name, "%")) { // NOI18N
                while (rs.next()) {
                    String type = String.valueOf(rs.getString("TYPE_NAME")); // NOI18N
                    int size = rs.getInt("COLUMN_SIZE"); // NOI18N
                    if (size > 0 && size < 100000 && (type.toLowerCase().contains("char") || type.toLowerCase().contains("numeric") || type.toLowerCase().contains("decimal"))) { // NOI18N
                        type += "(" + size + ")"; // NOI18N
                    }
                    t.columns.add(new Column(rs.getString("COLUMN_NAME"), type, // NOI18N
                            rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls)); // NOI18N
                }
            }
            try (ResultSet rs = md.getPrimaryKeys(catalog, schema, t.name)) {
                while (rs.next()) {
                    Column c = t.column(rs.getString("COLUMN_NAME")); // NOI18N
                    if (c != null) {
                        c.primaryKey = true;
                    }
                }
            }
        }
        String source;
        if (Db.Dialect.of(md) == Db.Dialect.MYSQL) {
            // read FKs straight from the server; Connector/J's metadata path varies with connection settings
            source = "information_schema"; // NOI18N
            loadMysqlForeignKeys(m, md.getConnection(), catalog != null ? catalog : schema);
        } else {
            source = "JDBC metadata"; // NOI18N
            for (Table t : m.tables.values()) {
                try {
                    loadImportedKeys(m, md, catalog, schema, t);
                } catch (SQLException ex) {
                    LOG.log(Level.INFO, "Cannot read foreign keys of " + t.name, ex); // NOI18N
                }
            }
        }
        LOG.log(Level.INFO, "ER diagram of {0}: {1} tables, {2} relationships ({3})", // NOI18N
                new Object[] {catalog != null ? catalog : schema, m.tables.size(), m.foreignKeys.size(), source});
        return m;
    }

    /** Exact name first, then case-insensitive (servers differ in how they report identifier case). */
    Table table(String name) {
        Table t = tables.get(name);
        if (t == null && name != null) {
            for (Table candidate : tables.values()) {
                if (candidate.name.equalsIgnoreCase(name)) {
                    return candidate;
                }
            }
        }
        return t;
    }

    private void addColumn(ForeignKey fk, String column) {
        fk.fromColumns.add(column);
        Column c = fk.from.column(column);
        if (c != null) {
            c.foreignKey = true;
        }
    }

    private static void loadImportedKeys(ErModel m, DatabaseMetaData md, String catalog, String schema, Table t)
            throws SQLException {
        try (ResultSet rs = md.getImportedKeys(catalog, schema, t.name)) {
            ForeignKey current = null;
            while (rs.next()) {
                Table target = m.table(rs.getString("PKTABLE_NAME")); // NOI18N
                if (target == null) {
                    continue; // references another schema
                }
                // KEY_SEQ restarts at 1 for every key of a composite FK
                if (current == null || rs.getShort("KEY_SEQ") == 1 || current.to != target) { // NOI18N
                    current = new ForeignKey(t, target);
                    m.foreignKeys.add(current);
                }
                m.addColumn(current, rs.getString("FKCOLUMN_NAME")); // NOI18N
            }
        }
    }

    private static void loadMysqlForeignKeys(ErModel m, Connection c, String database) throws SQLException {
        String sql = "SELECT TABLE_NAME, CONSTRAINT_NAME, COLUMN_NAME, REFERENCED_TABLE_NAME " // NOI18N
                + "FROM information_schema.KEY_COLUMN_USAGE " // NOI18N
                + "WHERE TABLE_SCHEMA = ? AND REFERENCED_TABLE_SCHEMA = ? AND REFERENCED_TABLE_NAME IS NOT NULL " // NOI18N
                + "ORDER BY TABLE_NAME, CONSTRAINT_NAME, ORDINAL_POSITION"; // NOI18N
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, database);
            ps.setString(2, database);
            try (ResultSet rs = ps.executeQuery()) {
                ForeignKey current = null;
                String currentKey = null;
                while (rs.next()) {
                    Table from = m.table(rs.getString(1));
                    Table to = m.table(rs.getString(4));
                    if (from == null || to == null) {
                        continue;
                    }
                    String key = from.name + '\u0000' + rs.getString(2);
                    if (!key.equals(currentKey)) {
                        current = new ForeignKey(from, to);
                        m.foreignKeys.add(current);
                        currentKey = key;
                    }
                    m.addColumn(current, rs.getString(3));
                }
            }
        }
    }
}
