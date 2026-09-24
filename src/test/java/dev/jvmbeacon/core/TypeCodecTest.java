package dev.jvmbeacon.core;

import org.junit.jupiter.api.Test;
import javax.management.ObjectName;
import java.math.BigInteger;
import static org.junit.jupiter.api.Assertions.*;

class TypeCodecTest {
    @Test void parsesExactScalarsAndPreservesStringWhitespace() throws Exception {
        assertEquals(42, TypeCodec.parse("int", "42"));
        assertEquals(true, TypeCodec.parse("boolean", "true"));
        assertEquals("  text  ", TypeCodec.parse("java.lang.String", "  text  "));
        assertEquals(new BigInteger("999999999999999999999"), TypeCodec.parse("java.math.BigInteger", "999999999999999999999"));
        assertEquals(new ObjectName("beacon:type=Probe"), TypeCodec.parse("javax.management.ObjectName", "beacon:type=Probe"));
    }
    @Test void rejectsInvalidLiteralsOverflowAndUnknownTypes() {
        for (String bad : new String[]{"yes", "TRUE", "1", ""}) assertThrows(IllegalArgumentException.class, () -> TypeCodec.parse("boolean", bad));
        assertThrows(IllegalArgumentException.class, () -> TypeCodec.parse("byte", "256"));
        assertThrows(IllegalArgumentException.class, () -> TypeCodec.parse("double", "NaN"));
        assertThrows(IllegalArgumentException.class, () -> TypeCodec.parse("double", "1e999"));
        assertThrows(IllegalArgumentException.class, () -> TypeCodec.parse("java.util.Date", "now"));
        assertThrows(IllegalArgumentException.class, () -> TypeCodec.parse("com.example.Mode", "ON"));
        assertFalse(TypeCodec.supports("[[I"));
    }
    @Test void parsesJsonPrimitiveAndStringArrays() {
        assertArrayEquals(new int[]{1, -2, 3}, (int[]) TypeCodec.parse("[I", "[1, -2, 3]"));
        assertArrayEquals(new boolean[]{true, false}, (boolean[]) TypeCodec.parse("boolean[]", "[true,false]"));
        assertArrayEquals(new String[]{"a,b", "line\nnext", "中"}, (String[]) TypeCodec.parse("[Ljava.lang.String;", "[\"a,b\",\"line\\nnext\",\"\\u4e2d\"]"));
        assertArrayEquals(new char[]{'a','中'}, (char[]) TypeCodec.parse("[C", "[\"a\",\"中\"]"));
        assertEquals(0, ((int[]) TypeCodec.parse("[I", "[]")).length);
    }
    @Test void rejectsAmbiguousAndOversizedArrays() {
        for (String bad : new String[]{"[1,]", "[null]", "[\"1\"]", "[1]junk", "[[1]]"}) assertThrows(IllegalArgumentException.class, () -> TypeCodec.parse("[I", bad));
        assertThrows(IllegalArgumentException.class, () -> TypeCodec.parse("[Ljava.lang.String;", "[unquoted]"));
        assertThrows(IllegalArgumentException.class, () -> TypeCodec.parse("[I", "[" + "1,".repeat(1024) + "1]"));
        assertThrows(IllegalArgumentException.class, () -> TypeCodec.parse("java.lang.String", "x".repeat(65537)));
    }
    @Test void formatterBoundsCyclesAndNeverCallsUnknownToString() {
        Object unknown = new Object() { @Override public String toString() { throw new AssertionError("Must never be called"); } };
        assertTrue(ValueFormatter.format(unknown).contains("unsupported result type"));
        Object[] cycle = new Object[1]; cycle[0] = cycle;
        assertTrue(ValueFormatter.format(cycle).contains("cycle"));
        assertTrue(ValueFormatter.format("x".repeat(50_000)).length() < 33_000);
        assertTrue(ValueFormatter.format(new int[500]).contains("more"));
        assertEquals("null", ValueFormatter.format(null));
    }
}
