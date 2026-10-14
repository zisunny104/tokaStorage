package dev.toka.storage;
import org.junit.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class AtomicReplaceTest {
    private Path root,target;
    @Before public void setup()throws Exception {Path parent=Path.of(".build-tools/tests");Files.createDirectories(parent);root=Files.createTempDirectory(parent,"atomic-");target=root.resolve("documents.json");Files.writeString(target,"old");}
    private byte[] updated(){return "new".getBytes(StandardCharsets.UTF_8);}
    private void assertOldAndNoTemporary()throws Exception {assertEquals("old",Files.readString(target));try(var paths=Files.list(root)){assertEquals(1,paths.count());}}
    @Test public void transientSharingFailureEventuallyReplacesAtomically()throws Exception {
        AtomicInteger attempts=new AtomicInteger(),waits=new AtomicInteger();
        Stores.atomicWrite(target,updated(),true,(source,destination,options)->{
            assertTrue(java.util.Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE));
            if(attempts.incrementAndGet()<3)throw new AccessDeniedException(source.toString(),destination.toString(),"sharing violation");
            return Files.move(source,destination,options);
        },millis->waits.incrementAndGet());
        assertEquals(3,attempts.get());assertEquals(2,waits.get());assertEquals("new",Files.readString(target));
    }
    @Test public void persistentAccessDeniedKeepsOldFileAndStops()throws Exception {
        AtomicInteger attempts=new AtomicInteger(),waits=new AtomicInteger();
        assertThrows(AccessDeniedException.class,()->Stores.atomicWrite(target,updated(),true,(source,destination,options)->{attempts.incrementAndGet();throw new AccessDeniedException(source.toString());},millis->waits.incrementAndGet()));
        assertEquals(5,attempts.get());assertEquals(4,waits.get());assertOldAndNoTemporary();
    }
    @Test public void unrelatedIoErrorIsNotRetriedOrDowngraded()throws Exception {
        AtomicInteger attempts=new AtomicInteger(),waits=new AtomicInteger();
        assertThrows(AtomicMoveNotSupportedException.class,()->Stores.atomicWrite(target,updated(),true,(source,destination,options)->{attempts.incrementAndGet();throw new AtomicMoveNotSupportedException(source.toString(),destination.toString(),"unsupported");},millis->waits.incrementAndGet()));
        assertEquals(1,attempts.get());assertEquals(0,waits.get());assertOldAndNoTemporary();
    }
    @Test public void interruptionStopsAndRestoresInterruptFlag()throws Exception {
        try {
            assertThrows(InterruptedIOException.class,()->Stores.atomicWrite(target,updated(),true,(source,destination,options)->{throw new AccessDeniedException(source.toString());},millis->{throw new InterruptedException("cancel");}));
            assertTrue(Thread.currentThread().isInterrupted());assertOldAndNoTemporary();
        }finally{Thread.interrupted();}
    }
}
