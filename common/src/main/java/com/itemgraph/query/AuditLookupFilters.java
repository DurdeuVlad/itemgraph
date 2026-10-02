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
 * caps a request at five filters, and rejects ambiguous or unsupported values
 * before any SQL is dispatched.</p>
 */
public record AuditLookupFilters(
        List<String> eventTypes,
        List<String> playerNames,
        List<String> includeSubjects,
        List<String> excludeSubjects,
        double radiusBlocks,
        QueryWindow window) {

    private static final int MAX_FILTERS = 5;
    private static final int MAX_VALUES_PER_FILTER = 32;
    private static final long MINUTES_PER_HOUR = 60L;
    private static final long MINUTES_PER_DAY = 1_440L;
    private static final long MINUTES_PER_YEAR = 525_600L;

    public AuditLookupFilters {
        eventTypes = List.copyOf(eventTypes == null ? List.of() : eventTypes);
        playerNames = List.copyOf(playerNames == null ? List.of() : playerNames);
        includeSubjects = List.copyOf(includeSubjects == null ? List.of() : includeSubjects);
        excludeSubjects = List.copyOf(excludeSubjects == null ? List.of() : excludeSubjects);
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

    /** Parses one or more whitespace-separated GriefLogger-style filter tokens. */
    public static AuditLookupFilters parse(String expression, long nowMs) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("at least one filter is required");
        }
        String[] tokens = expression.trim().split("\\s+");
        if (tokens.length > MAX_FILTERS) {
            throw new IllegalArgumentException("at most " + MAX_FILTERS + " filters are allowed");
        }

        List<String> actions = List.of();
        List<String> users = List.of();
        List<String> includes = List.of();
        List<String> excludes = List.of();
        double radius = -1.0;
        QueryWindow window = null;
        Set<String> seen = new HashSet<>();

        for (String rawToken : tokens) {
            String token = stripQuotes(rawToken);
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
                default -> throw new IllegalArgumentException("unknown filter '" + name + "'");
            };
            if (!seen.add(canonicalName)) {
                throw new IllegalArgumentException("filter '" + canonicalName + "' may be used once");
            }

            switch (canonicalName) {
                case "action" -> actions = parseActions(value);
                case "user" -> users = parseUsers(value);
                case "include" -> includes = parseSubjects(value);
                case "exclude" -> excludes = parseSubjects(value);
                case "radius" -> radius = parseRadius(value);
                case "time" -> window = QueryWindow.lastMinutes(parseMinutes(value), nowMs);
                default -> throw new IllegalStateException("Unhandled filter " + canonicalName);
            }
        }
        if (radius < 0.0) {
            throw new IllegalArgumentException("radius filter is required");
        }
        if (!includes.isEmpty() && !excludes.isEmpty()) {
            throw new IllegalArgumentException("include and exclude filters cannot be combined");
        }
        return new AuditLookupFilters(actions, users, includes, excludes, radius,
                window == null ? QueryWindow.unbounded() : window);
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
        parts.add("radius=" + trimRadius(radiusBlocks));
        parts.add("window=" + window.describe());
        return String.join(" ", parts);
    }

    /** Describes the applied query without copying identity or inventory filter values. */
    public String describeRedacted() {
        List<String> parts = new ArrayList<>();
        parts.add("action=" + (eventTypes.isEmpty() ? "all" : String.join(",", eventTypes)));
        parts.add("user_filter_applied=" + !playerNames.isEmpty());
        parts.add("subject_filter_applied=" + (!includeSubjects.isEmpty() || !excludeSubjects.isEmpty()));
        parts.add("radius=" + trimRadius(radiusBlocks));
        parts.add("window=" + window.describe());
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

    private static List<String> splitValues(String value, String kind) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(kind + " filter cannot be empty");
        }
        List<String> values = new ArrayList<>();
        for (String raw : value.split(",", -1)) {
            String trimmed = raw.trim();
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

    private static String stripQuotes(String token) {
        if (token.length() >= 2 && token.startsWith("\"") && token.endsWith("\"")) {
            return token.substring(1, token.length() - 1);
        }
        return token;
    }

    private static String trimRadius(double radius) {
        return radius == Math.rint(radius) ? Long.toString((long) radius) : Double.toString(radius);
    }
}
