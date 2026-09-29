package com.itemgraph.db;

import java.sql.Connection;
import java.sql.SQLException;

/** SQL family used by ItemGraph's own database. */
public enum DatabaseDialect {
    SQLITE,
    MYSQL_MARIADB;

    public static DatabaseDialect fromSettings(DatabaseSettings settings) {
        return settings.backend() == DatabaseSettings.Backend.SQLITE ? SQLITE : MYSQL_MARIADB;
    }

    public static DatabaseDialect fromConnection(Connection connection) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName();
        return product != null && product.toLowerCase(java.util.Locale.ROOT).contains("sqlite")
                ? SQLITE : MYSQL_MARIADB;
    }
}
