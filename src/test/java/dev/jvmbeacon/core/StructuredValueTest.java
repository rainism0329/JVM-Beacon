package dev.jvmbeacon.core;

import dev.jvmbeacon.fixture.DemoApplication;
import org.junit.jupiter.api.Test;
import javax.management.openmbean.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class StructuredValueTest {
    @Test void openDataPreservesTypesIndicesAndExactValuesWithoutRetainingObjects() {
        var probe = new DemoApplication.Probe();
        var summary = StructuredValue.capture("Summary", probe.getSummary());
        assertFalse(summary.incomplete()); assertEquals(4, summary.nodeCount());
        assertEquals("Summary", summary.root().type());
        assertEquals("7", child(summary.root(), "counter").value());
        assertEquals("java.lang.Integer", child(summary.root(), "counter").type());
        var rows = StructuredValue.capture("Rows", probe.getRows());
        assertEquals(StructuredValue.Kind.TABLE, rows.root().kind());
        assertTrue(rows.root().value().contains("Index: name"));
        assertEquals(Set.of("current", "next"), new HashSet<>(rows.root().children().stream().map(row -> child(row, "name").value()).toList()));
        probe.setCounter(100);
        assertEquals("7", child(summary.root(), "counter").value(), "Captured values must remain detached from target state");
        assertThrows(UnsupportedOperationException.class, () -> summary.root().children().clear());
    }

    @Test void nullEmptyZeroUnsupportedAndErrorsAreDistinct() throws Exception {
        assertEquals(StructuredValue.Kind.NULL, StructuredValue.capture("x", null).root().kind());
        assertEquals("", StructuredValue.capture("x", "").root().value());
        assertEquals("0", StructuredValue.capture("x", 0).root().value());
        assertEquals(StructuredValue.Kind.ARRAY, StructuredValue.capture("x", new int[0]).root().kind());
        Object hostile = new Object() { @Override public String toString() { fail("Must not call arbitrary toString"); return ""; } };
        assertEquals(StructuredValue.Kind.UNSUPPORTED, StructuredValue.capture("x", hostile).root().kind());
        CompositeType type = new CompositeType("Broken", "Broken", new String[]{"bad", "ok"}, new String[]{"bad", "ok"}, new OpenType[]{SimpleType.STRING, SimpleType.STRING});
        CompositeData broken = new CompositeDataSupport(type, Map.of("bad", "bad", "ok", "good")) {
            @Override public Object get(String key) { if (key.equals("bad")) throw new IllegalStateException("Do not expose arbitrary exception messages"); return super.get(key); }
        };
        var captured = StructuredValue.capture("x", broken);
        assertTrue(captured.incomplete()); assertEquals(StructuredValue.Kind.ERROR, child(captured.root(), "bad").kind());
        assertEquals("good", child(captured.root(), "ok").value());
    }

    @Test void cyclesAndDepthAreBoundedWithoutMistakingRepeatedReferencesForCycles() {
        Object[] cycle = new Object[3]; cycle[0] = cycle; cycle[1] = cycle; cycle[2] = "tail";
        var captured = StructuredValue.capture("cycle", cycle);
        assertEquals(4, captured.nodeCount()); assertTrue(captured.incomplete());
        assertEquals(StructuredValue.Kind.LIMIT, captured.root().children().get(1).kind());
        Object[] shared = {1, 2};
        assertFalse(StructuredValue.capture("shared", new Object[]{shared, shared}).incomplete());
        Object deep = "leaf";
        for (int i = 0; i < 30; i++) deep = new Object[]{deep};
        var depth = StructuredValue.capture("deep", deep);
        assertEquals(7, depth.nodeCount()); assertTrue(depth.incomplete());
    }

    @Test void nodeChildTextAndSharedBudgetsBoundRetainedData() {
        Object[] wide = new Object[100]; Arrays.fill(wide, new int[100]);
        var nodes = StructuredValue.capture("wide", wide);
        assertEquals(512, nodes.nodeCount()); assertTrue(nodes.incomplete());
        assertEquals(512, flatten(nodes.root()).count());
        var children = StructuredValue.capture("many", new int[1000]);
        assertEquals(100, children.root().children().size()); assertTrue(children.root().limited());
        var longText = StructuredValue.capture("text", "x".repeat(100_000));
        assertTrue(longText.incomplete()); assertTrue(longText.root().limited());
        assertTrue(characters(longText.root()) <= StructuredValue.MAX_CHARS);
        var budget = new StructuredValue.Budget(5, 200);
        var first = StructuredValue.capture("first", new int[]{1, 2}, budget);
        var second = StructuredValue.capture("second", new int[]{3, 4}, budget);
        assertTrue(second.incomplete()); assertEquals(5, first.nodeCount() + second.nodeCount());
        assertNull(StructuredValue.capture("third", 7, budget));
        assertTrue(characters(first.root()) + characters(second.root()) <= 200);
    }

    @Test void hostileNumberSubclassAndHugeIntegerAreNotConverted() {
        BigInteger hostile = new BigInteger("2") { @Override public String toString() { fail("Subclass must not execute"); return ""; } };
        assertEquals(StructuredValue.Kind.UNSUPPORTED, StructuredValue.capture("number", hostile).root().kind());
        assertEquals(StructuredValue.Kind.LIMIT, StructuredValue.capture("large", BigInteger.ONE.shiftLeft(20_000)).root().kind());
        assertTrue(ValueFormatter.format(BigInteger.ONE.shiftLeft(20_000)).contains("display conversion limit"));
        assertEquals("9007199254740993", StructuredValue.capture("exact", 9_007_199_254_740_993L).root().value());
    }

    @Test void workerCancellationStopsTraversal() {
        Thread.currentThread().interrupt();
        try { assertThrows(CancellationException.class, () -> StructuredValue.capture("x", new int[100])); }
        finally { Thread.interrupted(); }
    }

    private static StructuredValue.Node child(StructuredValue.Node node, String name) {
        return node.children().stream().filter(n -> n.name().equals(name)).findFirst().orElseThrow();
    }
    private static Stream<StructuredValue.Node> flatten(StructuredValue.Node node) {
        return Stream.concat(Stream.of(node), node.children().stream().flatMap(StructuredValueTest::flatten));
    }
    private static long characters(StructuredValue.Node node) { return flatten(node).mapToLong(n -> n.name().length() + n.type().length() + n.value().length()).sum(); }
}
