package com.itemgraph.db;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/** JDBC proxy that applies {@link DialectSql} to statements on network storage. */
final class DialectConnection {
    private DialectConnection() {
    }

    static Connection wrap(Connection delegate, DatabaseDialect dialect) {
        if (dialect == DatabaseDialect.SQLITE) {
            return delegate;
        }
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new ConnectionHandler(delegate, dialect));
    }

    private static final class ConnectionHandler implements InvocationHandler {
        private final Connection delegate;
        private final DatabaseDialect dialect;

        private ConnectionHandler(Connection delegate, DatabaseDialect dialect) {
            this.delegate = delegate;
            this.dialect = dialect;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            try {
                Object result;
                if (method.getName().equals("createStatement")) {
                    result = method.invoke(delegate, args);
                    return wrapStatement((Statement) result, Statement.class, dialect);
                }
                if (method.getName().equals("prepareStatement")) {
                    Object[] translated = translateFirstSql(args, dialect);
                    result = method.invoke(delegate, translated);
                    return wrapStatement((Statement) result, PreparedStatement.class, dialect);
                }
                if (method.getName().equals("prepareCall")) {
                    Object[] translated = translateFirstSql(args, dialect);
                    result = method.invoke(delegate, translated);
                    return wrapStatement((Statement) result, CallableStatement.class, dialect);
                }
                result = method.invoke(delegate, args);
                return wrapJdbcResult(result, dialect);
            } catch (InvocationTargetException e) {
                throw sanitize(e.getCause());
            }
        }
    }

    private static Object[] translateFirstSql(Object[] args, DatabaseDialect dialect) {
        if (args == null || args.length == 0 || !(args[0] instanceof String)) {
            return args;
        }
        Object[] copy = args.clone();
        copy[0] = DialectSql.translate((String) args[0], dialect);
        return copy;
    }

    private static Statement wrapStatement(Statement delegate, Class<?> primaryInterface,
                                           DatabaseDialect dialect) {
        if (delegate == null) {
            return null;
        }
        Class<?>[] interfaces = primaryInterface == Statement.class
                ? new Class<?>[]{Statement.class}
                : new Class<?>[]{primaryInterface, Statement.class};
        return (Statement) Proxy.newProxyInstance(
                Statement.class.getClassLoader(), interfaces,
                new StatementHandler(delegate, dialect));
    }

    private static final class StatementHandler implements InvocationHandler {
        private final Statement delegate;
        private final DatabaseDialect dialect;

        private StatementHandler(Statement delegate, DatabaseDialect dialect) {
            this.delegate = delegate;
            this.dialect = dialect;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object[] translated = args;
            if (args != null && args.length > 0 && args[0] instanceof String
                    && isSqlMethod(method.getName())) {
                translated = args.clone();
                translated[0] = DialectSql.translate((String) args[0], dialect);
            }
            try {
                return wrapJdbcResult(method.invoke(delegate, translated), dialect);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof SQLException sql && isIgnorableDuplicate(sql, method.getName())) {
                    return defaultResult(method.getReturnType());
                }
                throw sanitize(cause);
            }
        }

        private static boolean isSqlMethod(String name) {
            return name.equals("execute") || name.equals("executeQuery")
                    || name.equals("executeUpdate") || name.equals("executeLargeUpdate")
                    || name.equals("addBatch");
        }

        private static boolean isIgnorableDuplicate(SQLException error, String method) {
            String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
            String state = error.getSQLState() == null ? "" : error.getSQLState();
            boolean duplicateIndex = message.contains("duplicate key name")
                    || message.contains("already exists")
                    || message.contains("duplicate key");
            boolean unknownIndex = message.contains("can't drop") && message.contains("check that column/key exists");
            boolean duplicateMigration = method.startsWith("execute") && (duplicateIndex || unknownIndex);
            return duplicateMigration && (state.startsWith("42") || state.equals("23000"));
        }

        private static Object defaultResult(Class<?> type) {
            if (type == boolean.class) return false;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            return null;
        }
    }

    private static Object wrapJdbcResult(Object result, DatabaseDialect dialect) {
        if (result instanceof ResultSet resultSet) {
            return Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                    (proxy, method, args) -> {
                        try {
                            return wrapJdbcResult(method.invoke(resultSet, args), dialect);
                        } catch (InvocationTargetException e) {
                            throw sanitize(e.getCause());
                        }
                    });
        }
        if (result instanceof DatabaseMetaData metadata) {
            return Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(),
                    new Class<?>[]{DatabaseMetaData.class}, (proxy, method, args) -> {
                        try {
                            return wrapJdbcResult(method.invoke(metadata, args), dialect);
                        } catch (InvocationTargetException e) {
                            throw sanitize(e.getCause());
                        }
                    });
        }
        if (result instanceof Statement statement) {
            Class<?> primaryInterface = statement instanceof CallableStatement ? CallableStatement.class
                    : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
            return wrapStatement(statement, primaryInterface, dialect);
        }
        if (result instanceof Connection connection) {
            return wrap(connection, dialect);
        }
        return result;
    }

    private static Throwable sanitize(Throwable failure) {
        return failure instanceof SQLException sql ? DatabaseDiagnostics.redact(sql) : failure;
    }
}
