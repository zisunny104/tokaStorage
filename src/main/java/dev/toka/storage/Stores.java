package dev.toka.storage;
import com.google.gson.*;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.nodes.*;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.DumperOptions;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.math.*;

final class Stores {
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();
    static final long LIMIT = 16L * 1024 * 1024;
    static DocumentStore open(StoreConfig config) throws IOException { return new Store(config); }
    static void atomicWrite(Path destination, byte[] bytes, boolean replace) throws IOException {
        atomicWrite(destination,bytes,replace,Files::move,Thread::sleep);
    }
    @FunctionalInterface interface MoveOperation {Path move(Path source,Path target,CopyOption... options)throws IOException;}
    @FunctionalInterface interface Pause {void sleep(long millis)throws InterruptedException;}
    static void atomicWrite(Path destination,byte[] bytes,boolean replace,MoveOperation mover,Pause pause)throws IOException {
        Path temporary = destination.resolveSibling("." + destination.getFileName() + "." + UUID.randomUUID() + ".tmp");
        IOException failure=null;
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            if (replace) moveWithSharingRetry(temporary,destination,mover,pause,StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else {
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(destination.toString());
                moveWithSharingRetry(temporary,destination,mover,pause,StandardCopyOption.ATOMIC_MOVE);
            }
        } catch(IOException error){failure=error;throw error;}
        finally {try{Files.deleteIfExists(temporary);}catch(IOException cleanup){if(failure!=null)failure.addSuppressed(cleanup);else throw cleanup;}}
    }
    private static void moveWithSharingRetry(Path source,Path destination,MoveOperation mover,Pause pause,CopyOption... options)throws IOException {
        // Windows scanners may briefly deny atomic replacement. Do not retry corruption, disk-full, or unsupported atomic moves.
        long[] delays={25,50,100,200};
        for(int attempt=0;;attempt++){
            try{mover.move(source,destination,options);return;}
            catch(AccessDeniedException denied){
                if(attempt==delays.length)throw denied;
                try{pause.sleep(delays[attempt]);}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();InterruptedIOException stopped=new InterruptedIOException("Interrupted while retrying atomic replacement");stopped.initCause(denied);throw stopped;}
            }
        }
    }
    static JsonObject envelope(int schema, SortedMap<String,JsonObject> documents) {
        JsonObject root = new JsonObject(); root.addProperty("schemaVersion", schema);
        JsonObject entries = new JsonObject(); documents.forEach((key,value)->entries.add(key,value.deepCopy()));
        root.add("documents", entries); return root;
    }
    static boolean exactEquals(JsonElement a,JsonElement b) {
        if(a==null||b==null)return a==b;
        if(a.isJsonNull()||b.isJsonNull())return a.isJsonNull()&&b.isJsonNull();
        if(a.isJsonObject()&&b.isJsonObject()){
            if(!a.getAsJsonObject().keySet().equals(b.getAsJsonObject().keySet()))return false;
            for(String key:a.getAsJsonObject().keySet())if(!exactEquals(a.getAsJsonObject().get(key),b.getAsJsonObject().get(key)))return false;return true;
        }
        if(a.isJsonArray()&&b.isJsonArray()){
            if(a.getAsJsonArray().size()!=b.getAsJsonArray().size())return false;
            for(int i=0;i<a.getAsJsonArray().size();i++)if(!exactEquals(a.getAsJsonArray().get(i),b.getAsJsonArray().get(i)))return false;return true;
        }
        if(a.isJsonPrimitive()&&b.isJsonPrimitive()){
            JsonPrimitive x=a.getAsJsonPrimitive(),y=b.getAsJsonPrimitive();
            if(x.isNumber()&&y.isNumber())return x.getAsBigDecimal().compareTo(y.getAsBigDecimal())==0;
            if(x.isBoolean()!=y.isBoolean()||x.isString()!=y.isString()||x.isNumber()!=y.isNumber())return false;
            return x.equals(y);
        }return false;
    }
    static SortedMap<String,JsonObject> decode(JsonObject root, int schema) throws IOException {
        if (!root.has("schemaVersion") || !root.get("schemaVersion").isJsonPrimitive() || !root.get("schemaVersion").getAsJsonPrimitive().isNumber()) throw new IOException("Invalid schemaVersion");
        try {if(root.get("schemaVersion").getAsBigDecimal().intValueExact()!=schema)throw new IOException("Unsupported schemaVersion");}
        catch(ArithmeticException error){throw new IOException("Invalid schemaVersion",error);}
        if (!root.has("documents") || !root.get("documents").isJsonObject()) throw new IOException("Invalid documents object");
        SortedMap<String,JsonObject> result=new TreeMap<>();
        for (var entry:root.getAsJsonObject("documents").entrySet()) {
            checkKey(entry.getKey()); if (!entry.getValue().isJsonObject()) throw new IOException("Document must be an object");
            result.put(entry.getKey(),entry.getValue().getAsJsonObject().deepCopy());
        } return result;
    }
    static void checkKey(String key) throws IOException {
        if (key==null || !key.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) throw new IOException("Invalid document key");
    }
    static Object yamlValue(JsonElement element) {
        if(element.isJsonNull())return null;
        if(element.isJsonObject()){Map<String,Object> map=new LinkedHashMap<>();element.getAsJsonObject().entrySet().forEach(e->map.put(e.getKey(),yamlValue(e.getValue())));return map;}
        if(element.isJsonArray()){List<Object> list=new ArrayList<>();element.getAsJsonArray().forEach(e->list.add(yamlValue(e)));return list;}
        JsonPrimitive value=element.getAsJsonPrimitive();if(value.isBoolean())return value.getAsBoolean();if(value.isString())return value.getAsString();
        String number=value.getAsString();return number.matches("-?[0-9]+")?new BigInteger(number):new BigDecimal(number);
    }
    static final class DecimalConstructor extends SafeConstructor {
        DecimalConstructor(LoaderOptions options){super(options);yamlConstructors.put(Tag.FLOAT,new AbstractConstruct(){public Object construct(Node node){return new BigDecimal(((ScalarNode)node).getValue().replace("_",""));}});}
    }
    static final class DecimalRepresenter extends Representer {
        DecimalRepresenter(DumperOptions options){super(options);representers.put(BigDecimal.class,data->representScalar(Tag.FLOAT,((BigDecimal)data).toPlainString()));}
    }
    private static final class Store implements DocumentStore {
        private final StoreConfig config;
        private final FileChannel lockChannel;
        private final FileLock lock;
        private final Path data;
        private Connection connection;
        private boolean closed;
        Store(StoreConfig config) throws IOException {
            this.config=config;
            if (Files.isSymbolicLink(config.directory())) throw new IOException("Store directory must not be a symbolic link");
            Files.createDirectories(config.directory());
            String expected=switch(config.backend()){case SQLITE->"documents.sqlite";case JSON->"documents.json";case YAML->"documents.yml";};
            for(String name:List.of("documents.sqlite","documents.json","documents.yml"))if(!name.equals(expected)&&Files.exists(config.directory().resolve(name),LinkOption.NOFOLLOW_LINKS))throw new IOException("Different backend already exists; explicit migration required");
            Path lockPath=config.directory().resolve(".store.lock");
            if(Files.isSymbolicLink(lockPath))throw new IOException("Symbolic lock file");
            lockChannel=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            FileLock acquired=null;
            try { acquired=lockChannel.tryLock(); } catch(OverlappingFileLockException ignored) { }
            if(acquired==null){lockChannel.close();throw new IOException("Store already open");}
            lock=acquired;
            data=config.directory().resolve(switch(config.backend()){case SQLITE->"documents.sqlite";case JSON->"documents.json";case YAML->"documents.yml";});
            try {
                if(Files.isSymbolicLink(data))throw new IOException("Symbolic data file");
                if(config.backend()==Backend.SQLITE) {


                    // An empty file is what an interrupted first open leaves behind; treat it as a new store.
                    boolean existing=Files.exists(data)&&Files.size(data)>0;
                    // DriverManager filters registered drivers by caller classloader; plugin loaders can differ.
                    connection=SqliteDriver.connect(data);
                    if(existing){
                        boolean initialized;
                        try(Statement sql=connection.createStatement();ResultSet rs=sql.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table'")){initialized=rs.next()&&rs.getInt(1)>0;}
                        if(initialized){
                            try(Statement sql=connection.createStatement();ResultSet rs=sql.executeQuery("SELECT schema_version FROM metadata WHERE id=1")){if(!rs.next()||new BigDecimal(rs.getString(1)).intValueExact()!=config.schemaVersion())throw new IOException("Unsupported schemaVersion");}
                        }
                    }
                    try(Statement sql=connection.createStatement()) {
                        sql.execute("PRAGMA journal_mode=DELETE");sql.execute("PRAGMA synchronous=FULL");sql.execute("PRAGMA busy_timeout=5000");
                        sql.execute("CREATE TABLE IF NOT EXISTS metadata (id INTEGER PRIMARY KEY CHECK(id=1), schema_version INTEGER NOT NULL)");
                        sql.execute("CREATE TABLE IF NOT EXISTS documents (key TEXT PRIMARY KEY, payload TEXT NOT NULL)");
                    }
                    try(PreparedStatement statement=connection.prepareStatement("INSERT OR IGNORE INTO metadata(id,schema_version) VALUES(1,?)")){statement.setInt(1,config.schemaVersion());statement.executeUpdate();}
                    try(Statement statement=connection.createStatement();ResultSet rs=statement.executeQuery("SELECT schema_version FROM metadata WHERE id=1")){if(!rs.next()||rs.getInt(1)!=config.schemaVersion())throw new IOException("Unsupported schemaVersion");}
                } else if(Files.exists(data)) snapshot();
            } catch(SQLException|RuntimeException|IOException error) { try{close();}catch(IOException cleanup){error.addSuppressed(cleanup);} throw new IOException("Cannot open store",error); }
        }
        private void ready() throws IOException {if(closed)throw new IOException("Store closed");}
        public synchronized Optional<JsonObject> load(String key) throws IOException {
            ready();checkKey(key);if(connection==null)return Optional.ofNullable(snapshot().get(key));
            try(PreparedStatement sql=connection.prepareStatement("SELECT payload FROM documents WHERE key=?")){sql.setString(1,key);try(ResultSet rs=sql.executeQuery()){return rs.next()?Optional.of(JsonParser.parseString(rs.getString(1)).getAsJsonObject()):Optional.empty();}}
            catch(SQLException|RuntimeException error){throw new IOException("Cannot read document",error);}
        }
        public synchronized Set<String> keys() throws IOException {
            ready();if(connection==null)return Collections.unmodifiableSet(new TreeSet<>(snapshot().keySet()));
            Set<String> keys=new TreeSet<>();try(Statement sql=connection.createStatement();ResultSet rs=sql.executeQuery("SELECT key FROM documents ORDER BY key")){while(rs.next()){checkKey(rs.getString(1));keys.add(rs.getString(1));}return Collections.unmodifiableSet(keys);}catch(SQLException error){throw new IOException("Cannot read keys",error);}
        }
        public synchronized SortedMap<String,JsonObject> snapshot() throws IOException {
            ready(); SortedMap<String,JsonObject> result=new TreeMap<>();
            try {
                if(connection!=null) {
                    try(Statement statement=connection.createStatement();ResultSet rs=statement.executeQuery("SELECT key,payload FROM documents ORDER BY key")){
                        while(rs.next()){checkKey(rs.getString(1));result.put(rs.getString(1),JsonParser.parseString(rs.getString(2)).getAsJsonObject());}
                    } return result;
                }
                if(!Files.exists(data))return result;
                if(Files.size(data)>LIMIT)throw new IOException("File store exceeds 16 MiB limit");
                String text=Files.readString(data,StandardCharsets.UTF_8); JsonObject root;
                if(config.backend()==Backend.JSON) root=JsonParser.parseString(text).getAsJsonObject();
                else {
                    LoaderOptions options=new LoaderOptions();options.setAllowDuplicateKeys(false);options.setMaxAliasesForCollections(0);options.setCodePointLimit((int)LIMIT);
                    Object parsed=new Yaml(new DecimalConstructor(options)).load(text);
                    root=GSON.toJsonTree(parsed).getAsJsonObject();
                } return decode(root,config.schemaVersion());
            }catch(SQLException|RuntimeException error){throw new IOException("Cannot read documents",error);}
        }
        public synchronized void save(String key,JsonObject payload) throws IOException {
            ready();checkKey(key);Objects.requireNonNull(payload);
            if(connection!=null){
                try(PreparedStatement sql=connection.prepareStatement("INSERT INTO documents(key,payload) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET payload=excluded.payload")){
                    sql.setString(1,key);sql.setString(2,GSON.toJson(payload));sql.executeUpdate();
                }catch(SQLException error){throw new IOException("Cannot save document",error);}
            }else{SortedMap<String,JsonObject> documents=snapshot();documents.put(key,payload.deepCopy());write(documents);}
        }
        public synchronized void saveAll(Map<String,JsonObject> values) throws IOException {
            ready();SortedMap<String,JsonObject> verified=new TreeMap<>();
            for(var row:values.entrySet()){checkKey(row.getKey());verified.put(row.getKey(),Objects.requireNonNull(row.getValue()).deepCopy());}
            if(connection==null){var documents=snapshot();documents.putAll(verified);write(documents);return;}
            var serialized=new TreeMap<String,String>();verified.forEach((key,value)->serialized.put(key,GSON.toJson(value)));
            boolean transactionClean=false;
            try {
                connection.setAutoCommit(false);
                try(PreparedStatement sql=connection.prepareStatement("INSERT INTO documents(key,payload) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET payload=excluded.payload")){
                    for(var row:serialized.entrySet()){sql.setString(1,row.getKey());sql.setString(2,row.getValue());sql.addBatch();}
                    sql.executeBatch();connection.commit();transactionClean=true;
                } catch(SQLException|RuntimeException error){try{connection.rollback();transactionClean=true;}catch(SQLException rollback){error.addSuppressed(rollback);try{connection.close();}catch(SQLException close){error.addSuppressed(close);}}throw error;}
            } catch(SQLException|RuntimeException error){throw new IOException("Cannot save batch",error);}
            finally {if(transactionClean)try{connection.setAutoCommit(true);}catch(SQLException error){throw new IOException("Cannot finish batch",error);}}
        }
        public synchronized void delete(String key) throws IOException {
            ready();checkKey(key);
            if(connection!=null){try(PreparedStatement sql=connection.prepareStatement("DELETE FROM documents WHERE key=?")){sql.setString(1,key);sql.executeUpdate();}catch(SQLException error){throw new IOException("Cannot delete document",error);}}
            else{SortedMap<String,JsonObject> documents=snapshot();documents.remove(key);write(documents);}
        }
        private void write(SortedMap<String,JsonObject> documents)throws IOException {
            JsonObject root=envelope(config.schemaVersion(),documents);String text;
            if(config.backend()==Backend.JSON)text=GSON.toJson(root);
            else {DumperOptions options=new DumperOptions();text=new Yaml(new DecimalRepresenter(options),options).dump(yamlValue(root));}
            byte[] bytes=text.getBytes(StandardCharsets.UTF_8);if(bytes.length>LIMIT)throw new IOException("File store exceeds 16 MiB limit");
            atomicWrite(data,bytes,true);
        }
        public synchronized void close() throws IOException {
            if(closed)return;closed=true;IOException failure=null;
            if(connection!=null)try{connection.close();}catch(SQLException error){failure=new IOException(error);}
            try{lock.release();}catch(IOException error){failure=error;}finally{lockChannel.close();}
            if(failure!=null)throw failure;
        }
    }
}

