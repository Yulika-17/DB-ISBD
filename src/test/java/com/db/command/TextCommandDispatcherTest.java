package com.db.command;

import com.db.config.CollectionConfig;
import com.db.config.DbConfig;
import com.db.exception.CollectionNotFoundException;
import com.db.exception.DimensionMismatchException;
import com.db.exception.IdNotFoundException;
import com.db.model.CollectionStats;
import com.db.model.DbStats;
import com.db.model.SearchResult;
import com.db.model.VectorEntry;
import com.db.storage.InMemoryVectorDB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextCommandDispatcherTest {

    @TempDir
    Path dir;

    private InMemoryVectorDB db;
    private TextCommandDispatcher d;

    @BeforeEach
    void setUp() {
        db = InMemoryVectorDB.open(DbConfig.builder().dataDirectory(dir.toString()).autoSave(false).build());
        d = new TextCommandDispatcher(db);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private Object run(String line) {
        return d.dispatch(Arrays.asList(line.trim().split("\\s+")));
    }

    @Test
    void fullScenario() {
        CollectionConfig cfg = (CollectionConfig) run("create_collection docs 3 euclidean 10");
        assertEquals(3, cfg.dimension());
        assertEquals(10, cfg.maxVectors());
        assertEquals("OK", run("PUT docs a 1 0 0 hello big world"));
        assertEquals("OK", run("PUT docs b 0 1 0"));
        assertEquals(2L, run("SIZE docs"));

        VectorEntry a = (VectorEntry) run("GET docs a");
        assertEquals("hello big world", new String(a.data(), StandardCharsets.UTF_8));

        @SuppressWarnings("unchecked")
        List<SearchResult> r = (List<SearchResult>) run("SEARCH docs 0.9 0.1 0 1");
        assertEquals("a", r.get(0).entry().id());

        assertEquals(true, run("EXISTS docs b"));
        assertEquals(true, run("DELETE docs b"));
        assertEquals(false, run("DELETE docs b"));
        assertEquals(List.of("docs"), run("LIST_COLLECTIONS"));
        assertTrue(run("STATS") instanceof DbStats);
        assertTrue(run("STATS docs") instanceof CollectionStats);
        assertEquals("OK", run("SAVE"));
        assertEquals("OK", run("CLEAR docs"));
        assertEquals(0L, run("SIZE docs"));
        assertEquals(true, run("DROP_COLLECTION docs"));
    }

    @Test
    void errors() {
        run("CREATE_COLLECTION docs 2");
        assertThrows(IllegalArgumentException.class, () -> run("NOPE"));
        assertThrows(IllegalArgumentException.class, () -> run("CREATE_COLLECTION x"));
        assertThrows(IllegalArgumentException.class, () -> run("CREATE_COLLECTION x two"));
        assertThrows(IllegalArgumentException.class, () -> run("CREATE_COLLECTION x 2 manhattan"));
        assertThrows(IllegalArgumentException.class, () -> run("PUT docs a 1"));
        assertThrows(IllegalArgumentException.class, () -> run("PUT docs a 1 abc"));
        assertThrows(IllegalArgumentException.class, () -> run("SEARCH docs 1 2"));
        assertThrows(IllegalArgumentException.class, () -> run("SEARCH docs 1 2 3 4"));
        assertThrows(CollectionNotFoundException.class, () -> run("SIZE nope"));
        assertThrows(IdNotFoundException.class, () -> run("GET docs missing"));
        assertThrows(IllegalArgumentException.class, () -> d.dispatch(List.of()));
        // формат команды верный, но вектор не той размерности — до коллекции он не дойдёт через PUT,
        // а напрямую коллекция бросит DimensionMismatchException
        assertThrows(DimensionMismatchException.class,
                () -> db.getCollection("docs").getNearest(new double[]{1}, 1));
    }

    @Test
    void formatterProducesReadableText() {
        run("CREATE_COLLECTION docs 2");
        run("PUT docs a 1 0 note");
        assertEquals("id=a vector=[1.0, 0.0] data=\"note\"", ResponseFormatter.format(run("GET docs a")));
        assertTrue(ResponseFormatter.format(run("SEARCH docs 1 0 1")).startsWith("1) distance=0.000000"));
        assertEquals("(empty)", ResponseFormatter.format(List.of()));
        assertEquals("docs (dimension=2, metric=COSINE, maxVectors=unlimited)",
                ResponseFormatter.format(run("DESCRIBE docs")));
    }
}
