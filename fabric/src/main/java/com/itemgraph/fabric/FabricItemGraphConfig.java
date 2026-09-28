package com.itemgraph.fabric;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Simple editable properties file; Fabric does not provide a server config system. */
record FabricItemGraphConfig(Path databasePath, Path griefLoggerDatabasePath, int groundBridgeMaxSeconds) {
    static FabricItemGraphConfig load(Path gameDirectory, Path configDirectory) throws IOException {
        Path configFile = configDirectory.resolve("itemgraph.properties");
        Properties properties = new Properties();
        if (Files.exists(configFile)) {
            try (InputStream input = Files.newInputStream(configFile)) {
                properties.load(input);
            }
        } else {
            Files.createDirectories(configDirectory);
            properties.setProperty("database_path", "itemgraph/itemgraph.db");
            properties.setProperty("grieflogger_database_path", "database.db");
            properties.setProperty("ground_bridge_max_seconds", "300");
            try (OutputStream output = Files.newOutputStream(configFile)) {
                properties.store(output, "ItemGraph server configuration");
            }
        }

        int groundBridgeMaxSeconds;
        try {
            groundBridgeMaxSeconds = Integer.parseInt(properties.getProperty("ground_bridge_max_seconds", "300"));
        } catch (NumberFormatException e) {
            throw new IOException("Invalid integer in " + configFile + ": ground_bridge_max_seconds", e);
        }
        if (groundBridgeMaxSeconds < 1 || groundBridgeMaxSeconds > 86_400) {
            throw new IOException("ground_bridge_max_seconds must be in [1, 86400] in " + configFile);
        }

        return new FabricItemGraphConfig(
                resolve(gameDirectory, properties.getProperty("database_path", "itemgraph/itemgraph.db")),
                resolve(gameDirectory, properties.getProperty("grieflogger_database_path", "database.db")),
                groundBridgeMaxSeconds);
    }

    private static Path resolve(Path gameDirectory, String value) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException("ItemGraph database paths must not be blank");
        }
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : gameDirectory.resolve(path)).normalize();
    }
}
