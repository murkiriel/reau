package com.reau.app.fuzz

import java.util.Random

/**
 * Random inputs from a fixed seed, for tests that throw many odd inputs at a parser (no new dependency: the same inputs every run, so a
 * failure is a failure the next run can repeat; the failing round number is in the message). [ROUNDS] is how many inputs a test tries.
 */
object Seeded {
    const val SEED = 20_261_026L
    const val ROUNDS = 3_000

    fun random(salt: Long = 0) = Random(SEED + salt)

    fun bytes(rnd: Random, maxSize: Int): ByteArray = ByteArray(rnd.nextInt(maxSize + 1)).also { rnd.nextBytes(it) }

    /** Bytes that look more like the thing: [valid] with a few bytes flipped, a piece cut off its end, or random bytes put in. */
    fun damaged(rnd: Random, valid: ByteArray): ByteArray {
        if (valid.isEmpty()) return valid
        val out = valid.copyOf()
        repeat(rnd.nextInt(4)) { out[rnd.nextInt(out.size)] = rnd.nextInt(256).toByte() }
        return when (rnd.nextInt(3)) {
            0 -> out.copyOf(rnd.nextInt(out.size + 1))
            1 -> out + bytes(rnd, 8)
            else -> out
        }
    }
}
