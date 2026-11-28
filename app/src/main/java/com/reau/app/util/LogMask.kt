package com.reau.app.util

import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.Reader

/**
 * Hides, in a log that is about to leave the phone, what ties it to one bike and to one person's networks: the same three things, and the same
 * rules, as `scripts/registro/mascarar.py` (the script that masks the copy of a collection made for sharing), written again here so the zip
 * that "Compartilhar" exports is masked the same way (the owner, 11/11/2026).
 *
 * - The dash's Wi-Fi name, `RE_<4 letters or digits>_<6 digits>`, even glued to other text or escaped in an address (`%22RE_…%22`), and any other
 *   name that starts the way a dash's does (at the start of a word, after a character that is not of a word, or after a `%XX` escape; `MODE_RE_ABCD`
 *   is left), become [MASK].
 * - Every MAC address (six pairs of hex digits split by colons or by hyphens, even glued to a letter or a digit; the hyphen form not after a hex digit nor after two digits and a hyphen,
 *   which a date and time `2026-11-26-10-30-15` would be; and twelve hex digits with no separator, after `bssid`, `mac`, `macaddr` or `hwaddr` and `=` or `:`) becomes [MASK_MAC].
 * - The names of the Wi-Fi networks that are not the dash's (home, work): the name that follows `SSID`, `ssid=`, `configKey=` or `SSID:`, quoted or
 *   not, becomes [MASK_NETWORK]. A name found that way is also hidden everywhere else in the same export (where a line says it without the
 *   keyword), and [extraNames] (the paired dash's own, which may not start with `RE_`) are hidden wherever they stand. A name of fewer than
 *   [MINIMUM_NAME] characters is only hidden after a keyword.
 *
 * It errs on the side of hiding: a name that is also an ordinary word is hidden everywhere too. Only the text logs go through it; the
 * recordings are binary and are not read.
 */
object LogMask {
    const val MASK = "RE_****_******"
    const val MASK_MAC = "**:**:**:**:**:**"
    const val MASK_NETWORK = "<rede>"
    const val MINIMUM_NAME = 3

    // Any name that starts the way a dash's does is hidden first, then the dash's own pattern. OTHER's left edge is the start of a word, a character that
    // is not of a word, or a URL escape (`%22RE_…%22`: the `2` and the `R` are both word characters, so `\b` alone lets it through), and not "anywhere",
    // so an ordinary identifier that holds `RE_` (`MODE_RE_ABCD`, `PURE_REF`) stays. DASH has no edge at all: it is hidden glued to any text before or after.
    private val OTHER = Regex("""(?:(?<=%[0-9A-Fa-f]{2})|\b)RE_[A-Za-z0-9_]{4,}""")
    private val DASH = Regex("""RE_[A-Za-z0-9]{4}_\d{6}""")
    // A MAC has no edge, like the script's (53d3ad2, 25/11/2026; the `\b` here was left behind): glued to a letter or a digit it is hidden too. The hyphen form has a
    // left edge, because a date and time holds six pairs split by hyphens (26/11/2026, review B4): it does not start right after a hex digit, and a run of SIX PAIRS OF DIGITS
    // does not start right after a year (`2026-`) or a year and a month (`2026-11-`), where the date's own pairs begin. Only digits: a pair with a hex LETTER cannot be part of a
    // date, so `2026-12-aa-bb-cc-dd-ee-ff` is hidden (review of 26/11/2026, S5; the edge used to be any two digits and a hyphen, which left it whole). A hex LETTER or a single
    // digit before the hyphen (`bssid-`, `wlan0-`, `feed-`, `id-1a-`) is a word, and the MAC after it is hidden (review of 26/11/2026, C5). With no separator it is hidden only after
    // its keyword, and only when it is exactly twelve digits (a longer run is a hash).
    // One line on purpose: scripts/registro/mascarar.py holds the same pattern and the mutation catalog finds its pieces whole.
    @Suppress("MaxLineLength")
    private val MAC = Regex("""(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}|(?<![0-9A-Fa-f])(?<!^\d\d-)(?!(?:(?<=\d{4}-)|(?<=\d{4}-\d\d-))(?:\d\d-){5}\d\d)(?:[0-9A-Fa-f]{2}-){5}[0-9A-Fa-f]{2}""")
    // A word that ends in two hex digits glued to a MAC (`mac-aa-bb-cc-dd-ee-ff`, `device-00-11-22-33-44-55`) makes [MAC] match one position early (the `ac` of `mac` is a pair)
    // and leave the last pair out, which leaked. The pair that follows the MAC after the same kind of sign, whole, is hidden with it: it hides too much, never too little
    // (review of the plan 34, A5, 26/11/2026). The date `2026-11-26-10-30-15-45` is left as it was: the left edge of the hyphen form refuses it before.
    private val MAC_AND_ONE_MORE = Regex("(?:" + MAC.pattern + ")" + """(?:[:-][0-9A-Fa-f]{2}(?![0-9A-Fa-f]))?""")
    private val BARE_MAC = Regex("""(?i)(?<![a-z])((?:bssid|mac(?:addr(?:ess)?)?|hwaddr)\s*[=:]\s*["']?)[0-9a-f]{12}(?![0-9a-f])""")

    // `SSID: "Name"`, `ssid="Name"`, `configKey="Name"WPA_PSK`, `SSID 'Name'`: the quoted name after the keyword.
    private val QUOTED = Regex("""(?i)\b(ssid|configKey)(\s*[=:]?\s*)(["'])([^"'\n]*)\3""")

    // `SSID: Name, BSSID: …` (dumpsys): unquoted, up to the comma or the end of the line; `ssid=Name bssid=…`: one token.
    private val TO_COMMA = Regex("""(?i)\bSSID(\s*:[ \t]*)(?![ \t]*["'<*])([^,\n]+?)(?=,|\s*$)""")
    private val ONE_TOKEN = Regex("""(?i)\bssid(=)(?!["'<*])([^\s,"']+)""")
    private val NOT_A_NAME = setOf("none", "null", "<unknown ssid>", "0x", "<hidden>")

    private fun isTheDashs(name: String) = name.startsWith("RE_") || name.trim('*', '_').isEmpty()

    /** Whether [line] could hold anything to hide on its own: the cheap look that spares the regexes for most lines. */
    private fun hasKeyword(line: String) = line.contains("ssid", ignoreCase = true) || line.contains("configkey", ignoreCase = true)

    /** The Wi-Fi names that are not the dash's, as [text] says them after a keyword (`SSID: "Casa"` gives `Casa`). */
    fun networkNames(text: String): Set<String> {
        if (!hasKeyword(text)) return emptySet()
        val found = mutableSetOf<String>()
        QUOTED.findAll(text).forEach { found += it.groupValues[4] }
        for (regex in listOf(TO_COMMA, ONE_TOKEN)) regex.findAll(text).forEach { found += it.groupValues[2].trim() }
        return found.filterTo(mutableSetOf()) { n ->
            n.isNotEmpty() && !isTheDashs(n) && n != MASK_NETWORK && !n.all { it.isDigit() } && n.lowercase() !in NOT_A_NAME
        }
    }

    /** [text] with the dash's Wi-Fi names, every MAC and the other networks' names replaced; [names]: more names to hide wherever they stand. */
    fun mask(text: String, names: Collection<String> = emptySet()): String {
        var out = text
        if (out.contains("RE_")) out = DASH.replace(OTHER.replace(out, MASK), MASK)
        out = hideMacs(out)
        if (hasKeyword(out)) {
            out = QUOTED.replace(out) { m ->
                val name = m.groupValues[4]
                if (isTheDashs(name) || name.isEmpty() || name == MASK_NETWORK) {
                    m.value
                } else {
                    "${m.groupValues[1]}${m.groupValues[2]}${m.groupValues[3]}$MASK_NETWORK${m.groupValues[3]}"
                }
            }
            out = TO_COMMA.replace(out) { m -> if (isTheDashs(m.groupValues[2].trim())) m.value else "SSID${m.groupValues[1]}$MASK_NETWORK" }
            out = ONE_TOKEN.replace(out) { m -> if (isTheDashs(m.groupValues[2])) m.value else "ssid=$MASK_NETWORK" }
        }
        for (name in names.filter { it.length >= MINIMUM_NAME }.sortedByDescending { it.length }) {
            if (out.contains(name)) out = Regex("""(?<![\p{L}\p{N}_])${Regex.escape(name)}(?![\p{L}\p{N}_])""").replace(out, MASK_NETWORK)
        }
        return out
    }

    /** [text] with every MAC address replaced (see [MAC] and [BARE_MAC]). */
    private fun hideMacs(text: String): String {
        var out = text
        if (out.contains(':') || out.contains('-')) out = MAC_AND_ONE_MORE.replace(out, MASK_MAC)
        if (out.contains("mac", ignoreCase = true) || out.contains("bssid", ignoreCase = true) || out.contains("hwaddr", ignoreCase = true)) {
            out = BARE_MAC.replace(out) { m -> m.groupValues[1] + MASK_MAC }
        }
        return out
    }

    /** The networks' names that the lines of [file] say after a keyword. */
    fun networkNames(file: File): Set<String> {
        val found = mutableSetOf<String>()
        file.inputStream().use { forEachLine(InputStreamReader(it, Charsets.UTF_8)) { line -> found += networkNames(line) } }
        return found
    }

    /** [file] written to [out] masked, line by line (a log reaches tens of MB); [out] is left open. */
    fun write(file: File, out: OutputStream, names: Collection<String>) {
        file.inputStream().use { input ->
            forEachLine(InputStreamReader(input, Charsets.UTF_8)) { line -> out.write(mask(line, names).toByteArray(Charsets.UTF_8)) }
        }
    }

    private const val BUFFER = 8192

    /** The lines of [reader], each with its newline (the last one without, if the text does not end with one). */
    private fun forEachLine(reader: Reader, each: (String) -> Unit) {
        val buffer = CharArray(BUFFER)
        val line = StringBuilder()
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            var start = 0
            for (i in 0 until read) {
                if (buffer[i] == '\n') {
                    line.append(buffer, start, i + 1 - start)
                    each(line.toString())
                    line.setLength(0)
                    start = i + 1
                }
            }
            line.append(buffer, start, read - start)
        }
        if (line.isNotEmpty()) each(line.toString())
    }
}
