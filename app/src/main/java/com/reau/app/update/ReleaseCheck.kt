package com.reau.app.update

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Asks GitHub which release of REau is the latest, only when the rider taps "Check for updates" (never by itself): one `GET` of
 * [URL], with no account, no key and nothing about the rider in it (GitHub sees the phone's address, as any site does). It only
 * says whether a newer version is published; it downloads and installs nothing, and the page of the release opens in the browser.
 * Pure but for [fetch], so the JVM tests cover the reading of the answer and the comparison of the versions.
 */
internal object ReleaseCheck {
    const val URL = "https://api.github.com/repos/Murkiriel/reau/releases/latest"

    /** The release pages that may be opened: the project's own. */
    const val RELEASES_PREFIX = "https://github.com/Murkiriel/reau/"
    private const val TIMEOUT_MS = 10_000
    private const val MAX_BODY_BYTES = 1_000_000

    /** Why the check gave no answer. */
    enum class Why { OFFLINE, LIMITED, SERVER, BAD_ANSWER }

    sealed interface Result {
        /** The latest release: its tag (`v0.1.0`) and the address of its page. */
        data class Latest(val tag: String, val pageUrl: String) : Result

        /** GitHub knows the project and has published nothing. */
        data object NoRelease : Result
        data class Failed(val why: Why) : Result
    }

    /** What the rider is told. */
    enum class Verdict { UP_TO_DATE, NEWER, DEV_BUILD, UNKNOWN }

    /** The latest release out of GitHub's answer, or a bad answer. (The JSON reader is org.json, which the test JVM lacks: [fromFields] is tested there.) */
    fun parse(json: String): Result {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return Result.Failed(Why.BAD_ANSWER)
        return fromFields(root.optString("tag_name", ""), root.optString("html_url", ""))
    }

    /** The release out of the two fields of the answer that matter: its [tagName] and the address of its page, [htmlUrl]. */
    fun fromFields(tagName: String, htmlUrl: String): Result {
        val tag = tagName.trim()
        val page = htmlUrl.trim()
        if (tag.isEmpty() || coreOf(tag) == null) return Result.Failed(Why.BAD_ANSWER)
        // The page is opened in the browser: only the project's own addresses; anything else falls back to the project's releases.
        val safePage = if (page.startsWith(RELEASES_PREFIX)) page else RELEASES_PREFIX + "releases"
        return Result.Latest(tag, safePage)
    }

    /** What an HTTP status means before the body is read: null means "read the body". */
    fun failureOf(status: Int): Result? = when {
        status == HttpURLConnection.HTTP_OK -> null
        status == HttpURLConnection.HTTP_NOT_FOUND -> Result.NoRelease
        status == HttpURLConnection.HTTP_FORBIDDEN || status == TOO_MANY_REQUESTS -> Result.Failed(Why.LIMITED)
        status >= HttpURLConnection.HTTP_INTERNAL_ERROR -> Result.Failed(Why.SERVER)
        else -> Result.Failed(Why.BAD_ANSWER)
    }

    private const val TOO_MANY_REQUESTS = 429

    /** The numbers of a version, `v0.1.0` or `0.1.0-dev+26.10.21-07.53` giving (0, 1, 0); null when it is not a version. */
    fun coreOf(version: String): List<Int>? {
        val core = version.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
        val parts = core.split('.')
        if (parts.size !in 2..3 || !parts.all(::isSmallNumber)) return null
        return (parts.map { it.toInt() } + listOf(0, 0, 0)).take(3)
    }

    private fun isSmallNumber(text: String) = text.isNotEmpty() && text.length <= MAX_DIGITS && text.all(Char::isDigit)

    private const val MAX_DIGITS = 6

    /** What to tell about [current] (this build's name) given [latestTag]. */
    fun verdict(current: String, latestTag: String): Verdict {
        val have = coreOf(current)
        val latest = coreOf(latestTag)
        if (have == null || latest == null) return Verdict.UNKNOWN
        val newer = (0 until 3).map { latest[it].compareTo(have[it]) }.firstOrNull { it != 0 } ?: 0
        return when {
            newer > 0 -> Verdict.NEWER
            "-dev" in current -> Verdict.DEV_BUILD
            else -> Verdict.UP_TO_DATE
        }
    }

    /** At most [MAX_BODY_BYTES] of the answer: a longer one is cut, and what is cut is no valid release, so it is a bad answer. */
    private fun readCapped(input: java.io.InputStream): String =
        String(com.reau.app.util.CappedRead.read(input, MAX_BODY_BYTES).bytes, Charsets.UTF_8)

    /** The one request. Blocking: call it off the main thread. Never throws. */
    fun fetch(userAgent: String): Result = try {
        val connection = URL(URL).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            failureOf(connection.responseCode) ?: parse(connection.inputStream.use(::readCapped))
        } finally {
            connection.disconnect()
        }
    } catch (@Suppress("SwallowedException") e: IOException) { // no network, a timeout, a certificate: all "could not reach GitHub"
        Result.Failed(Why.OFFLINE)
    } catch (@Suppress("SwallowedException") e: SecurityException) {
        Result.Failed(Why.OFFLINE)
    }
}
