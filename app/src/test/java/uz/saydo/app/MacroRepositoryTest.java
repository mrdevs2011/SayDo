package uz.saydo.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import uz.saydo.app.data.Gesture;
import uz.saydo.app.data.Macro;
import uz.saydo.app.data.MacroRepository;
import uz.saydo.app.data.TouchPoint;
import uz.saydo.app.mock.TestContext;

import static org.junit.Assert.*;

/**
 * Exhaustive Unit Test Suite for MacroRepository.
 * Tests atomic file writing, corruption recovery from .bak,
 * legacy schema migration, and high-concurrency multi-threaded access.
 */
public class MacroRepositoryTest {

    private File testDir;
    private Context mockContext;

    @Before
    public void setUp() {
        testDir = new File("/tmp/saydo_repo_test_" + System.currentTimeMillis());
        //noinspection ResultOfMethodCallIgnored
        testDir.mkdirs();

        mockContext = new TestContext(testDir);
        resetSingleton();
    }

    @After
    public void tearDown() {
        resetSingleton();
        deleteRecursive(testDir);
    }

    private void resetSingleton() {
        try {
            Field instanceField = MacroRepository.class.getDeclaredField("sInstance");
            instanceField.setAccessible(true);
            instanceField.set(null, null);
        } catch (Throwable ignored) {
        }
    }

    private MacroRepository createNewInstance() throws Exception {
        Constructor<MacroRepository> ctor = MacroRepository.class.getDeclaredConstructor(Context.class);
        ctor.setAccessible(true);
        return ctor.newInstance(mockContext);
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    @Test
    public void testSaveAndRetrieveMacro() throws Exception {
        MacroRepository repo = createNewInstance();
        assertEquals(0, repo.count());

        List<TouchPoint> points = Arrays.asList(
                new TouchPoint(0.1, 0.2, 0),
                new TouchPoint(0.5, 0.6, 200)
        );
        Gesture g = Gesture.createSwipe(points, 0, 200);
        Macro m = new Macro("test-uuid-1", "open camera", Collections.singletonList(g), false, 1000L, 200L);

        repo.save(m);
        assertEquals(1, repo.count());

        Macro loaded = repo.getById("test-uuid-1");
        assertNotNull(loaded);
        assertEquals("open camera", loaded.trigger);
        assertEquals(1, loaded.gestures.size());

        Macro byTrigger = repo.getByTrigger("Open Camera");
        assertNotNull(byTrigger);
        assertEquals("test-uuid-1", byTrigger.id);
    }

    @Test
    public void testAtomicPersistenceAndReload() throws Exception {
        MacroRepository repo1 = createNewInstance();
        Macro m1 = new Macro("m-1", "shortcut one", Collections.emptyList(), false, 1000L, 0);
        Macro m2 = new Macro("m-2", "shortcut two", Collections.emptyList(), true, 2000L, 0);

        repo1.save(m1);
        repo1.save(m2);
        assertEquals(2, repo1.count());

        // Create fresh repository instance pointing to the same disk files
        MacroRepository repo2 = createNewInstance();
        assertEquals(2, repo2.count());
        assertNotNull(repo2.getById("m-1"));
        assertNotNull(repo2.getById("m-2"));
        assertTrue(repo2.getById("m-2").locked);
    }

    @Test
    public void testCorruptionRecoveryFromBackup() throws Exception {
        MacroRepository repo = createNewInstance();
        Macro m = new Macro("m-safe", "restore me", Collections.emptyList(), false, 1000L, 0);
        repo.save(m);

        File primary = new File(testDir, "macros_v2.json");
        File backup = new File(testDir, "macros_v2.json.bak");

        // Force a backup by saving another macro
        Macro m2 = new Macro("m-safe-2", "backup target", Collections.emptyList(), false, 2000L, 0);
        repo.save(m2);

        assertTrue("Backup file should exist after multiple saves", backup.exists());

        // Corrupt primary file with broken JSON syntax
        try (FileOutputStream fos = new FileOutputStream(primary)) {
            fos.write("CORRUPTED_GARBAGE_{{{[".getBytes(StandardCharsets.UTF_8));
            fos.flush();
        }

        // Recreate repository -> should automatically restore from backup
        MacroRepository restoredRepo = createNewInstance();
        assertTrue("Restored repository should contain data from backup", restoredRepo.count() > 0);
        assertNotNull(restoredRepo.getById("m-safe"));
    }

    @Test
    public void testLegacyMigration() throws Exception {
        // Populate legacy SharedPreferences format
        SharedPreferences sp = mockContext.getSharedPreferences("saydo", Context.MODE_PRIVATE);
        String legacyJson = "[{\"trigger\":\"legacy shortcut\",\"locked\":true,\"dur\":1500,\"gestures\":[]}]";
        sp.edit().putString("macros", legacyJson).commit();

        MacroRepository repo = createNewInstance();
        assertEquals(1, repo.count());

        Macro migrated = repo.getByTrigger("legacy shortcut");
        assertNotNull("Legacy shortcut must be migrated", migrated);
        assertTrue(migrated.locked);
        assertEquals(1500, migrated.duration);
        assertNotNull("Migrated macro must receive an ID", migrated.id);
    }

    @Test
    public void testDeleteMacro() throws Exception {
        MacroRepository repo = createNewInstance();
        Macro m = new Macro("del-id", "delete me", Collections.emptyList(), false, 1000L, 0);
        repo.save(m);
        assertEquals(1, repo.count());

        boolean deleted = repo.delete("del-id");
        assertTrue(deleted);
        assertEquals(0, repo.count());
        assertNull(repo.getById("del-id"));
    }

    @Test
    public void testReorderMacros() throws Exception {
        MacroRepository repo = createNewInstance();
        repo.save(new Macro("1", "first", Collections.emptyList(), false, 100L, 0));
        repo.save(new Macro("2", "second", Collections.emptyList(), false, 200L, 0));
        repo.save(new Macro("3", "third", Collections.emptyList(), false, 300L, 0));

        repo.reorder(Arrays.asList("3", "1", "2"));
        List<Macro> reordered = repo.getAll();
        assertEquals(3, reordered.size());
        assertEquals("3", reordered.get(0).id);
        assertEquals("1", reordered.get(1).id);
        assertEquals("2", reordered.get(2).id);
    }

    @Test
    public void testMultiThreadedConcurrency() throws Exception {
        final MacroRepository repo = createNewInstance();
        int threadCount = 8;
        int operationsPerThread = 25;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        final AtomicInteger successCount = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        String id = "m-" + threadId + "-" + i;
                        Macro m = new Macro(id, "trigger " + threadId + " " + i, Collections.emptyList(), false, System.currentTimeMillis(), 0);
                        repo.save(m);
                        assertNotNull(repo.getById(id));
                        repo.getAll();
                        if (i % 5 == 0) {
                            repo.delete(id);
                        }
                    }
                    successCount.incrementAndGet();
                } catch (Throwable e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
        assertEquals(threadCount, successCount.get());
        assertTrue("Repository must survive high concurrency", repo.count() > 0);
    }
}
