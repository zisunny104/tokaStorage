package dev.toka.storage;
import java.nio.file.Path;
public final class StorageCli {
    private StorageCli(){}
    public static void main(String[] args)throws Exception {
        if(args.length!=7 || !args[0].equals("migrate")) {
            System.err.println("Usage: migrate SOURCE_BACKEND SOURCE_DIRECTORY TARGET_BACKEND TARGET_DIRECTORY SCHEMA_VERSION BACKUP_DIRECTORY");System.exit(2);return;
        }
        var result=Migration.migrate(new StoreConfig(Backend.valueOf(args[1].toUpperCase(java.util.Locale.ROOT)),Path.of(args[2]),Integer.parseInt(args[5])),
            new StoreConfig(Backend.valueOf(args[3].toUpperCase(java.util.Locale.ROOT)),Path.of(args[4]),Integer.parseInt(args[5])),Path.of(args[6]));
        System.out.println("MIGRATION_PASS Documents="+result.documents()+" Backup="+result.backupFile()+" Destination="+result.destination());
    }
}
