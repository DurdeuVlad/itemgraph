package com.itemgraph.query;

import java.sql.ResultSet;
import java.sql.SQLException;

final class EdgeConfidence {
    private EdgeConfidence() {}

    static double readRequired(ResultSet rs, long edgeId) throws SQLException {
        double confidence = rs.getDouble("e_confidence");
        if (rs.wasNull()) {
            throw new SQLException("Inferred edge #" + edgeId + " has no stored confidence.");
        }
        return confidence;
    }
}
