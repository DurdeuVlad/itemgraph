package com.itemgraph.query;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
        lines.add(PREFIX + "=== OBSERVATION #" + obs.id() + " [OBSERVED] ===");
        lines.addAll(observationBody(obs, "  "));
        lines.add("  This row is raw evidence derived from a single source event. It is never inferred.");
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
            lines.add(indent + "time:        " + formatTime(obs.timestampMs()));
        } else {
            lines.add(indent + "time window: " + formatTime(obs.timestampMs()) + " -> " + formatTime(obs.timestampEndMs()));
            if ("container_session_net_delta".equals(obs.captureType())) {
                lines.add(indent + "scope:       session net delta; intra-session order is unknown and zero-net activity is not represented.");
            } else if ("queue_overflow_recovery".equals(obs.captureType())) {
                lines.add(indent + "scope:       coalesced capability transfers recovered after queue rejection; caller remains UNKNOWN.");
            } else {
                lines.add(indent + "scope:       observation spans an interval; intra-interval event order is not available.");
            }
        }
        lines.add(indent + "source:      " + obs.sourceType()
                + (obs.sourceEventId() != null ? " event#" + obs.sourceEventId() : " (no source event id)"));
        lines.add(indent + "action:      " + obs.actionType() + "  amount: " + obs.amount() + "x");
        lines.add(indent + "item:        " + obs.fingerprint().describeFull());
        lines.add(indent + "origin:      " + node(obs.origin()));
        lines.add(indent + "destination: " + node(obs.destination()));
        if (obs.itemEntityUuid() != null) {
            lines.add(indent + "entity UUID: " + obs.itemEntityUuid());
        }
        if (obs.sourceGroup() != null) {
            ObservationDetail.SourceGroup group = obs.sourceGroup();
            if ("CONFIRMED".equals(group.state())) {
                lines.add(indent + "source group: confirmed #" + group.id() + " " + group.matchBasis()
                        + "; role=" + group.memberRole() + "; canonical observation #" + group.canonicalObservationId());
            } else {
                lines.add(indent + "source group: ambiguous #" + group.id()
                        + "; no independent quantity capacity; " + group.explanation());
            }
        }
        lines.add(indent + "correlated:  " + (obs.correlatedAtMs() == null
                ? "not yet evaluated by the correlation engine"
                : formatTime(obs.correlatedAtMs())));
        return lines;
    }

    public static String eventNotFound(long observationId) {
        return PREFIX + "No observation #" + observationId + " exists in ig_observations.";
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
        lines.add(PREFIX + "=== " + (active ? "INFERRED" : "SUPERSEDED INFERENCE") + " EDGE #" + edge.id()
                + " [INFERRED conf=" + formatConfidence(edge.confidence()) + "] ===");
        lines.add("  WARNING: this is a reconstruction, not direct evidence. Only the observations listed below were observed.");
        if (!active) {
            lines.add("  state:       " + edge.edgeState() + " (excluded from current traces and quantity capacity)");
        }
        lines.add("  from:        " + node(edge.from()));
        lines.add("  to:          " + node(edge.to()));
        lines.add("  item:        " + edge.fingerprint().describeFull());
        lines.add("  amount:      " + edge.amount() + "x");
        lines.add("  time range:  " + formatTime(edge.timeStart()) + " -> " + formatTime(edge.timeEnd())
                + "  (gap " + formatDuration(edge.spanMs()) + ")");
        lines.add("  confidence:  " + formatConfidence(edge.confidence()));
        lines.add("  inferred at: " + formatTime(edge.createdAtMs()));
        lines.add("  why:         " + (edge.explanation() == null ? "(no explanation stored)" : edge.explanation()));

        if (edge.evidence().isEmpty()) {
            lines.add("  supporting evidence: NONE RECORDED - this edge cites no observations and cannot be justified.");
            return lines;
        }

        lines.add("  supporting evidence (" + edge.evidence().size() + " observed row"
                + (edge.evidence().size() == 1 ? "" : "s") + "):");
        for (ObservationDetail obs : edge.evidence()) {
            lines.add("   - [OBSERVED] observation #" + obs.id() + " (/ig event " + obs.id() + ")");
            lines.addAll(observationBody(obs, "       "));
        }
        if (edge.evidenceTruncated()) {
            lines.add("   ... evidence listing truncated at " + QueryLimits.MAX_EVIDENCE_ROWS + " rows.");
        }
        return lines;
    }

    public static String explainNotFound(long edgeId) {
        return PREFIX + "No inferred edge #" + edgeId + " exists in ig_inferred_edges.";
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
        lines.add(PREFIX + "=== TRACE " + result.targetDescription() + " ===");
        lines.add("  window: " + result.window().describe()
                + " | limit: " + result.appliedLimit()
                + (result.limitWasCapped() ? " (capped from " + result.requestedLimit() + ")" : ""));

        if (result.hops().isEmpty()) {
            lines.add("  No observed or inferred movement recorded for "
                    + (result.fingerprint() != null ? "this fingerprint" : result.targetDescription())
                    + " in that window.");
            return lines;
        }

        boolean showItem = (result.fingerprint() == null);
        for (TraceHop hop : result.hops()) {
            lines.add("  " + formatHop(hop, showItem));
        }
        if (result.hops().stream().anyMatch(hop -> hop.kind() == TraceHop.Kind.OBSERVED
                && hop.endMs() > hop.timestampMs()
                && hop.detail().contains("session net delta"))) {
            lines.add("  Session net container deltas are interval measurements; intra-session order is unknown, and zero-net activity is not represented.");
        }

        lines.add("  " + result.observedCount() + " observed hop" + (result.observedCount() == 1 ? "" : "s")
                + ", " + result.inferredCount() + " inferred hop" + (result.inferredCount() == 1 ? "" : "s") + ".");
        if (result.truncated()) {
            lines.add("  TRUNCATED at " + result.appliedLimit()
                    + " hops - more movement matched. Narrow the window or raise the limit (max "
                    + QueryLimits.MAX_LIMIT + ").");
        }
        String transformationDetails = result.fingerprint() == null
                ? "transformation# IDs refer to ig_item_transformations."
                : "transformation# IDs refer to ig_item_transformations; open /ig gui item \"id:"
                        + result.fingerprint().id() + "\" and select the row for details.";
        lines.add("  [OBSERVED] = raw observation or recorded transformation. Use /ig event <id> only "
                + "for observation# IDs; " + transformationDetails + " [INFERRED] = reconstructed, see "
                + "the evidence with /ig explain <id>.");
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
                ? "during [" + formatTime(hop.timestampMs()) + " -> " + formatTime(hop.endMs()) + "]"
                : "at " + formatTime(hop.timestampMs());
        return label + " " + nodeShort(hop.origin()) + " -> " + nodeShort(hop.destination())
                + " : " + hop.amount() + "x" + itemSuffix + " " + time + " " + reference;
    }

    public static String traceNoSuchFingerprint(long fingerprintId) {
        return PREFIX + "No item fingerprint #" + fingerprintId + " exists in ig_item_fingerprints.";
    }

    public static String traceNoSuchFingerprint(String query) {
        return PREFIX + "No item fingerprint matching '" + query + "' exists in ig_item_fingerprints.";
    }

    public static String traceNoSuchTarget(String target) {
        return PREFIX + "No recorded history found for " + target + ".";
    }

    public static List<String> formatFingerprintCandidates(String query, List<FingerprintRef> candidates) {
        List<String> lines = new ArrayList<>();
        lines.add(PREFIX + "Query '" + query + "' matched " + candidates.size() + " item fingerprints:");
        for (FingerprintRef c : candidates) {
            lines.add("  #" + c.id() + ": " + c.describe() + " [hash=" + c.fingerprintHash() + "]");
        }
        lines.add("  Use /ig trace item \"id:<id>\" or /ig gui item \"id:<id>\" to select a specific fingerprint.");
        return lines;
    }

    public static List<String> formatNodeCandidates(String target, List<NodeRef> candidates) {
        List<String> lines = new ArrayList<>();
        lines.add(PREFIX + target + " matches multiple stored nodes; no timeline was selected:");
        for (NodeRef candidate : candidates) {
            lines.add("  " + candidate.describe());
        }
        lines.add("  Candidate listing is capped at 10 nodes.");
        lines.add("Use the corresponding /ig gui command to select a specific node.");
        return lines;
    }

    public static List<String> formatAudit(com.itemgraph.audit.AuditReport report) {
        List<String> lines = new ArrayList<>();
        lines.add(PREFIX + "=== DATABASE INVARIANT AUDIT ===");
        lines.add("  Status: " + (report.healthy() ? "HEALTHY (ALL INVARIANTS SATISFIED)" : "VIOLATIONS DETECTED"));
        lines.add("  Observations: " + report.totalObservations() + " | Inferred edges: " + report.totalEdges());
        lines.add("  Allocations: " + report.totalAllocations() + " | Transformations: " + report.totalTransformations());
        lines.add("  Conservation violations: " + report.overAllocatedObservations());
        lines.add("  Non-positive quantities: " + report.nonPositiveQuantities());
        lines.add("  Orphaned allocations: " + report.orphanedAllocations());
        lines.add("  Invalid edge nodes: " + report.invalidEdgeNodes());
        lines.add("  Status consistency mismatches: " + report.statusMismatches());
        if (!report.healthy()) {
            lines.add("  Violations:");
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
        return PREFIX + "Query failed: " + detail;
    }
}
