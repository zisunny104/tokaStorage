package dev.toka.storage;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class Migration {
    private Migration(){}
    public record Result(Path backupFile,Path destination,int documents){}
    public static Result migrate(StoreConfig source,StoreConfig target,Path backupDirectory)throws IOException {
        if(!Files.isDirectory(source.directory()))throw new IOException("Source does not exist");
        String sourceFile=Stores.fileName(source.backend());
        Path sourceData=source.directory().resolve(sourceFile);
        if(!Files.isRegularFile(sourceData)||Files.size(sourceData)==0)throw new IOException("Source data file does not exist or is empty");
        if(source.schemaVersion()!=target.schemaVersion())throw new IOException("Schema upgrade requires explicit adapter");
        if(Files.exists(target.directory(),LinkOption.NOFOLLOW_LINKS))throw new FileAlreadyExistsException(target.directory().toString());
        if(source.directory().equals(target.directory())||target.directory().startsWith(source.directory()))throw new IOException("Target must be independent of source");
        Files.createDirectories(target.directory().getParent());
        Path staging=target.directory().resolveSibling("."+target.directory().getFileName()+".migration-"+UUID.randomUUID());
        Path backupRoot=backupDirectory.toAbsolutePath().normalize();
        if(backupRoot.startsWith(source.directory())||backupRoot.startsWith(target.directory()))throw new IOException("Backup must be independent");
        Files.createDirectories(backupRoot);Path backup=backupRoot.resolve("snapshot-"+UUID.randomUUID()+".json");
        try(DocumentStore original=DocumentStore.open(source)) {
            SortedMap<String,JsonObject> expected=original.snapshot();
            if(expected.isEmpty())throw new IOException("Source contains no documents");
            Stores.atomicWrite(backup,Stores.GSON.toJson(Stores.envelope(source.schemaVersion(),expected)).getBytes(StandardCharsets.UTF_8),false);
            SortedMap<String,JsonObject> saved=Stores.decode(com.google.gson.JsonParser.parseString(Files.readString(backup)).getAsJsonObject(),source.schemaVersion());
            if(!Stores.exactEquals(Stores.envelope(source.schemaVersion(),saved),Stores.envelope(source.schemaVersion(),expected)))throw new IOException("Backup verification failed");
            try(DocumentStore migrated=DocumentStore.open(new StoreConfig(target.backend(),staging,target.schemaVersion()))) {
                migrated.saveAll(expected);
                if(!Stores.exactEquals(Stores.envelope(target.schemaVersion(),migrated.snapshot()),Stores.envelope(source.schemaVersion(),expected)))throw new IOException("Destination verification failed");
            }
            try(DocumentStore reopened=DocumentStore.open(new StoreConfig(target.backend(),staging,target.schemaVersion()))) {
                if(!Stores.exactEquals(Stores.envelope(target.schemaVersion(),reopened.snapshot()),Stores.envelope(source.schemaVersion(),expected)))throw new IOException("Destination reopen verification failed");
            }
            if(Files.exists(target.directory(),LinkOption.NOFOLLOW_LINKS))throw new FileAlreadyExistsException(target.directory().toString());
            Files.move(staging,target.directory()); // Default move refuses replacement; ATOMIC_MOVE permits provider-specific replacement.
            return new Result(backup,target.directory(),expected.size());
        }catch(IOException|RuntimeException error){
            if(Files.isDirectory(staging))Files.writeString(staging.resolve("MIGRATION_FAILED.txt"),"Source retained; staging must not be activated.\n");
            throw error;
        }
    }
}
