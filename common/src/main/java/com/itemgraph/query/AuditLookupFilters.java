package com.itemgraph.query;

import com.itemgraph.audit.EventTaxonomy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parsed, bounded filters for the GriefLogger-style native audit lookup.
 *
 * <p>The parser deliberately accepts the documented {@code name.value} form,
 * caps a request at twelve filters, and rejects ambiguous or unsupported values
 * before any SQL is dispatched.</p>
 */
public record AuditLookupFilters(
        List<String> eventTypes,
        List<String> playerNames,
        List<String> includeSubjects,
        List<String> excludeSubjects,
        double radiusBlocks,
        QueryWindow window,
        List<String> evidenceClasses,
        List<ItemMetadataPredicate> itemPredicates) {

    private static final int MAX_FILTERS = 12;
    private static final int MAX_VALUES_PER_FILTER = 32;
    private static final int MAX_NORMALIZED_EXPRESSION_LENGTH = 4_096;
    private static final long MINUTES_PER_HOUR = 60L;
    private static final long MINUTES_PER_DAY = 1_440L;
    private static final long MINUTES_PER_YEAR = 525_600L;

    public AuditLookupFilters {
        eventTypes = List.copyOf(eventTypes == null ? List.of() : eventTypes);
        playerNames = List.copyOf(playerNames == null ? List.of() : playerNames);
        includeSubjects = List.copyOf(includeSubjects == null ? List.of() : includeSubjects);
        excludeSubjects = List.copyOf(excludeSubjects == null ? List.of() : excludeSubjects);
        evidenceClasses = List.copyOf(evidenceClasses == null ? List.of() : evidenceClasses);
        itemPredicates = List.copyOf(itemPredicates == null ? List.of() : itemPredicates);
        if (!Double.isFinite(radiusBlocks) || radiusBlocks < 1.0
                || radiusBlocks > AuditEventQueryService.MAX_RADIUS_BLOCKS) {
            throw new IllegalArgumentException("radius must be between 1 and "
                    + (int) AuditEventQueryService.MAX_RADIUS_BLOCKS + " blocks");
        }
        if (window == null) {
            throw new IllegalArgumentException("time window must be non-null");
        }
        if (!includeSubjects.isEmpty() && !excludeSubjects.isEmpty()) {
            throw new IllegalArgumentException("include and exclude filters cannot be combined");
        }
    }

    public AuditLookupFilters(List<String> eventTypes, List<String> playerNames,
                              List<String> includeSubjects, List<String> excludeSubjects,
                              double radiusBlocks, QueryWindow window) {
        this(eventTypes, playerNames, includeSubjects, excludeSubjects, radiusBlocks, window, List.of(), List.of());
    }

    public AuditLookupFilters(List<String> eventTypes, List<String> playerNames,
                              List<String> includeSubjects, List<String> excludeSubjects,
                              double radiusBlocks, QueryWindow window, List<String> evidenceClasses) {
        this(eventTypes, playerNames, includeSubjects, excludeSubjects, radiusBlocks, window,
                evidenceClasses, List.of());
    }

    /** Parses one or more bounded whitespace-separated lookup filter tokens. */
    public static AuditLookupFilters parse(String expression, long nowMs) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("at least one filter is required");
        }
        if (expression.length() > MAX_NORMALIZED_EXPRESSION_LENGTH) {
            throw new IllegalArgumentException("lookup filters support at most "
                    + MAX_NORMALIZED_EXPRESSION_LENGTH + " characters");
        }
        List<String> tokens = tokenize(expression);
        if (tokens.size() > MAX_FILTERS) {
            throw new IllegalArgumentException("at most " + MAX_FILTERS + " filters are allowed");
        }

        List<String> actions = List.of();
        List<String> users = List.of();
        List<String> includes = List.of();
        List<String> excludes = List.of();
        double radius = -1.0;
        QueryWindow window = null;
        List<String> states = List.of();
        List<ItemMetadataPredicate> metadataPredicates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String after = null;
        String before = null;
        String between = null;

        for (String token : tokens) {
            int separator = token.indexOf('.');
            if (separator <= 0 || separator == token.length() - 1) {
                throw new IllegalArgumentException("invalid filter '" + token
                        + "'; use name.value");
            }
            String name = token.substring(0, separator).toLowerCase(Locale.ROOT);
            String value = token.substring(separator + 1);
            String canonicalName = switch (name) {
                case "a", "action" -> "action";
                case "u", "user" -> "user";
                case "i", "include" -> "include";
                case "e", "exclude" -> "exclude";
                case "r", "radius" -> "radius";
                case "t", "time" -> "time";
                case "s", "state", "status" -> "state";
                case "after", "before", "between" -> name;
                case "item", "fingerprint", "name", "damage", "trim", "enchantment", "lore", "component" -> name;
                default -> throw new IllegalArgumentException("unknown filter '" + name + "'");
            };
            boolean metadata = isMetadataFilter(canonicalName);
            if (!metadata && !seen.add(canonicalName)) {
                throw new IllegalArgumentException("filter '" + canonicalName + "' may be used once");
            }

            switch (canonicalName) {
                case "action" -> actions = parseActions(value);
                case "user" -> users = parseUsers(value);
                case "include" -> includes = parseSubjects(value);
                case "exclude" -> excludes = parseSubjects(value);
                case "radius" -> radius = parseRadius(decodeTextValue(value, "radius"));
                case "time" -> window = QueryWindow.lastMinutes(parseMinutes(decodeTextValue(value, "time")), nowMs);
                case "state" -> states = parseEvidenceClasses(value);
                case "after" -> after = decodeTextValue(value, "after");
                case "before" -> before = decodeTextValue(value, "before");
                case "between" -> between = decodeTextValue(value, "between");
                case "item", "fingerprint", "name", "damage", "trim", "enchantment", "lore", "component" -> {
                    String predicateValue = "component".equals(canonicalName)
                            ? value : decodeTextValue(value, canonicalName);
                    ItemMetadataPredicate predicate = ItemMetadataPredicate.parse(canonicalName, predicateValue);
                    if (metadataPredicates.stream().anyMatch(existing ->
                            existing.uniquenessKey().equals(predicate.uniquenessKey()))) {
                        throw new IllegalArgumentException("metadata filter '" + canonicalName + "' may be used once per key");
                    }
                    metadataPredicates.add(predicate);
                }
                default -> throw new IllegalStateException("Unhandled filter " + canonicalName);
            }
        }
        if (window != null && (after != null || before != null || between != null)) {
            throw new IllegalArgumentException("time cannot be combined with absolute time filters");
        }
        if (between != null && (after != null || before != null)) {
            throw new IllegalArgumentException("between cannot be combined with after or before");
        }
        if (between != null) {
            String[] bounds = between.split(",", -1);
            if (bounds.length != 2) {
                throw new IllegalArgumentException("between must use startUTC,endUTC");
            }
            window = QueryWindow.between(bounds[0], bounds[1]);
        } else if (after != null || before != null) {
            QueryWindow lower = after == null ? QueryWindow.unbounded() : QueryWindow.after(after);
            QueryWindow upper = before == null ? QueryWindow.unbounded() : QueryWindow.before(before);
            Long since = lower.sinceMs();
            Long until = upper.untilMs();
            if (since != null && until != null && since > until) {
                throw new IllegalArgumentException("after timestamp must be before the before timestamp");
            }
            window = new QueryWindow(since, until);
        }
        if (radius < 0.0) {
            throw new IllegalArgumentException("radius filter is required");
        }
        if (!includes.isEmpty() && !excludes.isEmpty()) {
            throw new IllegalArgumentException("include and exclude filters cannot be combined");
        }
        return new AuditLookupFilters(actions, users, includes, excludes, radius,
                window == null ? QueryWindow.unbounded() : window, states, metadataPredicates);
    }

    /** Parses bounded exact metadata tokens without requiring a location radius. */
    public static List<ItemMetadataPredicate> parseMetadataPredicates(String expression) {
        if (expression == null || expression.isBlank()) {
            return List.of();
        }
        if (expression.length() > MAX_NORMALIZED_EXPRESSION_LENGTH) {
            throw new IllegalArgumentException("item metadata filters support at most "
                    + MAX_NORMALIZED_EXPRESSION_LENGTH + " characters");
        }
        List<String> tokens = tokenize(expression);
        if (tokens.size() > MAX_FILTERS) {
            throw new IllegalArgumentException("at most " + MAX_FILTERS + " filters are allowed");
        }
        List<ItemMetadataPredicate> predicates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String token : tokens) {
            int separator = token.indexOf('.');
            if (separator <= 0 || separator == token.length() - 1) {
                throw new IllegalArgumentException("invalid metadata filter '" + token + "'; use name.value");
            }
            String kind = token.substring(0, separator).toLowerCase(Locale.ROOT);
            if (!isMetadataFilter(kind)) {
                throw new IllegalArgumentException("only item metadata filters are supported here: item, fingerprint, name, damage, trim, enchantment, lore, component");
            }
            String value = token.substring(separator + 1);
            ItemMetadataPredicate predicate = ItemMetadataPredicate.parse(kind,
                    "component".equals(kind) ? value : decodeTextValue(value, kind));
            if (!seen.add(predicate.uniquenessKey())) {
                throw new IllegalArgumentException("metadata filter '" + kind + "' may be used once per key");
            }
            predicates.add(predicate);
        }
        return List.copyOf(predicates);
    }

    public String describe() {
        List<String> parts = new ArrayList<>();
        if (!eventTypes.isEmpty()) {
            parts.add("action=" + String.join(",", eventTypes));
        }
        if (!playerNames.isEmpty()) {
            parts.add("user=" + String.join(",", playerNames));
        }
        if (!includeSubjects.isEmpty()) {
            parts.add("include=" + String.join(",", includeSubjects));
        }
        if (!excludeSubjects.isEmpty()) {
            parts.add("exclude=" + String.join(",", excludeSubjects));
        }
        if (!evidenceClasses.isEmpty()) {
            parts.add("state=" + String.join(",", evidenceClasses));
        }
        itemPredicates.stream().map(ItemMetadataPredicate::normalizedToken).forEach(parts::add);
        parts.add("radius=" + trimRadius(radiusBlocks));
        parts.add("window=" + window.describe());
        parts.add(window.normalizedPredicate());
        return String.join(" ", parts);
    }

    public boolean requiresComponentIndex() {
        return itemPredicates.stream().anyMatch(predicate ->
                predicate.kind() != ItemMetadataPredicate.Kind.ITEM_ID
                        && predicate.kind() != ItemMetadataPredicate.Kind.FINGERPRINT);
    }

    /** Describes the applied query without copying identity or inventory filter values. */
    public String describeRedacted() {
        List<String> parts = new ArrayList<>();
        parts.add("action=" + (eventTypes.isEmpty() ? "all" : String.join(",", eventTypes)));
        parts.add("user_filter_applied=" + !playerNames.isEmpty());
        parts.add("subject_filter_applied=" + (!includeSubjects.isEmpty() || !excludeSubjects.isEmpty()));
        parts.add("state=" + (evidenceClasses.isEmpty() ? "all" : String.join(",", evidenceClasses)));
        parts.add("item_metadata_predicates=" + itemPredicates.size());
        parts.add("radius=" + trimRadius(radiusBlocks));
        parts.add("window=" + window.describe());
        parts.add(window.normalizedPredicate());
        return String.join(" ", parts);
    }

    private static List<String> parseActions(String value) {
        List<String> actions = splitValues(value, "action");
        List<String> result = new ArrayList<>(actions.size());
        for (String action : actions) {
            String normalized = action.toLowerCase(Locale.ROOT).replace('-', '_');
            if ("all".equals(normalized)) {
                return List.of();
            }
            String eventType = EventTaxonomy.canonicalAction(normalized).orElseThrow(() ->
                    new IllegalArgumentException("unsupported audit or item-flow action '" + action + "'"));
            if (!result.contains(eventType)) {
                result.add(eventType);
            }
        }
        return List.copyOf(result);
    }

    private static List<String> parseUsers(String value) {
        return splitValues(value, "user");
    }

    private static List<String> parseSubjects(String value) {
        return splitValues(value, "subject").stream()
                .map(AuditLookupFilters::normalizeSubject)
                .toList();
    }

    private static List<String> parseEvidenceClasses(String value) {
        List<String> values = splitValues(value, "state");
        List<String> normalized = new ArrayList<>(values.size());
        for (String state : values) {
            String canonical = state.toUpperCase(Locale.ROOT);
            try {
                com.itemgraph.audit.EventTaxonomy.EvidenceClass.valueOf(canonical);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("state must be observed, inferred, ambiguous, or unresolved");
            }
            if (!normalized.contains(canonical)) {
                normalized.add(canonical);
            }
        }
        return List.copyOf(normalized);
    }

    private static List<String> splitValues(String value, String kind) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(kind + " filter cannot be empty");
        }
        List<String> values = new ArrayList<>();
        for (String raw : splitOutsideQuotes(value, ',')) {
            String trimmed = decodeTextValue(raw.trim(), kind);
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException(kind + " filter contains an empty value");
            }
            if (!values.contains(trimmed)) {
                values.add(trimmed);
            }
            if (values.size() > MAX_VALUES_PER_FILTER) {
                throw new IllegalArgumentException(kind + " filter supports at most "
                        + MAX_VALUES_PER_FILTER + " values");
            }
        }
        return List.copyOf(values);
    }

    private static String normalizeSubject(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.indexOf(':') >= 0 ? normalized : "minecraft:" + normalized;
    }

    private static double parseRadius(String value) {
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed) || parsed < 1.0) {
                throw new NumberFormatException();
            }
            return Math.min(AuditEventQueryService.MAX_RADIUS_BLOCKS, parsed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("radius must be a positive number", e);
        }
    }

    private static long parseMinutes(String value) {
        if (value == null || value.length() < 2) {
            throw new IllegalArgumentException("time must use a number followed by m, h, d, or y");
        }
        char unit = Character.toLowerCase(value.charAt(value.length() - 1));
        long amount;
        try {
            amount = Long.parseLong(value.substring(0, value.length() - 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("time must use a whole number followed by m, h, d, or y", e);
        }
        if (amount < 1) {
            throw new IllegalArgumentException("time must be at least one unit");
        }
        long multiplier = switch (unit) {
            case 'm' -> 1L;
            case 'h' -> MINUTES_PER_HOUR;
            case 'd' -> MINUTES_PER_DAY;
            case 'y' -> MINUTES_PER_YEAR;
            default -> throw new IllegalArgumentException("time unit must be m, h, d, or y");
        };
        try {
            return Math.multiplyExact(amount, multiplier);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("time range is too large", e);
        }
    }

    private static List<String> tokenize(String expression) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quotedFilterValue = false;
        boolean escapedFilterValue = false;
        boolean inJsonString = false;
        boolean escapedJsonString = false;
        int jsonDepth = 0;
        for (int i = 0; i < expression.length(); i++) {
            char ch = expression.charAt(i);
            if (jsonDepth > 0 && inJsonString) {
                current.append(ch);
                if (escapedJsonString) {
                    escapedJsonString = false;
                } else if (ch == '\\') {
                    escapedJsonString = true;
                } else if (ch == '"') {
                    inJsonString = false;
                }
            } else if (jsonDepth > 0 && ch == '"') {
                inJsonString = true;
                current.append(ch);
            } else if (escapedFilterValue) {
                current.append('\\').append(ch);
                escapedFilterValue = false;
            } else if (quotedFilterValue && ch == '\\') {
                escapedFilterValue = true;
            } else if (ch == '"') {
                quotedFilterValue = !quotedFilterValue;
                current.append(ch);
            } else if (!quotedFilterValue && (ch == '{' || ch == '[')) {
                jsonDepth++;
                current.append(ch);
            } else if (!quotedFilterValue && (ch == '}' || ch == ']')) {
                jsonDepth--;
                current.append(ch);
            } else if (Character.isWhitespace(ch) && !quotedFilterValue && jsonDepth == 0) {
                if (!current.isEmpty()) {
                    tokens.add(unwrapQuotedFilterToken(current.toString()));
                    current.setLength(0);
                }
            } else {
                current.append(ch);
            }
        }
        if (escapedFilterValue || quotedFilterValue || escapedJsonString || inJsonString || jsonDepth != 0) {
            throw new IllegalArgumentException("quoted filter value is not closed");
        }
        if (!current.isEmpty()) {
            tokens.add(unwrapQuotedFilterToken(current.toString()));
        }
        return List.copyOf(tokens);
    }

    private static String unwrapQuotedFilterToken(String token) {
        return token.length() >= 2 && token.charAt(0) == '"' && token.charAt(token.length() - 1) == '"'
                ? token.substring(1, token.length() - 1) : token;
    }

    private static List<String> splitOutsideQuotes(String value, char separator) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (escaped) {
                current.append('\\').append(ch);
                escaped = false;
            } else if (quoted && ch == '\\') {
                escaped = true;
            } else if (ch == '"') {
                quoted = !quoted;
                current.append(ch);
            } else if (ch == separator && !quoted) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        if (quoted || escaped) {
            throw new IllegalArgumentException("quoted filter value is not closed");
        }
        parts.add(current.toString());
        return parts;
    }

    private static String decodeTextValue(String value, String kind) {
        if (!value.startsWith("\"")) {
            return value;
        }
        if (value.length() < 2 || !value.endsWith("\"")) {
            throw new IllegalArgumentException(kind + " quoted value is not closed");
        }
        StringBuilder decoded = new StringBuilder();
        for (int i = 1; i < value.length() - 1; i++) {
            char ch = value.charAt(i);
            if (ch == '\\') {
                if (++i >= value.length() - 1) {
                    throw new IllegalArgumentException(kind + " quoted value has a dangling escape");
                }
                char escaped = value.charAt(i);
                if (escaped != '\\' && escaped != '"') {
                    throw new IllegalArgumentException("only a backslash or quote may be escaped in quoted values");
                }
                decoded.append(escaped);
            } else {
                decoded.append(ch);
            }
        }
        return decoded.toString();
    }

    private static boolean isMetadataFilter(String name) {
        return switch (name) {
            case "item", "fingerprint", "name", "damage", "trim", "enchantment", "lore", "component" -> true;
            default -> false;
        };
    }

    private static String trimRadius(double radius) {
        return radius == Math.rint(radius) ? Long.toString((long) radius) : Double.toString(radius);
    }
}
