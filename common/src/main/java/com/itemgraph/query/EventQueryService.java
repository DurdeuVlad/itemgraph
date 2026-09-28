package com.itemgraph.query;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Backs {@code /ig event <observationId>}: one raw observation, fully resolved.
 *
 * <p>Reads only. This service exists so the Brigadier handler in
 * {@code ItemGraphCommands} is a thin wrapper with no SQL in it — the query logic is
 * then testable against a real SQLite database without a running Minecraft server, and
 * the command layer is left with nothing to get wrong except argument parsing.
 *
 * <p>A missing id is an {@link Optional#empty()} result, not an exception: "there is no
 * observation 42" is a legitimate answer to a forensic question, and the caller renders
 * it as a clear message rather than a stack trace.
 */
public final class EventQueryService {

    private static final String BY_ID = ObservationQueries.SELECT_FROM + " WHERE o.id = ?";

    public Optional<ObservationDetail> findObservation(Connection conn, long observationId) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(BY_ID)) {
            pstmt.setLong(1, observationId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(ObservationQueries.map(rs));
            }
        }
    }
}
