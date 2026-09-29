package com.itemgraph.db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Dialect-neutral schema introspection used to make ALTER migrations retry-safe. */
final class MigrationSchema {
    private MigrationSchema() {
    }

    static boolean hasColumn(Connection conn, String table, String column) throws SQLException {
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
}
