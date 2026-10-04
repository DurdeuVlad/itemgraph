package com.itemgraph.query;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import com.itemgraph.i18n.ItemGraphLanguage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders query results as plain text lines for the console and chat.
 *
 * <p>Deliberately free of any Minecraft type. The command layer wraps each line in a
 * {@code Component.literal(...)}; keeping the text generation here means the exact output
 * an admin sees is unit-testable without a running server, which for a forensic tool is
 * the part that most needs pinning down: the OBSERVED/INFERRED labels and the confidence
 * values are the output's correctness, not its styling.
 *
 * <h2>Labelling convention</h2>
 *
 * <p>Every line that asserts a movement is prefixed with its provenance:
 * <pre>
 *   [OBSERVED]            directly evidenced by one ig_observations row
 *   [INFERRED conf=0.90]  reconstructed by correlation, with its stored confidence
 * </pre>
 * There is no unlabelled movement line anywhere in the output. Confidence is printed on
 * every inferred line and to four decimals in detail views, so an inference can never be
 * mistaken for a fact at a glance.
 *
 * <h2>Timestamps</h2>
 *
 * <p>Rendered as {@code yyyy-MM-dd HH:mm:ss UTC}. UTC rather than server-local time
 * because an incident report gets compared against server logs and GriefLogger rows that
 * are themselves stored as epoch millis, and a timezone-dependent rendering would make
 * two copies of the same output disagree.
 */
public final class QueryFormatter {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private static final String PREFIX = "[ItemGraph] ";

    private QueryFormatter() {}

    // ------------------------------------------------------------------
    // Primitives
    // ------------------------------------------------------------------

    public static String formatTime(long epochMs) {
        return TIMESTAMP.format(Instant.ofEpochMilli(epochMs)) + " UTC";
    }

    public static String formatDuration(long ms) {
        if (ms < 0) {
            return "-" + formatDuration(-ms);
        }
        if (ms < 1000L) {
            return ms + "ms";
        }
        long totalSeconds = ms / 1000L;
        if (totalSeconds < 60L) {
            return totalSeconds + "s";
        }
        if (totalSeconds < 3600L) {
            return (totalSeconds / 60L) + "m" + (totalSeconds % 60L) + "s";
        }
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        return hours + "h" + minutes + "m";
    }

    public static String formatConfidence(Double confidence) {
        return confidence == null ? "(not recorded)" : formatConfidence(confidence.doubleValue());
    }

    /** Four decimals: enough to reproduce the engine's rounded score exactly. */
    public static String formatConfidence(double confidence) {
        return String.format(Locale.ROOT, "%.4f", confidence);
    }

    /** Formats native non-quantity audit evidence for {@code /ig lookup}. */
    public static List<String> formatAuditEvents(List<AuditEventDetail> events, String filterDescription) {
        List<String> lines = new ArrayList<>();
        lines.add(PREFIX + t("audit_events.header", "=== NATIVE AUDIT EVENTS ({0}) ===", filterDescription));
        if (events.isEmpty()) {
            lines.add(PREFIX + t("query.audit.empty", "No native audit events matched the requested filters."));
            return lines;
        }
        for (AuditEventDetail event : events) {
            String actor = auditActor(event);
            String subject = event.subjectId() == null ? "" : " subject=" + event.subjectId();
            String detail = event.detail() == null ? "" : " detail=" + escapeDetail(event.detail());
            String supersession = event.supersedingEventId() == null ? ""
                    : " superseded_by=audit#" + event.supersedingEventId()
                    + " reason=" + event.supersessionReason();
            lines.add(PREFIX + t("audit_events.row", "[OBSERVED] audit#{0} {1} actor={2} at {3} [{4}, {5}, {6}] time={7}{8}{9}{10}",
                    event.id(), event.eventType(), actor, event.levelName(), formatCoordinate(event.x()),
                    formatCoordinate(event.y()), formatCoordinate(event.z()), formatTime(event.timestampMs()),
                    subject, detail, supersession));
        }
        return lines;
    }

    private static String auditActor(AuditEventDetail event) {
        if (event.playerName() != null) return event.playerName();
        if (event.playerUuid() != null) return event.playerUuid();
        if ("CONTAINER_BREAK_UNRESOLVED".equals(event.eventType())) {
            return "(actor unavailable)";
        }
        return "(unknown player)";
    }

    /** Formats the cross-table GriefLogger-compatible lookup timeline. */
    public static List<String> formatUnifiedEvidence(List<UnifiedEvidenceDetail> evidence,
                                                      String filterDescription) {
        List<String> lines = new ArrayList<>();
        lines.add(PREFIX + t("unified.header", "=== UNIFIED EVIDENCE ({0}) ===", filterDescription));
        if (evidence.isEmpty()) {
            lines.add(PREFIX + t("query.unified.empty", "No audit, item-flow, transformation, or imported evidence matched the requested filters."));
            return lines;
        }
        for (UnifiedEvidenceDetail row : evidence) {
            String actor = row.playerName() == null ? t("common.unknown_player", "(unknown player)") : row.playerName();
            String location = row.levelName() == null ? t("common.unknown_dimension", "(unknown dimension)") : row.levelName();
            if ("CONTAINER_BREAK_UNRESOLVED".equals(row.actionType()) && row.playerName() == null) {
                actor = "(actor unavailable)";
            }
            if (row.x() != null && row.y() != null && row.z() != null) {
                location += " [" + formatCoordinate(row.x()) + ", "
                        + formatCoordinate(row.y()) + ", " + formatCoordinate(row.z()) + "]";
            }
            String quantity = row.quantity() == null ? t("unified.quantity_unknown", " quantity=UNKNOWN")
                    : row.quantity() == 0 ? "" : t("unified.quantity", " quantity={0}", row.quantity());
            String subject = row.subjectId() == null ? "" : t("unified.subject", " subject={0}", row.subjectId());
            String detail = row.detail() == null ? "" : t("unified.detail", " detail={0}", escapeDetail(row.detail()));
            lines.add(PREFIX + t("unified.row", "[{0}] {1} {2} {3} actor={4} at {5} time={6}{7}{8}{9}",
                    row.evidenceClass(), row.source(), row.evidenceId(), row.actionType(), actor, location,
                    formatTime(row.timestampMs()), quantity, subject, detail));
        }
        return lines;
    }

    private static String formatCoordinate(double coordinate) {
        if (coordinate == Math.rint(coordinate)) {
            return Long.toString((long) coordinate);
        }
        return String.format(Locale.ROOT, "%.2f", coordinate);
    }

    /** Keeps chat and command payloads on one operator-console line without changing stored evidence. */
    private static String escapeDetail(String detail) {
        StringBuilder escaped = new StringBuilder(detail.length());
        for (int i = 0; i < detail.length(); i++) {
            char character = detail.charAt(i);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '\r' -> escaped.append("\\r");
                case '\n' -> escaped.append("\\n");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (Character.isISOControl(character)) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static String node(NodeRef ref) {
        return ref == null ? "(none recorded)" : ref.describe();
    }

    private static String nodeShort(NodeRef ref) {
        return ref == null ? "(none)" : ref.describeShort();
    }

    // ------------------------------------------------------------------
    // /ig event
    // ------------------------------------------------------------------

    /** Full detail view for {@code /ig event}. */
    public static List<String> formatEvent(ObservationDetail obs) {
        List<String> lines = new ArrayList<>();
        lines.add(PREFIX + "=== OBSERVATION #" + obs.id() + " [" + obs.kindLabel() + "] ===");
        lines.addAll(observationBody(obs, "  "));
        lines.add("  " + t("event.raw_evidence_note", "This row is raw evidence derived from a single source event. It is never inferred."));
        return lines;
    }

    /**
     * The shared observation detail block, indented by {@code indent}.
     *
     * <p>Used both by {@code /ig event} and by the evidence listing under
     * {@code /ig explain}, so the evidence behind an inference is displayed in exactly the
     * same shape as a standalone lookup of the same row.
     */
    public static List<String> observationBody(ObservationDetail obs, String indent) {
        List<String> lines = new ArrayList<>();
        if (obs.timestampEndMs() == null) {
            lines.add(indent + t("detail.time", "time:        {0}", formatTime(obs.timestampMs())));
        } else {
            lines.add(indent + t("detail.time_window", "time window: {0} -> {1}", formatTime(obs.timestampMs()), formatTime(obs.timestampEndMs())));
            if ("container_session_net_delta".equals(obs.captureType())) {
                lines.add(indent + t("detail.scope_session", "scope:       session net delta; intra-session order is unknown and zero-net activity is not represented."));
            } else if ("queue_overflow_recovery".equals(obs.captureType())) {
                lines.add(indent + t("detail.scope_recovery", "scope:       coalesced capability transfers recovered after queue rejection; caller remains UNKNOWN."));
            } else {
                lines.add(indent + t("detail.scope_interval", "scope:       observation spans an interval; intra-interval event order is not available."));
            }
        }
        lines.add(indent + t("detail.source", "source:      {0}{1}", obs.sourceType(),
                obs.sourceEventId() != null ? " event#" + obs.sourceEventId() : " (no source event id)"));
        lines.add(indent + t("detail.action", "action:      {0}  amount: {1}x", obs.actionType(), obs.amount()));
        if (obs.dispositionReason() != null) {
            lines.add(indent + t("detail.evidence_excluded", "evidence:    excluded from current item flow; {0}", obs.dispositionReason()));
            lines.add(indent + t("detail.quantity_unproven", "quantity:    historical callback amount is not proof of a transfer"));
        }
        lines.add(indent + t("detail.item", "item:        {0}", obs.fingerprint().describeFull()));
        lines.add(indent + t("detail.origin", "origin:      {0}", node(obs.origin())));
        lines.add(indent + t("detail.destination", "destination: {0}", node(obs.destination())));
        if (obs.itemEntityUuid() != null) {
            lines.add(indent + t("detail.entity_uuid", "entity UUID: {0}", obs.itemEntityUuid()));
        }
        if (obs.sourceGroup() != null) {
            ObservationDetail.SourceGroup group = obs.sourceGroup();
            if ("CONFIRMED".equals(group.state())) {
                lines.add(indent + t("detail.source_group_confirmed", "source group: confirmed #{0} {1}; role={2}; canonical observation #{3}",
                        group.id(), group.matchBasis(), group.memberRole(), group.canonicalObservationId()));
            } else {
                lines.add(indent + t("detail.source_group_ambiguous", "source group: ambiguous #{0}; no independent quantity capacity; {1}",
                        group.id(), group.explanation()));
            }
        }
        lines.add(indent + t("detail.correlated", "correlated:  {0}", obs.correlatedAtMs() == null
                ? t("detail.not_evaluated", "not yet evaluated by the correlation engine")
                : formatTime(obs.correlatedAtMs())));
        return lines;
    }

    public static String eventNotFound(long observationId) {
        return PREFIX + t("error.observation_not_found", "No observation #{0} exists in ig_observations.", observationId);
    }

    // ------------------------------------------------------------------
    // /ig explain
    // ------------------------------------------------------------------

    /**
     * Full detail view for {@code /ig explain}: the claim, its confidence, the stored
     * scoring narrative, and every observation cited as evidence.
     */
    public static List<String> formatExplain(EdgeExplanation edge) {
        List<String> lines = new ArrayList<>();
        boolean active = "ACTIVE".equals(edge.edgeState());
        lines.add(PREFIX + t(active ? "explain.header_active" : "explain.header_superseded",
                active ? "=== INFERRED EDGE #{0} [INFERRED conf={1}] ==="
                        : "=== SUPERSEDED INFERENCE EDGE #{0} [INFERRED conf={1}] ===",
                edge.id(), formatConfidence(edge.confidence())));
        lines.add("  " + t("explain.warning", "WARNING: this is a reconstruction, not direct evidence. Only the observations listed below were observed."));
        if (!active) {
            lines.add("  " + t("explain.state", "state:       {0} (excluded from current traces and quantity capacity)", edge.edgeState()));
        }
        lines.add("  " + t("explain.from", "from:        {0}", node(edge.from())));
        lines.add("  " + t("explain.to", "to:          {0}", node(edge.to())));
        lines.add("  " + t("explain.item", "item:        {0}", edge.fingerprint().describeFull()));
        lines.add("  " + t("explain.amount", "amount:      {0}x", edge.amount()));
        lines.add("  " + t("explain.time_range", "time range:  {0} -> {1}  (gap {2})",
                formatTime(edge.timeStart()), formatTime(edge.timeEnd()), formatDuration(edge.spanMs())));
        lines.add("  " + t("explain.confidence", "confidence:  {0}", formatConfidence(edge.confidence())));
        lines.add("  " + t("explain.inferred_at", "inferred at: {0}", formatTime(edge.createdAtMs())));
        lines.add("  " + t("explain.why", "why:         {0}", edge.explanation() == null ? "(no explanation stored)" : edge.explanation()));

        if (edge.evidence().isEmpty()) {
            lines.add("  " + t("explain.no_evidence", "supporting evidence: NONE RECORDED - this edge cites no observations and cannot be justified."));
            return lines;
        }

        lines.add("  " + t("explain.evidence_count", "supporting evidence ({0} observed row{1}):",
                edge.evidence().size(), edge.evidence().size() == 1 ? "" : "s"));
        for (ObservationDetail obs : edge.evidence()) {
            lines.add(t("explain.evidence_row", "   - [OBSERVED] observation #{0} (/ig event {0})", obs.id()));
            lines.addAll(observationBody(obs, "       "));
        }
        if (edge.evidenceTruncated()) {
            lines.add("   " + t("explain.evidence_truncated", "... evidence listing truncated at {0} rows.", QueryLimits.MAX_EVIDENCE_ROWS));
        }
        return lines;
    }

    public static String explainNotFound(long edgeId) {
        return PREFIX + t("error.edge_not_found", "No inferred edge #{0} exists in ig_inferred_edges.", edgeId);
    }

    // ------------------------------------------------------------------
    // /ig trace item
    // ------------------------------------------------------------------

    /**
     * The merged timeline. One line per hop, in the documented shape:
     * {@code [OBSERVED|INFERRED conf=X.XX] <origin> -> <destination> : <amount>x at <time>}.
     */
    public static List<String> formatTrace(TraceResult result) {
        List<String> lines = new ArrayList<>();
        lines.add(PREFIX + t("trace.header", "=== TRACE {0} ===", result.targetDescription()));
        lines.add("  " + t("trace.window_limit", "window: {0} | limit: {1}{2}", result.window().describe(),
                result.appliedLimit(), result.limitWasCapped()
                        ? t("trace.capped_from", " (capped from {0})", result.requestedLimit()) : ""));

        if (result.hops().isEmpty()) {
            lines.add("  " + t("trace.empty", "No observed or inferred movement recorded for {0} in that window.",
                    result.fingerprint() != null ? t("trace.this_fingerprint", "this fingerprint") : result.targetDescription()));
            return lines;
        }

        boolean showItem = (result.fingerprint() == null);
        for (TraceHop hop : result.hops()) {
            lines.add("  " + formatHop(hop, showItem));
        }
        if (result.hops().stream().anyMatch(hop -> hop.kind() == TraceHop.Kind.OBSERVED
                && hop.endMs() > hop.timestampMs()
                && hop.detail().contains("session net delta"))) {
            lines.add("  " + t("trace.session_delta_note", "Session net container deltas are interval measurements; intra-session order is unknown, and zero-net activity is not represented."));
        }

        lines.add("  " + t("trace.counts", "{0} observed hop{1}, {2} inferred hop{3}.", result.observedCount(),
                result.observedCount() == 1 ? "" : "s", result.inferredCount(), result.inferredCount() == 1 ? "" : "s"));
        if (result.truncated()) {
            lines.add("  " + t("trace.truncated", "TRUNCATED at {0} hops - more movement matched. Narrow the window or raise the limit (max {1}).",
                    result.appliedLimit(), QueryLimits.MAX_LIMIT));
        }
        String transformationDetails = result.fingerprint() == null
                ? t("trace.transformation_reference", "transformation# IDs refer to ig_item_transformations.")
                : t("trace.transformation_reference_item", "transformation# IDs refer to ig_item_transformations; open /ig gui item \"id:{0}\" and select the row for details.",
                        result.fingerprint().id());
        lines.add("  " + t("trace.legend", "[OBSERVED] = raw observation or recorded transformation. Use /ig event <id> only for observation# IDs; {0} [INFERRED] = reconstructed, see the evidence with /ig explain <id>.", transformationDetails));
        return lines;
    }

    /** One timeline row. Provenance first, so it is never read as an unqualified fact. */
    public static String formatHop(TraceHop hop) {
        return formatHop(hop, false);
    }

    public static String formatHop(TraceHop hop, boolean showItem) {
        String label = hop.kind() == TraceHop.Kind.OBSERVED
                ? "[OBSERVED]"
                : "[INFERRED conf=" + formatConfidence(hop.confidence()) + "]";

        String reference = switch (hop.source()) {
            case OBSERVATION -> "(observation#" + hop.refId() + " " + hop.detail() + ")";
            case TRANSFORMATION -> "(transformation#" + hop.refId() + " " + hop.detail() + ")";
            case INFERRED_EDGE -> "(edge#" + hop.refId() + " " + hop.detail() + ")";
        };

        String itemSuffix = (showItem && hop.item() != null && hop.item().resolved())
                ? " [" + hop.item().describe() + "]"
                : "";

            String time = hop.endMs() > hop.timestampMs()
                ? t("trace.hop_during", "during [{0} -> {1}]", formatTime(hop.timestampMs()), formatTime(hop.endMs()))
                : t("trace.hop_at", "at {0}", formatTime(hop.timestampMs()));
        return label + " " + nodeShort(hop.origin()) + " -> " + nodeShort(hop.destination())
                + " : " + hop.amount() + "x" + itemSuffix + " " + time + " " + reference;
    }

    public static String traceNoSuchFingerprint(long fingerprintId) {
        return PREFIX + t("error.fingerprint_id_not_found", "No item fingerprint #{0} exists in ig_item_fingerprints.", fingerprintId);
    }

    public static String traceNoSuchFingerprint(String query) {
        return PREFIX + t("error.fingerprint_query_not_found", "No item fingerprint matching '{0}' exists in ig_item_fingerprints.", query);
    }

    public static String traceNoSuchTarget(String target) {
        return PREFIX + t("error.history_not_found", "No recorded history found for {0}.", target);
    }

    public static List<String> formatFingerprintCandidates(String query, List<FingerprintRef> candidates) {
        List<String> lines = new ArrayList<>();
        lines.add(PREFIX + t("trace.fingerprint_candidate_count", "Query '{0}' matched {1} item fingerprints:", query, candidates.size()));
        for (FingerprintRef c : candidates) {
            lines.add("  " + t("trace.fingerprint_candidate_row", "#{0}: {1} [hash={2}]",
                    c.id(), c.describe(), c.fingerprintHash()));
        }
        lines.add("  " + t("trace.select_fingerprint", "Use /ig trace item \"id:<id>\" or /ig gui item \"id:<id>\" to select a specific fingerprint."));
        return lines;
    }

    public static List<String> formatNodeCandidates(String target, List<NodeRef> candidates) {
        List<String> lines = new ArrayList<>();
        lines.add(PREFIX + t("trace.multiple_nodes", "{0} matches multiple stored nodes; no timeline was selected:", target));
        for (NodeRef candidate : candidates) {
            lines.add("  " + candidate.describe());
        }
        lines.add("  " + t("trace.node_candidate_cap", "Candidate listing is capped at 10 nodes."));
        lines.add(t("trace.select_node", "Use the corresponding /ig gui command to select a specific node."));
        return lines;
    }

    public static List<String> formatAudit(com.itemgraph.audit.AuditReport report) {
        List<String> lines = new ArrayList<>();
        lines.add(PREFIX + t("audit.header", "=== DATABASE INVARIANT AUDIT ==="));
        lines.add("  " + t("audit.status", "Status: {0}", report.healthy()
                ? t("audit.healthy", "HEALTHY (ALL INVARIANTS SATISFIED)") : t("audit.violations", "VIOLATIONS DETECTED")));
        lines.add("  " + t("audit.observations_edges", "Observations: {0} | Inferred edges: {1}", report.totalObservations(), report.totalEdges()));
        lines.add("  " + t("audit.allocations_transformations", "Allocations: {0} | Transformations: {1}", report.totalAllocations(), report.totalTransformations()));
        lines.add("  " + t("audit.conservation_violations", "Conservation violations: {0}", report.overAllocatedObservations()));
        lines.add("  " + t("audit.invalid_allocations", "Invalid edge allocations: {0}", report.invalidEdgeAllocations()));
        lines.add("  " + t("audit.invalid_timestamps", "Invalid edge timestamps: {0}", report.invalidEdgeTemporal()));
        lines.add("  " + t("audit.nonpositive", "Non-positive quantities: {0}", report.nonPositiveQuantities()));
        lines.add("  " + t("audit.orphaned", "Orphaned allocations: {0}", report.orphanedAllocations()));
        lines.add("  " + t("audit.invalid_nodes", "Invalid edge nodes: {0}", report.invalidEdgeNodes()));
        lines.add("  " + t("audit.status_mismatches", "Status consistency mismatches: {0}", report.statusMismatches()));
        if (!report.healthy()) {
            lines.add("  " + t("audit.violations_list", "Violations:"));
            for (String detail : report.violationDetails()) {
                lines.add("   - " + detail);
            }
        }
        return lines;
    }

    // ------------------------------------------------------------------
    // Shared
    // ------------------------------------------------------------------

    public static String queryFailed(String detail) {
        return PREFIX + t("error.query_failed", "Query failed: {0}", detail);
    }

    private static String t(String key, String english, Object... arguments) {
        return ItemGraphLanguage.text(key, english, arguments);
    }
}
