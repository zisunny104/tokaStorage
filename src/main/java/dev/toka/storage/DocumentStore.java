package dev.toka.storage;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.*;
public interface DocumentStore extends AutoCloseable {
    static DocumentStore open(StoreConfig config) throws IOException { return Stores.open(config); }
    Optional<JsonObject> load(String key) throws IOException;
    void save(String key, JsonObject payload) throws IOException;
    void saveAll(Map<String, JsonObject> documents) throws IOException;
    void delete(String key) throws IOException;
    Set<String> keys() throws IOException;
    SortedMap<String, JsonObject> snapshot() throws IOException;
    @Override void close() throws IOException;
}
