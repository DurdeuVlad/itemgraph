package com.itemgraph.db.migration;

import java.sql.Connection;
import java.sql.SQLException;

public interface SchemaMigration {
    int getVersion();
    String getDescription();
    void apply(Connection conn) throws SQLException;
}
