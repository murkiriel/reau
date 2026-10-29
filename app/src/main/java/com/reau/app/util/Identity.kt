package com.reau.app.util

import java.util.Collections
import java.util.IdentityHashMap

/** The elements without repeats by identity (the same object once), in order; `equals` is not asked: two equal nodes are two nodes. */
fun <T : Any> List<T>.distinctByIdentity(): List<T> {
    val seen = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
    return filter { seen.add(it) }
}
