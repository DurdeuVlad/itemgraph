package com.itemgraph.db.migration;

import com.itemgraph.db.DatabaseDialect;
import java.sql.Connection;
import java.sql.SQLException;

public interface SchemaMigration {
    int getVersion();
    String getDescription();
    void apply(Connection conn) throws SQLException;

    /**
     * Applies this migration with an explicit storage dialect. Existing migrations
     * remain valid through the one-argument method; migrations that need a native
     * generated-column or index shape can override this overload.
     */
    default void apply(Connection conn, DatabaseDialect dialect) throws SQLException {
        apply(conn);
    }
}
