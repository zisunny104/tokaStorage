package dev.toka.storage;
import java.nio.file.Path;
import java.util.Objects;
public record StoreConfig(Backend backend, Path directory, int schemaVersion) {
    public StoreConfig {
        Objects.requireNonNull(backend); Objects.requireNonNull(directory);
        directory = directory.toAbsolutePath().normalize();
        if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be positive");
    }
    public StoreConfig(Path directory, int schemaVersion) { this(Backend.SQLITE, directory, schemaVersion); }
}
