package com.itemgraph.command;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryCancellationJdbcTest {
    @Test
    void appliesJdbcTimeoutAndCancelsNonSqliteStatements() throws Exception {
        AtomicInteger timeoutSeconds = new AtomicInteger();
        AtomicInteger cancelCalls = new AtomicInteger();
        PreparedStatement rawStatement = (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("setQueryTimeout")) timeoutSeconds.set((int) args[0]);
                    if (method.getName().equals("cancel")) cancelCalls.incrementAndGet();
                    return defaultValue(method.getReturnType());
                });
        Connection rawConnection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement")) {
                        return rawStatement;
                    }
                    return defaultValue(method.getReturnType());
                });

        QueryDispatcher.QueryCancellation cancellation = new QueryDispatcher.QueryCancellation();
        Connection instrumented = cancellation.instrument(rawConnection);
        PreparedStatement statement = instrumented.prepareStatement("SELECT 1");
        assertEquals(5, timeoutSeconds.get());

        cancellation.cancel();
        assertEquals(1, cancelCalls.get());
        org.junit.jupiter.api.Assertions.assertFalse(cancellation.finish());
        assertThrows(SQLException.class, statement::executeQuery);
    }

    @Test
    void cancelsARunningSqliteQueryThroughTheIsolatedDriver() throws Exception {
        com.itemgraph.db.SqliteJdbc.initialize(java.nio.file.Path.of("build", "iglibs-test"));
        java.sql.Connection raw = com.itemgraph.db.SqliteJdbc.connect(
                "jdbc:sqlite::memory:", new java.util.Properties());
        try {
            QueryDispatcher.QueryCancellation cancellation = new QueryDispatcher.QueryCancellation();
            cancellation.attach(raw);
            java.sql.Connection instrumented = cancellation.instrument(raw);
            Thread canceller = new Thread(() -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {
                }
                cancellation.cancel();
            });
            long started = System.currentTimeMillis();
            canceller.start();
            SQLException thrown = assertThrows(SQLException.class, () -> {
                try (java.sql.Statement stmt = instrumented.createStatement();
                     java.sql.ResultSet rs = stmt.executeQuery(
                             "WITH RECURSIVE counter(x) AS ("
                                     + "SELECT 1 UNION ALL SELECT x + 1 FROM counter LIMIT 2000000000"
                                     + ") SELECT COUNT(*) FROM counter c1 JOIN counter c2 ON c2.x < 200")) {
                    rs.next();
                }
            });
            assertTrue(System.currentTimeMillis() - started < 30_000,
                    "the running query must abort promptly, not finish the Cartesian count");
            assertTrue(thrown.getMessage() == null
                            || thrown.getMessage().toLowerCase(java.util.Locale.ROOT)
                                    .contains("interrupt")
                            || thrown.getMessage().toLowerCase(java.util.Locale.ROOT)
                                    .contains("cancel"),
                    "expected an interrupt-style abort, got: " + thrown.getMessage());
        } finally {
            raw.close();
        }
    }

    @Test
    void completedQueryWinsOverLaterTimeoutCallback() {
        QueryDispatcher.QueryCancellation cancellation = new QueryDispatcher.QueryCancellation();
        org.junit.jupiter.api.Assertions.assertTrue(cancellation.finish());
        cancellation.cancel();
        org.junit.jupiter.api.Assertions.assertFalse(cancellation.isCancelled());
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        return 0D;
    }
}
