package com.itemgraph.db.migration;

import com.itemgraph.db.DatabaseDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Dialect-neutral schema introspection used to make ALTER migrations retry-safe. */
final class MigrationSchema {
    private MigrationSchema() {
    }

    static boolean hasColumn(Connection conn, String table, String column) throws SQLException {
        return hasColumn(conn, DatabaseDialect.fromConnection(conn), table, column);
    }

    static boolean hasColumn(Connection conn, DatabaseDialect dialect, String table, String column)
            throws SQLException {
        if (!identifier(table) || !identifier(column)) {
            throw new IllegalArgumentException("table and column names must be simple SQL identifiers");
        }
        if (dialect == DatabaseDialect.MYSQL_MARIADB) {
            try (PreparedStatement stmt = conn.prepareStatement("""
                    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?
                    """)) {
                stmt.setString(1, table);
                stmt.setString(2, column);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next();
                }
            }
        }
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equals(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean identifier(String value) {
        return value != null && value.matches("[A-Za-z][A-Za-z0-9_]*");
    }
}
