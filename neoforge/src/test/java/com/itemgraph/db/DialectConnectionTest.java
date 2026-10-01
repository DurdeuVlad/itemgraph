package com.itemgraph.db;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DialectConnectionTest {
    private static final String SECRET = "jdbc:mariadb://user:host-secret@private-host/private-database";
    private static final SQLException DRIVER_FAILURE = new SQLException(SECRET, "08006", 1042);

    @Test
    void connectionStatementAndResultSetFailuresAreRedactedButKeepSqlState() {
        Connection delegate = proxy(Connection.class, (method, args) -> {
            if (method.equals("isValid")) throw DRIVER_FAILURE;
            if (method.equals("createStatement")) {
                return proxy(Statement.class, (statementMethod, statementArgs) -> {
                    if (statementMethod.equals("execute")) throw DRIVER_FAILURE;
                    if (statementMethod.equals("executeQuery")) {
                        return proxy(ResultSet.class, (resultMethod, resultArgs) -> {
                            if (resultMethod.equals("next")) throw DRIVER_FAILURE;
                            return defaultValue(resultMethod);
                        });
                    }
                    return defaultValue(statementMethod);
                });
            }
            return defaultValue(method);
        });
        Connection connection = DialectConnection.wrap(delegate, DatabaseDialect.MYSQL_MARIADB);

        assertRedacted(assertThrows(SQLException.class, () -> connection.isValid(1)));
        try (Statement statement = connection.createStatement()) {
            assertRedacted(assertThrows(SQLException.class, () -> statement.execute("SELECT 1")));
            ResultSet resultSet = statement.executeQuery("SELECT 1");
            assertRedacted(assertThrows(SQLException.class, resultSet::next));
        } catch (SQLException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void metadataResultSetFailuresAreRedacted() throws Exception {
        ResultSet rawResult = proxy(ResultSet.class, (method, args) -> {
            if (method.equals("next")) throw DRIVER_FAILURE;
            return defaultValue(method);
        });
        DatabaseMetaData metadata = proxy(DatabaseMetaData.class, (method, args) ->
                method.equals("getTables") ? rawResult : defaultValue(method));
        Connection delegate = proxy(Connection.class, (method, args) ->
                method.equals("getMetaData") ? metadata : defaultValue(method));
        Connection connection = DialectConnection.wrap(delegate, DatabaseDialect.MYSQL_MARIADB);

        assertRedacted(assertThrows(SQLException.class, () -> connection.getMetaData()
                .getTables(null, null, "%", null).next()));
    }

    @Test
    void openingIndependentNetworkConnectionRedactsDriverMessage() {
        DatabaseSettings settings = DatabaseSettings.mysqlMariaDb(
                "jdbc:mariadb://user:host-secret@127.0.0.1", 1, "private_database", "private_user",
                "database-secret", 250, true);

        SQLException failure = assertThrows(SQLException.class,
                () -> DatabaseManager.openIndependentConnection(settings, true));

        assertFalse(failure.getMessage().contains("host-secret"), failure.getMessage());
        assertFalse(failure.getMessage().contains("private_database"), failure.getMessage());
        assertFalse(failure.getMessage().contains("private_user"), failure.getMessage());
        assertFalse(failure.getMessage().contains("database-secret"), failure.getMessage());
        assertFalse(failure.getMessage().contains("jdbc:mariadb"), failure.getMessage());
    }

    private static void assertRedacted(SQLException failure) {
        assertEquals("ItemGraph database operation failed (SQL state 08006)", failure.getMessage());
        assertEquals("08006", failure.getSQLState());
        assertEquals(1042, failure.getErrorCode());
        assertFalse(failure.getMessage().contains("host-secret"));
    }

    private static <T> T proxy(Class<T> type, JdbcCall call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "test " + type.getSimpleName();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            }
            return call.invoke(method.getName(), args);
        }));
    }

    private static Object defaultValue(String method) {
        return switch (method) {
            case "isClosed" -> false;
            case "isValid" -> true;
            case "execute" -> false;
            case "executeUpdate" -> 0;
            case "getUpdateCount" -> -1;
            default -> null;
        };
    }

    @FunctionalInterface
    private interface JdbcCall {
        Object invoke(String method, Object[] args) throws Throwable;
    }
}
