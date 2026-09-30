package com.itemgraph.command;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
                (proxy, method, args) -> method.getName().equals("prepareStatement")
                        ? rawStatement : defaultValue(method.getReturnType()));

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
