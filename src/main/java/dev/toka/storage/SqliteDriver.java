package dev.toka.storage;

import java.io.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.sql.*;
import java.util.Properties;

/** Keeps the complete JDBC driver, including JNI classes, in one loader. */
final class SqliteDriver {
    private static final Driver DRIVER = load();
    private static Driver load() {
        try {
            var resource = SqliteDriver.class.getResource("/META-INF/toka/sqlite-jdbc.jar");
            if (resource == null) {
                return (Driver) Class.forName("org.sqlite.JDBC", true,
                    SqliteDriver.class.getClassLoader()).getDeclaredConstructor().newInstance();
            }
            Path jar = Files.createTempFile("toka-sqlite-", ".jar");
            jar.toFile().deleteOnExit();
            try (InputStream input = resource.openStream()) {
                Files.copy(input, jar, StandardCopyOption.REPLACE_EXISTING);
            }
            // Platform parent exposes java.sql but cannot see core/plugin SQLite classes.
            var loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader());
            return (Driver) Class.forName("org.sqlite.JDBC", true, loader)
                .getDeclaredConstructor().newInstance();
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }
    static Connection connect(Path path) throws SQLException {
        return DRIVER.connect("jdbc:sqlite:" + path, new Properties());
    }
}
