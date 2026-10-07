package com.itemgraph.i18n;

import java.util.HashSet;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.ResourceBundle;

/** Server-side ItemGraph-owned messages. Final strings are sent as literals so clients need no mod assets. */
public final class ItemGraphLanguage {
    public static final String DEFAULT_LOCALE = "en_us";
    public static final Set<String> SUPPORTED_LOCALES = Set.of("en_us", "nl_nl", "zh_tw");
    private static final Set<String> REQUIRED_KEYS = Set.of(
            "permission.denied", "permission.result_revoked", "query.queue_full",
            "config.invalid_language", "flow.browser.loading", "flow.browser.open_failed",
            "flow.browser.permission_denied", "inspect.requires_player", "goto.invalid", "locale.name",
            "query.audit.empty", "query.unified.empty", "detail.time", "detail.time_window",
            "detail.scope_session", "detail.scope_recovery", "detail.scope_interval", "detail.source",
            "detail.action", "detail.evidence_excluded", "detail.quantity_unproven", "detail.item",
            "detail.origin", "detail.destination", "detail.entity_uuid", "detail.source_group_confirmed",
            "detail.source_group_ambiguous", "detail.correlated", "detail.not_evaluated",
            "error.observation_not_found", "explain.warning", "explain.state", "explain.from", "explain.to",
            "explain.item", "explain.amount", "explain.time_range", "explain.confidence", "explain.inferred_at",
            "explain.why", "explain.no_evidence", "explain.evidence_count", "explain.evidence_truncated",
            "error.edge_not_found", "trace.empty", "trace.this_fingerprint", "trace.session_delta_note",
            "trace.truncated", "trace.legend", "error.query_failed", "browser.page_prefix",
            "browser.ambiguous_candidate", "browser.ambiguous_node_candidate", "browser.ambiguous_empty",
            "browser.not_found", "browser.empty", "browser.row", "browser.no_target", "browser.no_movement_title",
            "browser.no_movement_lore", "browser.window", "browser.candidate_cap", "browser.timeline_page_size",
            "browser.page_title", "browser.candidate_title", "browser.loading_suffix", "browser.control_lore",
            "browser.previous", "browser.previous_unavailable", "browser.next", "browser.next_unavailable",
            "browser.close", "browser.page", "browser.candidate_page", "error.fingerprint_id_not_found",
            "error.fingerprint_query_not_found", "error.history_not_found", "trace.fingerprint_candidate_count",
            "trace.select_fingerprint", "trace.multiple_nodes", "trace.node_candidate_cap", "trace.select_node",
            "audit.header", "audit.status", "audit.healthy", "audit.violations", "audit.observations_edges",
            "audit.allocations_transformations", "audit.conservation_violations", "audit.invalid_allocations",
            "audit.invalid_timestamps", "audit.nonpositive", "audit.orphaned", "audit.invalid_nodes",
            "audit.status_mismatches", "audit.violations_list", "goto.rejected", "help.unknown_topic",
            "lookup.unknown_event_type", "inspect.enabled_full", "inspect.enabled", "inspect.disabled",
            "inspect.disabled_revoked",
            "inspect.already_enabled", "inspect.already_disabled", "inspect.status", "inspect.enabled_word",
            "inspect.disabled_word", "navigation.go_to", "navigation.go_to_hover",
            "navigation.more_locations", "help.no_close_topics", "inspect.block_permission", "lookup.player_required",
            "lookup.invalid_filter", "lookup.audit_required", "page.player_required", "page.invalid_session",
            "page.no_session", "status.db_stats_unavailable", "ingest.integration_disabled",
            "ingest.manual_not_queued", "ingest.manual_queued", "ingest.history_not_queued", "ingest.history_queued",
            "common.unknown_player", "common.unknown_dimension", "audit_events.header", "audit_events.row",
            "unified.header", "unified.quantity_unknown", "unified.quantity", "unified.subject", "unified.detail",
            "unified.row", "event.raw_evidence_note", "trace.header", "trace.window_limit", "trace.capped_from",
            "trace.counts", "trace.transformation_reference", "trace.transformation_reference_item",
            "trace.hop_during", "trace.hop_at", "status.source_disabled", "status.source_available",
            "status.source_unavailable", "status.summary", "status.connected", "status.disconnected", "status.not_configured",
            "status.action.db_disconnected", "status.action.recovery_blocked", "status.action.recovery_loading",
            "status.action.capture_stopped", "status.action.capture_disabled", "status.action.dropped_records",
            "status.action.no_stop_reported",
            "query.db_unavailable_status", "query.db_unavailable", "query.accepted_log", "query.accepted",
            "query.interrupted", "query.timed_out", "query.timed_out_queued", "query.server_unavailable",
            "browser.timeline_stale", "browser.amount",
            "browser.time_window", "browser.time", "browser.from", "browser.to", "browser.evidence_observation",
            "browser.evidence_transformation", "browser.inference_edge", "browser.related_fingerprint", "browser.details",
            "browser.fingerprint_id", "browser.hash", "browser.select_fingerprint", "browser.select_item",
            "browser.select_node", "browser.node_id", "browser.node_type", "browser.dimension",
            "browser.identity_unavailable", "browser.event_kind_unavailable", "browser.title_item",
            "browser.title_node", "browser.title_target", "explain.header_active", "explain.header_superseded",
            "explain.evidence_row", "trace.fingerprint_candidate_row", "browser.detail.time",
            "browser.detail.quantity", "browser.detail.item", "browser.detail.stored_details",
            "browser.detail.title", "browser.detail.observation", "browser.detail.transformation",
            "browser.menu_slot",
            "browser.detail.inference_edge", "browser.detail.previous", "browser.detail.previous_unavailable",
            "browser.detail.next", "browser.detail.next_unavailable", "browser.detail.back");
    private static final Pattern ARGUMENT = Pattern.compile("\\{(\\d+)(?:,[^{}]*)?}");
    private static volatile String locale = DEFAULT_LOCALE;
    private static final ConcurrentMap<String, String> SOURCE_INVENTORY = new ConcurrentHashMap<>();

    private ItemGraphLanguage() {}

    public static String validateLocale(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_LOCALES.contains(normalized)) {
            throw new IllegalArgumentException("general.language must be one of en_us, nl_nl, zh_tw; got: " + value);
        }
        return normalized;
    }

    public static void setLocale(String value) {
        validateCatalogs();
        locale = validateLocale(value);
    }
    public static String getLocale() { return locale; }

    /** Returns translated text, falling back per key to English and then to the supplied English source. */
    public static String text(String key, String english, Object... arguments) {
        String pattern = lookup(locale, key);
        if (pattern == null && !DEFAULT_LOCALE.equals(locale)) pattern = lookup(DEFAULT_LOCALE, key);
        if (pattern == null || !placeholders(pattern).equals(placeholders(english))) pattern = english;
        if (arguments == null || arguments.length == 0) return pattern;
        // Replace only numbered placeholders. Unlike MessageFormat this preserves literal
        // apostrophes/braces in authored fallback text and never parses raw argument values.
        Matcher matcher = ARGUMENT.matcher(pattern);
        StringBuilder formatted = new StringBuilder(pattern.length() + 24);
        int previous = 0;
        while (matcher.find()) {
            formatted.append(pattern, previous, matcher.start());
            int index = Integer.parseInt(matcher.group(1));
            if (index < arguments.length) formatted.append(String.valueOf(arguments[index]));
            else formatted.append(matcher.group());
            previous = matcher.end();
        }
        return formatted.append(pattern, previous, pattern.length()).toString();
    }

    /** Resolves a complete authored English UI phrase; unknown phrases deliberately remain readable English. */
    public static String sourceText(String english) {
        String digest = sourceKey(english);
        String previous = SOURCE_INVENTORY.putIfAbsent(digest, english);
        if (previous != null && !previous.equals(english)) {
            throw new IllegalStateException("ItemGraph source-message hash collision: " + digest);
        }
        return text("source." + digest, english);
    }

    public static Set<String> sourceInventory() { return Set.copyOf(SOURCE_INVENTORY.values()); }

    /** Returns the explicit English fallback inventory for all discovered source-text keys in a locale. */
    public static Set<String> sourceFallbackInventory(String selectedLocale) {
        String normalized = validateLocale(selectedLocale);
        ResourceBundle selected = bundle(normalized);
        Set<String> missing = new HashSet<>();
        SOURCE_INVENTORY.forEach((digest, english) -> {
            if (selected == null || !selected.containsKey("source." + digest)) missing.add(english);
        });
        return Set.copyOf(missing);
    }

    /** Required message keys that use per-key English fallback in the selected catalog. */
    public static Set<String> keyFallbackInventory(String selectedLocale) {
        String normalized = validateLocale(selectedLocale);
        ResourceBundle selected = bundle(normalized);
        Set<String> missing = new HashSet<>();
        for (String key : REQUIRED_KEYS) {
            if (selected == null || !selected.containsKey(key)) missing.add(key);
        }
        return Set.copyOf(missing);
    }

    public static String sourceKey(String english) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(english.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    /** Startup/build validation for the required runtime key inventory and translation placeholder parity. */
    public static void validateCatalogs() {
        com.itemgraph.command.CommandHelp.initializeMessages();
        ResourceBundle english = bundle(DEFAULT_LOCALE);
        if (english == null || !english.keySet().containsAll(REQUIRED_KEYS)) {
            throw new IllegalStateException("ItemGraph en_us message catalog is missing required keys: " + REQUIRED_KEYS);
        }
        for (String selected : SUPPORTED_LOCALES) {
            ResourceBundle catalog = bundle(selected);
            if (catalog == null) throw new IllegalStateException("Missing ItemGraph language resource: " + selected);
            for (String key : catalog.keySet()) {
                String englishValue;
                if (key.startsWith("source.")) {
                    englishValue = SOURCE_INVENTORY.get(key.substring("source.".length()));
                    if (englishValue == null) throw new IllegalStateException("Unknown ItemGraph source-language key: " + selected + ":" + key);
                } else {
                    if (!english.containsKey(key)) throw new IllegalStateException("Unknown ItemGraph language key: " + selected + ":" + key);
                    englishValue = english.getString(key);
                }
                if (!placeholders(catalog.getString(key)).equals(placeholders(englishValue))) {
                    throw new IllegalStateException("Placeholder mismatch in ItemGraph language key: " + selected + ":" + key);
                }
            }
        }
    }

    private static Set<Integer> placeholders(String pattern) {
        Set<Integer> result = new HashSet<>();
        Matcher matcher = ARGUMENT.matcher(pattern);
        while (matcher.find()) result.add(Integer.parseInt(matcher.group(1)));
        return result;
    }

    private static ResourceBundle bundle(String selected) {
        try {
            return ResourceBundle.getBundle("assets.itemgraph.lang." + selected, Locale.ROOT,
                    ItemGraphLanguage.class.getClassLoader());
        } catch (MissingResourceException missing) {
            return null;
        }
    }

    private static String lookup(String selected, String key) {
        ResourceBundle bundle = bundle(selected);
        try { return bundle == null ? null : bundle.getString(key); }
        catch (MissingResourceException missing) { return null; }
    }
}
