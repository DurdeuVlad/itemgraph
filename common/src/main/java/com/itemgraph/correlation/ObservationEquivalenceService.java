package com.itemgraph.correlation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ObservationEquivalenceService {

    static final long MATCH_WINDOW_MS = 250L;
    private static final int MAX_OBSERVATIONS_PER_PASS = 500;
    private static final String INTERNAL_SOURCE = "ITEMGRAPH_INTERNAL";
    private static final String LEGACY_INTERNAL_SOURCE = "INTERNAL";
    private static final String EXTERNAL_SOURCE = "GRIEFLOGGER";

    private enum EventFamily {
        DROP,
        PICKUP
    }

    private static boolean isInternalSource(String sourceType) {
        return INTERNAL_SOURCE.equals(sourceType) || LEGACY_INTERNAL_SOURCE.equals(sourceType);
    }

    private record GroundObservation(
            long id,
            String sourceType,
            long timestampMs,
            long nodeId,
            Long targetNodeId,
            long fingerprintId,
            String actionType,
            int amount,
            String itemEntityUuid
    ) {
        EventFamily family() {
            return actionType.equals("PICKUP_ITEM") ? EventFamily.PICKUP : EventFamily.DROP;
        }

        long actorNodeId() {
            return family() == EventFamily.DROP ? nodeId
                    : targetNodeId == null ? Long.MIN_VALUE : targetNodeId;
        }

        String oppositeSource() {
            return isInternalSource(sourceType) ? EXTERNAL_SOURCE
                    : EXTERNAL_SOURCE.equals(sourceType) ? INTERNAL_SOURCE : null;
        }
    }

    public int reconcile(Connection conn, long nowMs) throws SQLException {
        Set<Long> handled = new LinkedHashSet<>();
        int groupsCreated = 0;

        for (GroundObservation observation : loadUncheckedObservations(conn)) {
            if (handled.contains(observation.id())) {
                continue;
            }
            if (observation.oppositeSource() == null) {
                markChecked(conn, observation.id(), nowMs, "NO_MATCH");
                handled.add(observation.id());
                continue;
            }

            List<GroundObservation> candidates = findCandidates(conn, observation);
            if (candidates.isEmpty()) {
                markChecked(conn, observation.id(), nowMs, "NO_MATCH");
                handled.add(observation.id());
                continue;
            }

            Map<Long, GroundObservation> members = new LinkedHashMap<>();
            members.put(observation.id(), observation);
            candidates.forEach(candidate -> members.put(candidate.id(), candidate));

            boolean confirmed = false;
            if (candidates.size() == 1 && isConfirmedMatch(observation, candidates.get(0))) {
                List<GroundObservation> reverse = findCandidates(conn, candidates.get(0));
                confirmed = reverse.size() == 1 && reverse.get(0).id() == observation.id();
                reverse.forEach(candidate -> members.put(candidate.id(), candidate));
            } else {
                GroundObservation representative = candidates.get(0);
                for (GroundObservation reverse : findCandidates(conn, representative)) {
                    if (isPossibleMatch(representative, reverse)) {
                        members.put(reverse.id(), reverse);
                    }
                }
            }

            createGroup(conn, new ArrayList<>(members.values()), confirmed, nowMs);
            handled.addAll(members.keySet());
            groupsCreated++;
        }
        return groupsCreated;
    }

    private List<GroundObservation> loadUncheckedObservations(Connection conn) throws SQLException {
        String sql = """
            SELECT o.id, o.source_type, o.timestamp_ms, o.node_id, o.target_node_id,
                   o.fingerprint_id, o.action_type, o.amount, o.item_entity_uuid
            FROM ig_observations o
            WHERE o.action_type IN ('DROP_ITEM', 'THROW_ITEM', 'SHOOT_ITEM', 'DEATH_DROP', 'PICKUP_ITEM',
                                    'DROP_CANCELLED', 'DROP_UNRESOLVED', 'DEATH_DROP_UNRESOLVED', 'DEATH_DROP_CANCELLED')
              AND NOT EXISTS (
                  SELECT 1 FROM ig_observation_match_checks c WHERE c.observation_id = o.id
              )
            ORDER BY CASE WHEN o.correlated_at IS NULL THEN 0 ELSE 1 END, o.id ASC
            LIMIT ?
        """;

        List<GroundObservation> observations = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, MAX_OBSERVATIONS_PER_PASS);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    observations.add(readObservation(rs));
                }
            }
        }
        return observations;
    }

    private List<GroundObservation> findCandidates(Connection conn, GroundObservation source) throws SQLException {
        String oppositeSource = source.oppositeSource();
        if (oppositeSource == null) {
            return List.of();
        }

        String sourceFilter = isInternalSource(source.sourceType())
                ? "o.source_type = 'GRIEFLOGGER'"
                : "o.source_type IN ('ITEMGRAPH_INTERNAL', 'INTERNAL')";
        String actionFilter = source.family() == EventFamily.PICKUP
                ? "o.action_type = 'PICKUP_ITEM'"
                : "o.action_type IN ('DROP_ITEM', 'THROW_ITEM', 'SHOOT_ITEM', 'DEATH_DROP', 'DROP_CANCELLED', 'DROP_UNRESOLVED', 'DEATH_DROP_UNRESOLVED', 'DEATH_DROP_CANCELLED')";
        String entityFilter = source.itemEntityUuid() == null
                ? "1 = 1"
                : "(o.item_entity_uuid = ? OR o.item_entity_uuid IS NULL)";
        String actorColumn = source.family() == EventFamily.DROP ? "o.node_id" : "o.target_node_id";
        String actorFilter = source.itemEntityUuid() == null
                ? actorColumn + " = ?"
                : "(" + actorColumn + " = ? OR o.item_entity_uuid = ?)";
        String sql = """
            SELECT o.id, o.source_type, o.timestamp_ms, o.node_id, o.target_node_id,
                   o.fingerprint_id, o.action_type, o.amount, o.item_entity_uuid
            FROM ig_observations o
            WHERE %s
              AND o.fingerprint_id = ?
              AND o.timestamp_ms BETWEEN ? AND ?
              AND %s
              AND %s
              AND %s
              AND NOT EXISTS (
                  SELECT 1 FROM ig_observation_group_members gm
                  WHERE gm.observation_id = o.id
              )
            ORDER BY ABS(o.timestamp_ms - ?) ASC, o.id ASC
        """.formatted(sourceFilter, actionFilter, entityFilter, actorFilter);

        List<GroundObservation> candidates = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, source.fingerprintId());
            pstmt.setLong(2, source.timestampMs() - MATCH_WINDOW_MS);
            pstmt.setLong(3, source.timestampMs() + MATCH_WINDOW_MS);
            int next = 4;
            if (source.itemEntityUuid() != null) {
                pstmt.setString(next++, source.itemEntityUuid());
            }
            pstmt.setLong(next++, source.actorNodeId());
            if (source.itemEntityUuid() != null) {
                pstmt.setString(next++, source.itemEntityUuid());
            }
            pstmt.setLong(next, source.timestampMs());
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    GroundObservation candidate = readObservation(rs);
                    if (isPossibleMatch(source, candidate)) {
                        candidates.add(candidate);
                    }
                }
            }
        }
        return candidates;
    }

    private static boolean isPossibleMatch(GroundObservation first, GroundObservation second) {
        if (first.family() != second.family()
                || first.fingerprintId() != second.fingerprintId()
                || Math.abs(first.timestampMs() - second.timestampMs()) > MATCH_WINDOW_MS) {
            return false;
        }
        if (first.itemEntityUuid() != null && second.itemEntityUuid() != null
                && !first.itemEntityUuid().equals(second.itemEntityUuid())) {
            return false;
        }
        boolean sharedEntityUuid = first.itemEntityUuid() != null
                && first.itemEntityUuid().equals(second.itemEntityUuid());
        if (first.family() == EventFamily.PICKUP
                && first.actorNodeId() != second.actorNodeId() && !sharedEntityUuid) {
            return false;
        }
        if (first.family() == EventFamily.DROP
                && (isUnresolvedAttempt(first.actionType()) || isUnresolvedAttempt(second.actionType()))) {
            return first.actorNodeId() == second.actorNodeId() && first.amount() == second.amount();
        }
        if (sharedEntityUuid) {
            return true;
        }
        return first.nodeId() == second.nodeId()
                && Objects.equals(first.targetNodeId(), second.targetNodeId())
                && first.actorNodeId() == second.actorNodeId()
                && first.amount() == second.amount();
    }

    private static boolean isConfirmedMatch(GroundObservation first, GroundObservation second) {
        return !isUnresolvedAttempt(first.actionType())
                && !isUnresolvedAttempt(second.actionType())
                && first.itemEntityUuid() != null
                && first.itemEntityUuid().equals(second.itemEntityUuid())
                && first.family() == second.family()
                && first.fingerprintId() == second.fingerprintId()
                && first.amount() == second.amount()
                && first.actorNodeId() == second.actorNodeId()
                && Math.abs(first.timestampMs() - second.timestampMs()) <= MATCH_WINDOW_MS;
    }

    private void createGroup(Connection conn, List<GroundObservation> observations,
                             boolean confirmed, long nowMs) throws SQLException {
        observations.sort(Comparator.comparingLong(GroundObservation::id));
        GroundObservation canonical = confirmed ? chooseCanonical(observations) : null;
        String state = confirmed ? "CONFIRMED" : "AMBIGUOUS";
        String matchBasis = confirmed ? "SHARED_ITEM_ENTITY_UUID" : ambiguousMatchBasis(observations);
        String explanation = confirmed
                ? "Distinct source event IDs share one ItemEntity UUID and matching action, fingerprint, amount, actor, and time window."
                : ambiguousGroupExplanation(observations, matchBasis);

        boolean originalAutoCommit = conn.getAutoCommit();
        try {
            conn.setAutoCommit(false);
            long groupId = insertGroup(conn, state, matchBasis, explanation, nowMs);
            insertMembers(conn, groupId, observations, canonical, confirmed, nowMs);
            if (confirmed) {
                supersedeAliasAllocations(conn, observations, canonical.id());
                refreshCanonicalStatus(conn, canonical, nowMs);
                addGroupMembersToExistingEdges(conn, groupId, canonical.id());
            } else {
                supersedeGroupAllocations(conn, observations);
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(originalAutoCommit);
        }
    }

    private long insertGroup(Connection conn, String state, String matchBasis,
                             String explanation, long nowMs) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO ig_observation_groups (state, match_basis, explanation, created_at_ms) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, state);
            pstmt.setString(2, matchBasis);
            pstmt.setString(3, explanation);
            pstmt.setLong(4, nowMs);
            pstmt.executeUpdate();
            try (ResultSet keys = pstmt.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("Failed to obtain observation group id");
    }

    private void insertMembers(Connection conn, long groupId, List<GroundObservation> observations,
                               GroundObservation canonical, boolean confirmed, long nowMs) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO ig_observation_group_members (group_id, observation_id, member_role) VALUES (?, ?, ?)")) {
            for (GroundObservation observation : observations) {
                String role = !confirmed ? "CANDIDATE"
                        : observation.id() == canonical.id() ? "CANONICAL" : "CORROBORATING";
                pstmt.setLong(1, groupId);
                pstmt.setLong(2, observation.id());
                pstmt.setString(3, role);
                pstmt.addBatch();
                if (!role.equals("CANONICAL")) {
                    updateObservationStatus(conn, observation.id(),
                            confirmed ? "CORROBORATING" : "SOURCE_AMBIGUOUS", nowMs);
                }
                markChecked(conn, observation.id(), nowMs, confirmed ? "CONFIRMED" : "AMBIGUOUS");
            }
            pstmt.executeBatch();
        }
    }

    private static String ambiguousMatchBasis(List<GroundObservation> observations) {
        if (observations.stream().anyMatch(observation -> isUnresolvedAttempt(observation.actionType()))) {
            return "UNRESOLVED_DROP_ATTEMPT_CONFLICT";
        }
        for (int i = 0; i < observations.size(); i++) {
            String uuid = observations.get(i).itemEntityUuid();
            if (uuid != null && observations.subList(i + 1, observations.size()).stream()
                    .anyMatch(other -> uuid.equals(other.itemEntityUuid()))) {
                return "SHARED_UUID_DETAILS_CONFLICT";
            }
        }
        return observations.size() > 2
                ? "NON_UNIQUE_SOURCE_SIGNATURE"
                : "SIGNATURE_WITHOUT_SHARED_ENTITY_UUID";
    }

    private static String ambiguousGroupExplanation(List<GroundObservation> observations, String matchBasis) {
        return switch (matchBasis) {
            case "UNRESOLVED_DROP_ATTEMPT_CONFLICT" ->
                    "One source records an unresolved or canceled drop attempt while another has a compatible ground event; neither endpoint is accepted as a proven transfer. No quantity capacity is allocated.";
            case "SHARED_UUID_DETAILS_CONFLICT" ->
                    "Rows share an ItemEntity UUID but actor, amount, action details, or uniqueness conflict. No independent quantity capacity is allocated.";
            case "NON_UNIQUE_SOURCE_SIGNATURE" ->
                    "Several cross-source rows share a compatible signature without a unique shared ItemEntity identity. No independent quantity capacity is allocated.";
            default ->
                    "Cross-source event signatures are compatible, but no shared ItemEntity identity proves that they are the same event. No independent quantity capacity is allocated.";
        };
    }

    private static GroundObservation chooseCanonical(List<GroundObservation> observations) {
        return observations.stream()
                .filter(observation -> observation.sourceType().equals(INTERNAL_SOURCE))
                .findFirst()
                .orElseGet(() -> observations.stream()
                        .filter(observation -> observation.sourceType().equals(LEGACY_INTERNAL_SOURCE))
                        .findFirst()
                        .orElse(observations.get(0)));
    }

    private void supersedeAliasAllocations(Connection conn, List<GroundObservation> observations,
                                           long canonicalId) throws SQLException {
        for (GroundObservation observation : observations) {
            if (observation.id() != canonicalId) {
                supersedeAllocations(conn, observation.id(), "SUPERSEDED_SOURCE_DUPLICATE");
            }
        }
    }

    private void supersedeGroupAllocations(Connection conn, List<GroundObservation> observations) throws SQLException {
        for (GroundObservation observation : observations) {
            supersedeAllocations(conn, observation.id(), "SUPERSEDED_SOURCE_AMBIGUITY");
        }
    }

    private void supersedeAllocations(Connection conn, long observationId, String edgeState) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement("""
                UPDATE ig_inferred_edges
                SET edge_state = ?
                WHERE edge_state = 'ACTIVE'
                  AND id IN (SELECT edge_id FROM ig_edge_allocations WHERE observation_id = ?)
                """)) {
            pstmt.setString(1, edgeState);
            pstmt.setLong(2, observationId);
            pstmt.executeUpdate();
        }
    }

    private void refreshCanonicalStatus(Connection conn, GroundObservation canonical, long nowMs) throws SQLException {
        String role = canonical.family() == EventFamily.DROP ? "SOURCE" : "DESTINATION";
        int allocated;
        try (PreparedStatement pstmt = conn.prepareStatement("""
                SELECT COALESCE(SUM(a.amount), 0)
                FROM ig_edge_allocations a
                JOIN ig_inferred_edges e ON e.id = a.edge_id
                WHERE a.observation_id = ? AND a.allocation_role = ? AND e.edge_state = 'ACTIVE'
                """)) {
            pstmt.setLong(1, canonical.id());
            pstmt.setString(2, role);
            try (ResultSet rs = pstmt.executeQuery()) {
                rs.next();
                allocated = rs.getInt(1);
            }
        }

        String status = allocated >= canonical.amount() ? "FULLY_ALLOCATED"
                : allocated > 0 ? "PARTIALLY_ALLOCATED" : "PENDING";
        Long correlatedAt = allocated >= canonical.amount() ? nowMs : null;
        try (PreparedStatement pstmt = conn.prepareStatement(
                "UPDATE ig_observations SET correlation_status = ?, correlated_at = ? WHERE id = ?")) {
            pstmt.setString(1, status);
            if (correlatedAt == null) {
                pstmt.setNull(2, java.sql.Types.INTEGER);
            } else {
                pstmt.setLong(2, correlatedAt);
            }
            pstmt.setLong(3, canonical.id());
            pstmt.executeUpdate();
        }
    }

    private void addGroupMembersToExistingEdges(Connection conn, long groupId, long canonicalId) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement("""
                INSERT OR IGNORE INTO ig_edge_evidence (edge_id, observation_id)
                SELECT DISTINCT allocation.edge_id, member.observation_id
                FROM ig_edge_allocations allocation
                JOIN ig_inferred_edges edge ON edge.id = allocation.edge_id AND edge.edge_state = 'ACTIVE'
                JOIN ig_observation_group_members member ON member.group_id = ?
                WHERE allocation.observation_id = ?
                """)) {
            pstmt.setLong(1, groupId);
            pstmt.setLong(2, canonicalId);
            pstmt.executeUpdate();
        }
    }

    private static void updateObservationStatus(Connection conn, long observationId,
                                                String status, long nowMs) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "UPDATE ig_observations SET correlation_status = ?, correlated_at = ? WHERE id = ?")) {
            pstmt.setString(1, status);
            pstmt.setLong(2, nowMs);
            pstmt.setLong(3, observationId);
            pstmt.executeUpdate();
        }
    }

    private static void markChecked(Connection conn, long observationId,
                                    long nowMs, String result) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement("""
                INSERT INTO ig_observation_match_checks (observation_id, checked_at_ms, result)
                VALUES (?, ?, ?)
                ON CONFLICT(observation_id) DO UPDATE SET
                    checked_at_ms = excluded.checked_at_ms,
                    result = excluded.result
                """)) {
            pstmt.setLong(1, observationId);
            pstmt.setLong(2, nowMs);
            pstmt.setString(3, result);
            pstmt.executeUpdate();
        }
    }

    private static boolean isUnresolvedAttempt(String actionType) {
        return "DROP_CANCELLED".equals(actionType)
                || "DROP_UNRESOLVED".equals(actionType)
                || "DEATH_DROP_UNRESOLVED".equals(actionType)
                || "DEATH_DROP_CANCELLED".equals(actionType);
    }

    private static GroundObservation readObservation(ResultSet rs) throws SQLException {
        long targetId = rs.getLong("target_node_id");
        Long targetNodeId = rs.wasNull() ? null : targetId;
        return new GroundObservation(
                rs.getLong("id"),
                rs.getString("source_type"),
                rs.getLong("timestamp_ms"),
                rs.getLong("node_id"),
                targetNodeId,
                rs.getLong("fingerprint_id"),
                rs.getString("action_type"),
                rs.getInt("amount"),
                rs.getString("item_entity_uuid"));
    }
}
