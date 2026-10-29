package com.reau.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** A clickable node stood in the walk's list once for each descendant with a text: giving it back twice throws before Android 13. */
class IdentityTest {
    private class Node(val name: String) {
        override fun equals(other: Any?) = other is Node && other.name == name
        override fun hashCode() = name.hashCode()
    }

    @Test
    fun `the same object once, in order`() {
        val a = Node("a")
        val b = Node("b")
        val result = listOf(a, b, a, a, b).distinctByIdentity()
        assertEquals(2, result.size)
        assertSame(a, result[0])
        assertSame(b, result[1])
    }

    @Test
    fun `two equal objects are two`() {
        val one = Node("x")
        val other = Node("x")
        assertEquals(2, listOf(one, other, one).distinctByIdentity().size)
        assertEquals(1, listOf(one, other, one).distinct().size) // what distinct() would have done: one node never given back
    }

    @Test
    fun `an empty list stays empty`() {
        assertEquals(emptyList<Node>(), emptyList<Node>().distinctByIdentity())
    }
}
