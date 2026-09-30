package com.itemgraph.command;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HistoricalUsernameSuggestionsTest {
    @Test
    void readsDistinctHistoricalNamesFromPreservedReferenceRows() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ig_grieflogger_rows (table_name TEXT, payload_json TEXT)");
            statement.execute("""
                    INSERT INTO ig_grieflogger_rows VALUES
                    ('usernames', '{"id":1,"time":10,"uuid":"uuid-a","name":"Zelda"}'),
                    ('usernames', '{"id":2,"time":20,"uuid":"uuid-a","name":"Alice"}'),
                    ('usernames', '{"id":3,"time":30,"uuid":"uuid-b","name":"zelda"}'),
                    ('usernames', '{"id":4,"time":40,"uuid":"uuid-c","username":"OBrien"}'),
                    ('usernames', '{"id":5,"name":" "}'),
                    ('usernames', 'not-json'),
                    ('users', '{"id":1,"name":"CurrentPlayer"}')
                    """);

            assertEquals(List.of("Alice", "CurrentPlayer", "OBrien", "Zelda"),
                    HistoricalUsernameSuggestions.load(connection));
        }
    }

    @Test
    void mergesOnlineAndHistoricalNamesWithoutCaseInsensitiveDuplicates() {
        assertEquals(List.of("Alice", "Zelda", "OfflinePlayer"),
                HistoricalUsernameSuggestions.merge(
                        List.of("Alice", "Zelda"), List.of("offlineplayer", "alice", "OfflinePlayer")));
    }

    @Test
    void failedRefreshPreservesLastGoodNamesAndRetriesAfterBackoff() {
        HistoricalUsernameSuggestions.NameCache cache = new HistoricalUsernameSuggestions.NameCache();
        AtomicLong now = new AtomicLong();
        assertEquals(List.of("Alice"), cache.get(
                () -> CompletableFuture.completedFuture(List.of("Alice")), now::get).join());

        now.set(31_000_000_000L);
        assertEquals(List.of("Alice"), cache.get(
                () -> CompletableFuture.failedFuture(new IllegalStateException("temporary read failure")),
                now::get).join());

        AtomicInteger retryCalls = new AtomicInteger();
        now.set(32_000_000_000L);
        assertEquals(List.of("Alice"), cache.get(() -> {
            retryCalls.incrementAndGet();
            return CompletableFuture.completedFuture(List.of("Bob"));
        }, now::get).join());
        assertEquals(0, retryCalls.get(), "failed reads must be backed off");

        now.set(37_000_000_000L);
        assertEquals(List.of("Bob"), cache.get(
                () -> CompletableFuture.completedFuture(List.of("Bob")), now::get).join());
    }

    @Test
    void queueRejectionPreservesLastGoodNamesAndUsesBackoff() {
        HistoricalUsernameSuggestions.NameCache cache = new HistoricalUsernameSuggestions.NameCache();
        AtomicLong now = new AtomicLong();
        cache.get(() -> CompletableFuture.completedFuture(List.of("Alice")), now::get).join();
        now.set(31_000_000_000L);
        assertEquals(List.of("Alice"), cache.get(() -> {
            throw new RejectedExecutionException("full");
        }, now::get).join());

        AtomicInteger retryCalls = new AtomicInteger();
        now.set(32_000_000_000L);
        assertEquals(List.of("Alice"), cache.get(() -> {
            retryCalls.incrementAndGet();
            return CompletableFuture.completedFuture(List.of("Bob"));
        }, now::get).join());
        assertEquals(0, retryCalls.get());
    }
}
