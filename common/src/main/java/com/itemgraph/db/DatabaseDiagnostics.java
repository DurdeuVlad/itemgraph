package com.itemgraph.db;

import java.sql.SQLException;

/** Builds database errors that retain useful SQL state without leaking connection details. */
final class DatabaseDiagnostics {
    private DatabaseDiagnostics() {
    }

    static SQLException redact(SQLException failure) {
        String sqlState = failure.getSQLState();
        String message = "ItemGraph database operation failed"
                + (sqlState == null ? "" : " (SQL state " + sqlState + ")");
        return new SQLException(message, sqlState, failure.getErrorCode());
    }
}
