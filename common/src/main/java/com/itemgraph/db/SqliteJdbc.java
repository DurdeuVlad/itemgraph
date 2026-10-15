package com.itemgraph.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.ServiceLoader;

/**
 * Loads the bundled SQLite JDBC driver inside a dedicated, platform-parented
 * classloader so ItemGraph never publishes {@code org.sqlite.*} on its own
 * module or classpath. GriefLogger embeds {@code org.sqlite.*} directly in its
 * mod jar; if ItemGraph also carried the package, Java module resolution
 * (NeoForge) or flat-classloader shadowing (Fabric) would crash or corrupt the
 * pair at startup. Loading the driver privately lets both mods coexist.
 *
 * <p>Resolution order: the {@value #JAR_PATH_PROPERTY} system property, then the
 * {@code META-INF/iglibs/sqlite-jdbc-*.jar} resource bundled in the mod jar
 * (extracted to the supplied library directory), then the {@code org.sqlite.JDBC}
 * classpath fallback used by dev runs and unit tests.
 */
public final class SqliteJdbc {
    public static final String JAR_PATH_PROPERTY = "itemgraph.sqliteJdbcJar";
    private static final String INDEX_RESOURCE = "META-INF/iglibs/libs.properties";
    private static final String INDEX_KEY = "sqlite.jdbc.jar";
    private static final Logger LOGGER = LoggerFactory.getLogger(SqliteJdbc.class);

    private static volatile Driver isolatedDriver;
    private static volatile String status = "uninitialized";
    private static volatile boolean initialized;

    private SqliteJdbc() {}

    /** Idempotent; safe to call once at mod init and lazily from {@link #connect}. */
    public static synchronized void initialize(Path libraryDirectory) {
        if (initialized) {
            return;
        }
        try {
            Path jar = locateDriverJar(libraryDirectory);
            if (jar != null) {
                URLClassLoader loader = new URLClassLoader(
                        new URL[]{jar.toUri().toURL()},
                        slf4jBridgeParent());
                for (Driver candidate : ServiceLoader.load(Driver.class, loader)) {
                    isolatedDriver = candidate;
                    break;
                }
                if (isolatedDriver != null) {
                    try (Connection smoke = isolatedDriver.connect(
                            "jdbc:sqlite::memory:", new Properties())) {
                        if (smoke == null) {
                            isolatedDriver = null;
                        }
                    }
                }
                if (isolatedDriver != null) {
                    status = "isolated:" + jar.getFileName();
                    LOGGER.info("SQLite JDBC driver loaded in isolation from {}", jar);
                }
            }
        } catch (Throwable t) {
            LOGGER.error("Failed to initialize the isolated SQLite JDBC driver", t);
        }
        if (isolatedDriver == null) {
            try {
                Class.forName("org.sqlite.JDBC");
                status = "classpath";
            } catch (Throwable t) {
                status = "unavailable";
                LOGGER.error("No SQLite JDBC driver is available; sqlite-backed ItemGraph features will fail", t);
            }
        }
        initialized = true;
    }

    /**
     * Parent for the isolated driver loader: platform classes plus only
     * {@code org.slf4j.*} bridged from the mod classpath (sqlite-jdbc's static
     * initializer requires it). {@code org.sqlite.*} is deliberately not
     * exposed, so it resolves exclusively from the bundled driver jar even when
     * another mod (GriefLogger) publishes the same package.
     */
    private static ClassLoader slf4jBridgeParent() {
        return new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.startsWith("org.slf4j.")) {
                    return Class.forName(name, false, SqliteJdbc.class.getClassLoader());
                }
                return super.findClass(name);
            }

            @Override
            public URL getResource(String name) {
                if (name.startsWith("org/slf4j/")) {
                    return SqliteJdbc.class.getClassLoader().getResource(name);
                }
                return super.getResource(name);
            }
        };
    }

    private static Path locateDriverJar(Path libraryDirectory) throws IOException {
        String override = System.getProperty(JAR_PATH_PROPERTY);
        if (override != null && !override.isBlank()) {
            Path path = Path.of(override.trim());
            if (Files.isRegularFile(path)) {
                return path;
            }
            LOGGER.warn("{} points at a missing file: {}", JAR_PATH_PROPERTY, path);
        }
        String jarName = bundledJarName();
        if (jarName == null) {
            return null;
        }
        Path targetDir = libraryDirectory != null
                ? libraryDirectory
                : Files.createTempDirectory("itemgraph-sqlite");
        Path target = targetDir.resolve(jarName);
        if (!Files.isRegularFile(target)) {
            Files.createDirectories(targetDir);
            try (InputStream in = SqliteJdbc.class.getClassLoader()
                    .getResourceAsStream("META-INF/iglibs/" + jarName)) {
                if (in == null) {
                    LOGGER.warn("Bundled SQLite driver resource META-INF/iglibs/{} not found", jarName);
                    return null;
                }
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return target;
    }

    private static String bundledJarName() {
        try (InputStream in = SqliteJdbc.class.getClassLoader().getResourceAsStream(INDEX_RESOURCE)) {
            if (in == null) {
                return null;
            }
            Properties index = new Properties();
            index.load(in);
            String name = index.getProperty(INDEX_KEY);
            return name == null || name.isBlank() ? null : name.trim();
        } catch (IOException e) {
            return null;
        }
    }

    public static boolean isIsolated() {
        return isolatedDriver != null;
    }

    public static String status() {
        return status;
    }

    /**
     * Opens a SQLite JDBC connection through the isolated driver when loaded,
     * otherwise through the classpath {@link DriverManager}. The URL must carry
     * any read-only or mode parameters itself; ItemGraph never mutates a
     * GriefLogger-owned database.
     */
    public static Connection connect(String url, Properties properties) throws SQLException {
        if (!initialized) {
            initialize(null);
        }
        Driver driver = isolatedDriver;
        if (driver != null) {
            Connection connection = driver.connect(url, properties == null ? new Properties() : properties);
            if (connection == null) {
                throw new SQLException("isolated SQLite driver rejected URL " + url);
            }
            return connection;
        }
        return DriverManager.getConnection(url, properties);
    }
}
