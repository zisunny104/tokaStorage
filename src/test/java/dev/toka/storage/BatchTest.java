package dev.toka.storage;
import org.junit.*;import static org.junit.Assert.*;import java.nio.file.*;import java.util.*;import com.google.gson.*;
public class BatchTest {
 @Test public void batchesAndInvalidBatchAllBackends()throws Exception {Path base=Path.of(".build-tools/tests");Files.createDirectories(base);for(Backend b:Backend.values()){Path path=Files.createTempDirectory(base,"batch-");var config=new StoreConfig(b,path,1);try(var store=DocumentStore.open(config)){JsonObject a=new JsonObject();a.addProperty("value",1);store.saveAll(Map.of("a",a,"b",a));assertEquals(Set.of("a","b"),store.keys());var invalid=new LinkedHashMap<String,JsonObject>();invalid.put("c",a);invalid.put("../bad",a);assertThrows(java.io.IOException.class,()->store.saveAll(invalid));assertEquals(Set.of("a","b"),store.keys());}try(var store=DocumentStore.open(config)){assertEquals(Set.of("a","b"),store.keys());}}}
}
