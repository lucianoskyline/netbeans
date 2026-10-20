/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.db.tools;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Properties;
import org.netbeans.api.db.explorer.ConnectionManager;
import org.netbeans.api.db.explorer.DatabaseConnection;
import org.netbeans.api.db.explorer.DatabaseException;
import org.netbeans.api.db.explorer.DatabaseMetaDataTransfer;
import org.netbeans.api.db.explorer.JDBCDriver;
import org.netbeans.api.db.explorer.JDBCDriverManager;
import org.netbeans.api.db.sql.support.SQLIdentifiers;
import org.openide.nodes.Node;

/**
 * Bridges the Database explorer (public transfer API only) and plain JDBC:
 * which connection/table/column a node is, and how to talk to its database.
 */
final class Db {

    private Db() {
    }

    /** A table as shown in the explorer; catalog/schema are resolved against the metadata. */
    static final class TableRef {

        final DatabaseConnection connection;
        final JDBCDriver driver;
        final String owner;
        final String table;
        String catalog;
        String schema;

        TableRef(DatabaseConnection connection, JDBCDriver driver, String owner, String table) {
            this.connection = connection;
            this.driver = driver;
            this.owner = owner;
            this.table = table;
        }

        /** Decides whether the explorer's parent folder is a schema or a catalog (MySQL databases are catalogs). */
        void resolve(DatabaseMetaData md) throws SQLException {
            if (owner == null) {
                schema = connection.getSchema();
                return;
            }
            if (exists(md, null, owner, table)) {
                schema = owner;
            } else if (exists(md, owner, null, table)) {
                catalog = owner;
            } else {
                schema = owner;
            }
        }

        String qualified(DatabaseMetaData md) throws SQLException {
            SQLIdentifiers.Quoter q = SQLIdentifiers.createQuoter(md);
            String prefix = schema != null ? q.quoteIfNeeded(schema) + "." // NOI18N
                    : catalog != null ? q.quoteIfNeeded(catalog) + "." : ""; // NOI18N
            return prefix + q.quoteIfNeeded(table);
        }
    }

    private static boolean exists(DatabaseMetaData md, String catalog, String schema, String table) throws SQLException {
        try (ResultSet rs = md.getTables(catalog, schema, table, null)) {
            return rs.next();
        }
    }

    private static <T> T transfer(Node node, DataFlavor flavor, Class<T> type) {
        try {
            Transferable t = node.clipboardCopy();
            if (t != null && t.isDataFlavorSupported(flavor)) {
                return type.cast(t.getTransferData(flavor));
            }
        } catch (Exception ex) {
            // not a database node of that kind
        }
        return null;
    }

    static DatabaseMetaDataTransfer.Connection connectionOf(Node node) {
        return transfer(node, DatabaseMetaDataTransfer.CONNECTION_FLAVOR, DatabaseMetaDataTransfer.Connection.class);
    }

    /** The connection above {@code node} (or the node itself), plus the names on the way up. */
    static DatabaseMetaDataTransfer.Connection connectionAbove(Node node, java.util.List<String> namesOnPath) {
        for (Node n = node; n != null; n = n.getParentNode()) {
            DatabaseMetaDataTransfer.Connection c = connectionOf(n);
            if (c != null) {
                return c;
            }
            namesOnPath.add(n.getName());
        }
        return null;
    }

    static TableRef tableOf(Node node) {
        DatabaseMetaDataTransfer.Table t = transfer(node, DatabaseMetaDataTransfer.TABLE_FLAVOR, DatabaseMetaDataTransfer.Table.class);
        if (t == null) {
            return null;
        }
        // explorer layout: Connection > [Catalog] > [Schema] > "Tables" > table
        Node list = node.getParentNode();
        Node ownerNode = list == null ? null : list.getParentNode();
        String owner = ownerNode == null || connectionOf(ownerNode) != null ? null : ownerNode.getName();
        return new TableRef(t.getDatabaseConnection(), t.getJDBCDriver(), owner, t.getTableName());
    }

    /** @return {table, column} or null */
    static Object[] columnOf(Node node) {
        DatabaseMetaDataTransfer.Column c = transfer(node, DatabaseMetaDataTransfer.COLUMN_FLAVOR, DatabaseMetaDataTransfer.Column.class);
        if (c == null || node.getParentNode() == null) {
            return null;
        }
        TableRef table = tableOf(node.getParentNode());
        return table == null ? null : new Object[] {table, c.getColumnName()};
    }

    static JDBCDriver driverFor(DatabaseConnection dc) {
        JDBCDriver[] drivers = JDBCDriverManager.getDefault().getDrivers(dc.getDriverClass());
        return drivers.length > 0 ? drivers[0] : null;
    }

    /**
     * A JDBC connection for background work. A private connection is opened when
     * the password is known, so tools never interfere with the SQL editor's
     * session; otherwise the explorer's shared connection is used (and not closed).
     */
    static final class Session implements AutoCloseable {

        final Connection connection;
        private final boolean owned;

        private Session(Connection connection, boolean owned) {
            this.connection = connection;
            this.owned = owned;
        }

        @Override
        public void close() {
            if (owned) {
                try {
                    connection.close();
                } catch (SQLException ex) {
                    // nothing to do
                }
            }
        }
    }

    /** May block or show the connect dialog; never call on the EDT. */
    static Session open(DatabaseConnection dc, JDBCDriver driver, boolean allowShared) throws SQLException {
        Connection own = openPrivate(dc, driver);
        if (own != null) {
            return new Session(own, true);
        }
        if (!allowShared) {
            return null;
        }
        Connection shared = dc.getJDBCConnection();
        if (shared == null) {
            try {
                ConnectionManager.getDefault().connect(dc);
            } catch (DatabaseException ex) {
                throw new SQLException(ex.getMessage(), ex);
            }
            shared = dc.getJDBCConnection();
        }
        if (shared == null) {
            throw new SQLException("Not connected to " + dc.getDisplayName()); // NOI18N
        }
        return new Session(shared, false);
    }

    static Connection openPrivate(DatabaseConnection dc, JDBCDriver driver) {
        if (driver == null || dc.getPassword() == null && dc.getUser() != null && !dc.getUser().isEmpty()) {
            return null;
        }
        try {
            Driver d = driver.getDriver();
            Properties props = new Properties();
            if (dc.getUser() != null) {
                props.put("user", dc.getUser()); // NOI18N
            }
            if (dc.getPassword() != null) {
                props.put("password", dc.getPassword()); // NOI18N
            }
            return d.connect(dc.getDatabaseURL(), props);
        } catch (DatabaseException | SQLException ex) {
            return null;
        }
    }

    enum Dialect {
        POSTGRES, MYSQL, ORACLE, SQLSERVER, DERBY, H2, SQLITE, HSQLDB, OTHER;

        static Dialect of(DatabaseMetaData md) throws SQLException {
            String p = md.getDatabaseProductName().toLowerCase(Locale.ROOT);
            if (p.contains("postgres")) return POSTGRES; // NOI18N
            if (p.contains("mysql") || p.contains("mariadb")) return MYSQL; // NOI18N
            if (p.contains("oracle")) return ORACLE; // NOI18N
            if (p.contains("microsoft") || p.contains("sql server")) return SQLSERVER; // NOI18N
            if (p.contains("derby")) return DERBY; // NOI18N
            if (p.equals("h2")) return H2; // NOI18N
            if (p.contains("sqlite")) return SQLITE; // NOI18N
            if (p.contains("hsql")) return HSQLDB; // NOI18N
            return OTHER;
        }

        String renameTable(String qualifiedTable, String plainTable, String qualifier, String newName) {
            return switch (this) {
                case MYSQL -> "RENAME TABLE " + qualifiedTable + " TO " + (qualifier == null ? "" : qualifier + ".") + newName; // NOI18N
                case DERBY -> "RENAME TABLE " + qualifiedTable + " TO " + newName; // NOI18N
                case SQLSERVER -> "EXEC sp_rename '" + plainTable + "', '" + newName + "'"; // NOI18N
                default -> "ALTER TABLE " + qualifiedTable + " RENAME TO " + newName; // NOI18N
            };
        }

        String renameColumn(String qualifiedTable, String plainTable, String column, String newName) {
            return switch (this) {
                case DERBY -> "RENAME COLUMN " + qualifiedTable + "." + column + " TO " + newName; // NOI18N
                case SQLSERVER -> "EXEC sp_rename '" + plainTable + "." + column + "', '" + newName + "', 'COLUMN'"; // NOI18N
                default -> "ALTER TABLE " + qualifiedTable + " RENAME COLUMN " + column + " TO " + newName; // NOI18N
            };
        }

        /** A statement that plans {@code sql} without running it, or null when the database has none. */
        String explain(String sql) {
            return switch (this) {
                case POSTGRES, MYSQL, H2, SQLITE -> "EXPLAIN " + sql; // NOI18N
                case HSQLDB -> "EXPLAIN PLAN FOR " + sql; // NOI18N
                default -> null; // Oracle writes a plan table, SQL Server needs SHOWPLAN, Derby has none
            };
        }

        int explainPrefixLength() {
            return this == HSQLDB ? "EXPLAIN PLAN FOR ".length() : "EXPLAIN ".length(); // NOI18N
        }
    }
}
