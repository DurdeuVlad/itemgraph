package com.itemgraph.command;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.itemgraph.db.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Supplies online and imported GriefLogger identity/name values for user filters. */
final class HistoricalUsernameSuggestions {
    private static final long CACHE_TTL_NANOS = 30_000_000_000L;
    private static final long FAILURE_RETRY_NANOS = 5_000_000_000L;
    private static final NameCache CACHE = new NameCache();

    private HistoricalUsernameSuggestions() {}

    static CompletableFuture<List<String>> values(List<String> onlineNames) {
        List<String> online = List.copyOf(onlineNames);
        return historicalNames().thenApply(historical -> merge(online, historical));
    }

    private static CompletableFuture<List<String>> historicalNames() {
        return CACHE.get(() -> {
            if (!DatabaseManager.getInstance().isInitialized()) {
                return CompletableFuture.failedFuture(
                        new SQLException("ItemGraph database is not initialized"));
            }
            return QueryDispatcher.submitData(HistoricalUsernameSuggestions::load);
        }, System::nanoTime);
    }

    static List<String> load(Connection connection) throws SQLException {
        List<String> names = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT payload_json
                FROM ig_grieflogger_rows
                WHERE table_name IN ('users', 'usernames') AND payload_json IS NOT NULL
                """)) {
            statement.setQueryTimeout(5);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name = usernameFromPayload(rows.getString(1));
                    if (name != null) {
                        names.add(name);
                    }
                }
            }
        }
        return merge(List.of(), names);
    }

    private static String usernameFromPayload(String payload) {
        try {
            JsonElement parsed = JsonParser.parseString(payload);
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject object = parsed.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (!entry.getKey().equalsIgnoreCase("name")
                        && !entry.getKey().equalsIgnoreCase("username")) {
                    continue;
                }
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    String name = value.getAsString().trim();
                    return name.isEmpty() ? null : name;
                }
            }
        } catch (RuntimeException ignored) {
            // A malformed provenance row must not prevent completion for valid names.
        }
        return null;
    }

    static List<String> merge(List<String> online, List<String> historical) {
        Map<String, String> namesByNormalizedName = new LinkedHashMap<>();
        for (String name : online) {
            add(namesByNormalizedName, name);
        }
        historical.stream()
                .filter(name -> name != null && !name.isBlank())
                .sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(java.util.Comparator.naturalOrder()))
                .forEach(name -> add(namesByNormalizedName, name));
        return List.copyOf(namesByNormalizedName.values());
    }

    private static void add(Map<String, String> names, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return;
        }
        String normalized = candidate.toLowerCase(Locale.ROOT);
        names.putIfAbsent(normalized, candidate);
    }

    static final class NameCache {
        private List<String> names = List.of();
        private long loadedAtNanos;
        private long retryAfterNanos;
        private boolean loaded;
        private boolean retryBackoffActive;
        private CompletableFuture<List<String>> refresh;

        CompletableFuture<List<String>> get(
                Supplier<CompletableFuture<List<String>>> loader, LongSupplier clock) {
            CompletableFuture<List<String>> query;
            CompletableFuture<List<String>> result;
            synchronized (this) {
                long now = clock.getAsLong();
                if (loaded && now - loadedAtNanos < CACHE_TTL_NANOS) {
                    return CompletableFuture.completedFuture(names);
                }
                if (refresh != null) {
                    return refresh;
                }
                if (retryBackoffActive && now - retryAfterNanos < 0) {
                    return CompletableFuture.completedFuture(names);
                }
                try {
                    query = loader.get();
                } catch (RuntimeException failure) {
                    recordFailure(now);
                    return CompletableFuture.completedFuture(names);
                }
                result = new CompletableFuture<>();
                refresh = result;
            }

            query.whenComplete((loadedNames, failure) -> {
                List<String> snapshot;
                synchronized (this) {
                    long completedAt = clock.getAsLong();
                    if (failure == null) {
                        names = List.copyOf(loadedNames);
                        loadedAtNanos = completedAt;
                        loaded = true;
                        retryBackoffActive = false;
                    } else {
                        recordFailure(completedAt);
                    }
                    snapshot = names;
                    if (refresh == result) {
                        refresh = null;
                    }
                }
                result.complete(snapshot);
            });
            return result;
        }

        private void recordFailure(long now) {
            // Preserve a previous successful result and avoid retrying on every completion packet.
            retryAfterNanos = now + FAILURE_RETRY_NANOS;
            retryBackoffActive = true;
        }
    }
}
