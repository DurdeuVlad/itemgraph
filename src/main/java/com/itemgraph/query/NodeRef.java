package com.itemgraph.query;

import java.util.Locale;

/**
 * A resolved {@code ig_nodes} row, flattened for display.
 *
 * <p>Rendering node identity is deliberately centralised here rather than repeated per
 * command: an admin reading {@code /ig event}, {@code /ig explain} and {@code /ig trace}
 * output must see the same node described the same way every time, otherwise comparing
 * two outputs by eye becomes guesswork. The wording intentionally mirrors
 * {@code CorrelationEngine.describeNode}, which produces the stored edge explanations,
 * so a node named in an explanation string reads identically to the same node printed
 * as a structured field.
 *
 * <p>Nothing here invents detail. A node that cannot be resolved renders as an explicit
 * "missing" marker rather than a plausible-looking placeholder.
 *
 * @param id        {@code ig_nodes.id}
 * @param nodeType  {@code ig_nodes.node_type}, or null when the row could not be resolved
 * @param label     {@code ig_nodes.custom_label} (the username, for PLAYER nodes)
 * @param levelId   {@code ig_nodes.level_id}
 * @param x         block coordinate, or null for coordinate-less nodes (PLAYER, UNKNOWN)
 * @param y         block coordinate, or null
 * @param z         block coordinate, or null
 */
public record NodeRef(
        long id,
        String nodeType,
        String label,
        String levelId,
        Double x,
        Double y,
        Double z
) {

    /** A node id that has no corresponding {@code ig_nodes} row (dangling reference). */
    public static NodeRef missing(long id) {
        return new NodeRef(id, null, null, null, null, null, null);
    }

    public boolean resolved() {
        return nodeType != null;
    }

    private boolean hasCoordinates() {
        return x != null && y != null && z != null;
    }

    private String coordinates() {
        return String.format(Locale.ROOT, "%d,%d,%d", (long) (double) x, (long) (double) y, (long) (double) z);
    }

    private String playerName() {
        return (label != null && !label.isBlank()) ? label : ("player node#" + id);
    }

    /**
     * Full form, for the detail views: always carries the node id so an admin can
     * cross-reference two outputs without ambiguity.
     */
    public String describe() {
        if (!resolved()) {
            return "node#" + id + " (no such row in ig_nodes)";
        }
        if ("PLAYER".equals(nodeType)) {
            return playerName() + " (PLAYER node#" + id + ")";
        }
        if (!hasCoordinates()) {
            return nodeType + " " + levelId + " (node#" + id + ")";
        }
        return nodeType + " " + levelId + " " + coordinates() + " (node#" + id + ")";
    }

    /**
     * Compact form, for one-line timeline rows where several nodes share a line and the
     * level id would push the interesting part off the end of a chat window.
     */
    public String describeShort() {
        if (!resolved()) {
            return "node#" + id + "(missing)";
        }
        if ("PLAYER".equals(nodeType)) {
            return playerName();
        }
        if (!hasCoordinates()) {
            return nodeType;
        }
        return nodeType + " " + coordinates();
    }
}
