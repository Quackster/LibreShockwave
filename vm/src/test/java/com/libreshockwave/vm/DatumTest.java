package com.libreshockwave.vm;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

/**
 * Unit tests for Datum value types.
 */
class DatumTest {

    @Test
    void testIntegerValues() {
        Datum zero = Datum.of(0);
        Datum one = Datum.of(1);
        Datum negative = Datum.of(-42);

        assertTrue(zero.isInt());
        assertTrue(one.isInt());
        assertTrue(negative.isInt());

        assertEquals(0, zero.toInt());
        assertEquals(1, one.toInt());
        assertEquals(-42, negative.toInt());

        // Singleton optimization
        assertSame(Datum.ZERO, zero);
        assertSame(Datum.ONE, one);
    }

    @Test
    void testFloatValues() {
        Datum pi = Datum.of(3.14159);
        Datum negative = Datum.of(-2.5);

        assertTrue(pi.isFloat());
        assertTrue(negative.isFloat());

        assertEquals(3.14159, pi.toDouble(), 0.00001);
        assertEquals(-2.5, negative.toDouble(), 0.00001);

        // Int conversion truncates
        assertEquals(3, pi.toInt());
    }

    @Test
    void testStringValues() {
        Datum hello = Datum.of("Hello World");
        Datum empty = Datum.of("");

        assertTrue(hello.isString());
        assertTrue(empty.isString());

        assertEquals("Hello World", hello.toStr());
        assertEquals("", empty.toStr());

        // Empty string singleton
        assertSame(Datum.EMPTY_STRING, empty);
    }

    @Test
    void testSymbolValues() {
        Datum sym = Datum.symbol("mySymbol");

        assertTrue(sym.isSymbol());
        assertEquals("mySymbol", ((Datum.Symbol) sym).name());
        assertEquals("#mySymbol", sym.toString());
    }

    @Test
    void testListValues() {
        Datum list = Datum.list(Datum.of(1), Datum.of(2), Datum.of(3));

        assertTrue(list.isList());
        assertEquals(3, ((Datum.List) list).items().size());
        assertEquals("[1, 2, 3]", list.toString());
    }

    @Test
    void testPropListValues() {
        Datum propList = Datum.propList(Map.of("x", Datum.of(10), "y", Datum.of(20)));

        assertTrue(propList.isPropList());
        assertEquals(2, ((Datum.PropList) propList).properties().size());
    }

    @Test
    void testTruthiness() {
        // Falsy values
        assertFalse(Datum.VOID.isTruthy());
        assertFalse(Datum.ZERO.isTruthy());
        assertFalse(Datum.of(0.0).isTruthy());
        assertFalse(Datum.EMPTY_STRING.isTruthy());

        // Truthy values
        assertTrue(Datum.ONE.isTruthy());
        assertTrue(Datum.of(42).isTruthy());
        assertTrue(Datum.of(-1).isTruthy());
        assertTrue(Datum.of(0.001).isTruthy());
        assertTrue(Datum.of("hello").isTruthy());
        assertTrue(Datum.list().isTruthy());
    }

    @Test
    void testTypeCoercion() {
        // String to number
        assertEquals(42, Datum.of("42").toInt());
        assertEquals(3.14, Datum.of("3.14").toDouble(), 0.001);

        // Invalid string to number
        assertEquals(0, Datum.of("abc").toInt());
        assertEquals(0.0, Datum.of("abc").toDouble(), 0.001);

        // Number to string
        assertEquals("42", Datum.of(42).toStr());
        assertEquals("3.14", Datum.of(3.14).toStr());
    }

    @Test
    void testPointAndRect() {
        Datum point = new Datum.Point(100, 200);
        Datum rect = new Datum.Rect(0, 0, 640, 480);

        assertEquals("point(100, 200)", point.toString());
        assertEquals("rect(0, 0, 640, 480)", rect.toString());
    }

    @Test
    void testSpriteAndCastMemberRef() {
        Datum sprite = new Datum.SpriteRef(5);
        Datum member = new Datum.CastMemberRef(1, 10);

        assertEquals("sprite(5)", sprite.toString());
        assertEquals("member(10, 1)", member.toString());
    }

    @Test
    void testNumericOperations() {
        Datum a = Datum.of(10);
        Datum b = Datum.of(3);

        // These would be done by the VM, but we can test the coercion
        assertEquals(10, a.toInt());
        assertEquals(3, b.toInt());
        assertEquals(10.0, a.toDouble(), 0.001);
        assertEquals(3.0, b.toDouble(), 0.001);
    }
}
