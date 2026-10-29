package com.reau.app.util

/** One value made the first time it is asked for and kept for the life of the process; [forget] makes the next ask make it again (tests). */
internal class Once<T : Any> {
    @Volatile private var value: T? = null

    fun get(make: () -> T): T = value ?: synchronized(this) { value ?: make().also { value = it } }

    fun forget() {
        value = null
    }
}
