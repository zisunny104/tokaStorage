package dev.toka.storage;
import com.google.gson.*;
import org.junit.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class DocumentStoreTest {
    private Path root;
    @Before public void setup()throws Exception {Path parent=Path.of(".build-tools/tests");Files.createDirectories(parent);root=Files.createTempDirectory(parent,"store-").toAbsolutePath();}
    private JsonObject document(){return JsonParser.parseString("{\"name\":\"幻月服\",\"null\":null,\"array\":[1,true,\"葉子\"],\"amount\":9223372036854775806,\"huge\":123456789012345678901234567890,\"decimal\":0.12345678901234567890123456789,\"nested\":{\"enabled\":false}}").getAsJsonObject();}
    @Test public void durableRoundTripAllBackends()throws Exception {
        for(Backend backend:Backend.values()) {
            StoreConfig config=new StoreConfig(backend,root.resolve(backend.name()),1);
            try(DocumentStore store=DocumentStore.open(config)){
                JsonObject input=document();store.save("state",input);input.addProperty("name","mutated");
                assertEquals(document(),store.load("state").orElseThrow());
                JsonObject copy=store.load("state").orElseThrow();copy.addProperty("name","changed");assertEquals(document(),store.load("state").orElseThrow());
                store.save("delete_me",new JsonObject());store.delete("delete_me");assertEquals(Set.of("state"),store.keys());
            }
            try(DocumentStore reopened=DocumentStore.open(config)){assertEquals(document(),reopened.load("state").orElseThrow());}
        }
    }
    @Test public void migrationAllNinePairsPreservesSourceAndBackup()throws Exception {
        for(Backend from:Backend.values())for(Backend to:Backend.values()){
            Path dir=root.resolve(from+"-"+to);StoreConfig source=new StoreConfig(from,dir.resolve("source"),1);
            try(DocumentStore store=DocumentStore.open(source)){store.save("state",document());store.save("journal_1",new JsonObject());}
            var migrated=Migration.migrate(source,new StoreConfig(to,dir.resolve("target"),1),dir.resolve("backups"));
            assertEquals(2,migrated.documents());assertTrue(Files.isRegularFile(migrated.backupFile()));
            JsonObject backup=JsonParser.parseString(Files.readString(migrated.backupFile())).getAsJsonObject();assertEquals(document(),backup.getAsJsonObject("documents").getAsJsonObject("state"));
            try(DocumentStore a=DocumentStore.open(source);DocumentStore b=DocumentStore.open(new StoreConfig(to,migrated.destination(),1))){assertEquals(a.snapshot(),b.snapshot());}
            try(DocumentStore b=DocumentStore.open(new StoreConfig(to,migrated.destination(),1))){assertTrue(Stores.exactEquals(document(),b.load("state").orElseThrow()));}
        }
    }
    @Test public void destinationIsNeverOverwritten()throws Exception {
        StoreConfig source=new StoreConfig(root.resolve("source"),1);try(DocumentStore store=DocumentStore.open(source)){store.save("state",document());}
        Path target=root.resolve("target");Files.createDirectory(target);Files.writeString(target.resolve("sentinel"),"keep");
        assertThrows(FileAlreadyExistsException.class,()->Migration.migrate(source,new StoreConfig(Backend.JSON,target,1),root.resolve("backup")));
        assertEquals("keep",Files.readString(target.resolve("sentinel")));try(DocumentStore store=DocumentStore.open(source)){assertEquals(document(),store.load("state").orElseThrow());}
    }
    @Test public void targetWriteFailurePreservesSourceAndBackup()throws Exception {
        StoreConfig source=new StoreConfig(root.resolve("source"),1);JsonObject big=new JsonObject();big.addProperty("huge","x".repeat(17*1024*1024));
        try(DocumentStore store=DocumentStore.open(source)){store.save("state",big);}
        Path target=root.resolve("target"),backups=root.resolve("backup");
        assertThrows(IOException.class,()->Migration.migrate(source,new StoreConfig(Backend.JSON,target,1),backups));assertFalse(Files.exists(target));
        try(DocumentStore store=DocumentStore.open(source)){assertEquals(big,store.load("state").orElseThrow());}
        try(var entries=Files.list(backups)){assertEquals(1,entries.count());}
        try(var entries=Files.list(root)){assertTrue(entries.anyMatch(path->Files.exists(path.resolve("MIGRATION_FAILED.txt"))));}
    }
    @Test public void lockingSchemaAndKeyValidation()throws Exception {
        StoreConfig config=new StoreConfig(root.resolve("data"),2);
        try(DocumentStore store=DocumentStore.open(config)){
            assertThrows(IOException.class,()->DocumentStore.open(config));
            assertThrows(IOException.class,()->store.save("../escape",document()));store.save("plans_abc-123",document());
        }
        assertThrows(IOException.class,()->DocumentStore.open(new StoreConfig(config.directory(),1)));
        try(DocumentStore reopened=DocumentStore.open(config)){assertEquals(document(),reopened.load("plans_abc-123").orElseThrow());}
    }
    @Test public void unsafeYamlAndInvalidJsonRemainUntouched()throws Exception {
        Path yaml=root.resolve("yaml");Files.createDirectories(yaml);String unsafe="!!java.net.URL [http://example.com]\n";Files.writeString(yaml.resolve("documents.yml"),unsafe);
        assertThrows(IOException.class,()->DocumentStore.open(new StoreConfig(Backend.YAML,yaml,1)));assertEquals(unsafe,Files.readString(yaml.resolve("documents.yml")));
        Path json=root.resolve("json");Files.createDirectories(json);Files.writeString(json.resolve("documents.json"),"broken");
        assertThrows(IOException.class,()->DocumentStore.open(new StoreConfig(Backend.JSON,json,1)));assertEquals("broken",Files.readString(json.resolve("documents.json")));
    }
    @Test public void exactNumbersSurviveAllFormats()throws Exception {
        JsonObject precise=JsonParser.parseString("{\"huge\":123456789012345678901234567890,\"decimal\":0.123456789012345678901234567890,\"exponent\":1.234567890123456789e-50}").getAsJsonObject();
        for(Backend backend:Backend.values()){
            StoreConfig config=new StoreConfig(backend,root.resolve(backend+"-precise"),1);try(DocumentStore store=DocumentStore.open(config)){store.save("numbers",precise);}
            try(DocumentStore store=DocumentStore.open(config)){JsonObject actual=store.load("numbers").orElseThrow();for(String key:List.of("huge","decimal","exponent"))assertEquals(0,precise.get(key).getAsBigDecimal().compareTo(actual.get(key).getAsBigDecimal()));}
        }
        JsonObject changed=precise.deepCopy();changed.addProperty("huge",new java.math.BigInteger("123456789012345678901234567891"));assertFalse(Stores.exactEquals(precise,changed));
    }
    @Test public void wrongBackendAndFractionalVersionAreRejected()throws Exception {
        Path directory=root.resolve("json");StoreConfig config=new StoreConfig(Backend.JSON,directory,1);try(DocumentStore store=DocumentStore.open(config)){store.save("state",document());}
        assertThrows(IOException.class,()->DocumentStore.open(new StoreConfig(Backend.SQLITE,directory,1)));assertFalse(Files.exists(directory.resolve("documents.sqlite")));
        Files.writeString(directory.resolve("documents.json"),"{\"schemaVersion\":1.5,\"documents\":{}}");assertThrows(IOException.class,()->DocumentStore.open(config));
        assertThrows(IOException.class,()->Migration.migrate(new StoreConfig(Backend.SQLITE,directory,1),new StoreConfig(root.resolve("target"),1),root.resolve("backup")));assertFalse(Files.exists(directory.resolve("documents.sqlite")));
    }
    @Test public void foreignSqliteDatabaseIsNotInitialized()throws Exception {
        Path dir=root.resolve("foreign");Files.createDirectories(dir);Path database=dir.resolve("documents.sqlite");
        try(java.sql.Connection conn=java.sql.DriverManager.getConnection("jdbc:sqlite:"+database);java.sql.Statement sql=conn.createStatement()){sql.execute("CREATE TABLE existing(data TEXT)");sql.execute("INSERT INTO existing VALUES('keep')");}
        byte[] before=Files.readAllBytes(database);assertThrows(IOException.class,()->DocumentStore.open(new StoreConfig(dir,1)));assertArrayEquals(before,Files.readAllBytes(database));
        try(java.sql.Connection conn=java.sql.DriverManager.getConnection("jdbc:sqlite:"+database);java.sql.Statement sql=conn.createStatement();java.sql.ResultSet rs=sql.executeQuery("SELECT count(*) FROM sqlite_master WHERE name='metadata' OR name='documents'")){assertTrue(rs.next());assertEquals(0,rs.getInt(1));}
    }
}
