"""Hides, from a log, what ties it to one bike and one person's networks, before it is shared.

Three things are masked:
- the dash's Wi-Fi name, `RE_<4 characters>_<6 digits>` (e.g. `RE_AB12_345678`), tied to one bike and its owner (the system's logcat carries it whole 51 times
  in the logs of 26/10/2026; REau's own ride log never writes it), even glued to other text or escaped in an address (`%22RE_AB12_345678%22`), and any other
  name that starts the way a dash's does (at the start of a word, after a character that is not of a word, or after a `%XX` escape; `MODE_RE_ABCD` is left);
- every MAC address (six pairs of hex digits split by colons, `aa:bb:…:ff`: the dash's BSSID, the phone's, a router's);
- the names of the Wi-Fi networks that are not the dash's (home, work): the name that follows `SSID`, `ssid=`, `configKey=` or `SSID:` (quoted, or unquoted up to the comma
  or the next field). A name found that way is also hidden everywhere else in the same file (where a line says it without the keyword), and `--rede NOME` hides one by hand.

It is the list of what the collection script (`coletar_do_celular.sh`) masks in the copy it makes for sharing; the original stays private. A name that is also an
ordinary word is hidden everywhere too: the copy errs on the side of hiding. Names with fewer than 3 characters are only hidden after the keyword.

Usage:  python3 mascarar.py [--rede NOME ...] ENTRADA SAIDA
"""
import os
import re
import sys

# Any name that starts the way a dash's does is hidden first (a longer or odd variant must not leak), then the dash's own pattern.
# The left edge of `_OTHER` is the start of a word, a character that is not of a word, or a URL escape (`%22RE_…%22`: the `2` and the `R` are both word
# characters, so `\b` alone lets it through); it is still not "anywhere", so `MODE_RE_ABCD` or `PURE_REF` (an ordinary identifier that holds `RE_`) stay.
# The dash's own pattern has no edge at all: it is hidden glued to any text before or after it (`xRE_AB12_345678`, `%22RE_AB12_345678%22`).
_OTHER = re.compile(r"(?:(?<=%[0-9A-Fa-f]{2})|\b)RE_[A-Za-z0-9_]{4,}")
_DASH = re.compile(r"RE_[A-Za-z0-9]{4}_\d{6}")
DASH_QUALQUER = _OTHER  # any name that starts as a dash's does (what the system log reader hides too)
MAC = re.compile(r"\b(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\b")
MASK = "RE_****_******"
MASK_MAC = "**:**:**:**:**:**"
MASK_REDE = "<rede>"
NOME_MINIMO = 3

# `SSID: "Name"`, `ssid="Name"`, `configKey="Name"WPA_PSK`, `SSID 'Name'`: the quoted name after the keyword.
_ENTRE_ASPAS = re.compile(r"""(?i)\b(ssid|configKey)(\s*[=:]?\s*)(["'])([^"'\n]*)\3""")
# `SSID: Name, BSSID: …` (dumpsys): unquoted, up to the comma or the end of the line; `ssid=Name bssid=…`: one token.
_COM_VIRGULA = re.compile(r"(?i)\bSSID(\s*:[ \t]*)(?![ \t]*[\"'<*])([^,\n]+?)(?=,|\s*$)")
_UM_TOKEN = re.compile(r"(?i)\bssid(=)(?![\"'<*])([^\s,\"']+)")
_JA_MASCARADO = re.compile(r"^(?:RE_|<rede>|\*+$)")


def _e_do_painel(nome: str) -> bool:
    return nome.startswith("RE_") or nome.strip("*_") == ""


def nomes_de_redes(texto: str) -> set:
    """The Wi-Fi names that are not the dash's, as [texto] says them after a keyword (`SSID: "Casa"` gives `Casa`)."""
    achados = set()
    for m in _ENTRE_ASPAS.finditer(texto):
        achados.add(m.group(4))
    for rex in (_COM_VIRGULA, _UM_TOKEN):
        for m in rex.finditer(texto):
            achados.add(m.group(2).strip())
    return {n for n in achados if n and not _e_do_painel(n) and n != MASK_REDE and not n.isdigit() and n.lower() not in ("none", "null", "<unknown ssid>", "0x", "<hidden>")}


def compilar_nomes(nomes):
    """One regex for all the [nomes] to hide wherever they stand (None if none is long enough), longest first so a name does not eat part of another.

    One regex for the whole file, not one per name and line: with hundreds of names the per-name patterns outgrow Python's cache of compiled ones and are
    compiled again on every line (a 267 MB log with 830 names would take about 55 hours)."""
    ordenados = sorted({n for n in nomes if len(n) >= NOME_MINIMO}, key=len, reverse=True)
    if not ordenados:
        return None
    return re.compile(r"(?<![\w])(?:" + "|".join(map(re.escape, ordenados)) + r")(?![\w])")


def mascarar(texto: str, nomes=()) -> str:
    """[texto] with the dash's Wi-Fi names, every MAC and the other networks' names replaced. [nomes]: more names to hide wherever they stand
    (a collection of names, or what [compilar_nomes] gave, which a caller that masks many lines makes once)."""
    texto = _DASH.sub(MASK, _OTHER.sub(MASK, texto))
    texto = MAC.sub(MASK_MAC, texto)

    def entre_aspas(m):
        return m.group(0) if _e_do_painel(m.group(4)) or m.group(4) in ("", MASK_REDE) else f"{m.group(1)}{m.group(2)}{m.group(3)}{MASK_REDE}{m.group(3)}"

    texto = _ENTRE_ASPAS.sub(entre_aspas, texto)
    texto = _COM_VIRGULA.sub(lambda m: m.group(0) if _e_do_painel(m.group(2).strip()) else f"SSID{m.group(1)}{MASK_REDE}", texto)
    texto = _UM_TOKEN.sub(lambda m: m.group(0) if _e_do_painel(m.group(2)) else f"ssid={MASK_REDE}", texto)
    rex = nomes if hasattr(nomes, "sub") else compilar_nomes(nomes)
    return rex.sub(MASK_REDE, texto) if rex else texto


def _opcoes(argv):
    """(nomes dados à mão, caminhos) da linha de comando; None se `--rede` ficou sem nome."""
    nomes, caminhos, args = [], [], list(argv)
    while args:
        a = args.pop(0)
        if a == "--rede":
            if not args:
                return None
            nomes.append(args.pop(0))
        else:
            caminhos.append(a)
    return nomes, caminhos


def main(argv):
    opcoes = _opcoes(argv)
    if opcoes is None or len(opcoes[1]) != 2:
        print(__doc__)
        return 2
    manuais, (entrada, saida) = opcoes
    if os.path.abspath(entrada) == os.path.abspath(saida):
        # Opening the output empties it: over its own input the file would be lost.
        print("a saída não pode ser o próprio arquivo de entrada", file=sys.stderr)
        return 2
    # First pass: the names the file itself says after a keyword, so they are hidden where a line says them alone.
    nomes = set(manuais)
    with open(entrada, encoding="utf-8", errors="replace") as primeira:
        for linha in primeira:
            nomes |= nomes_de_redes(linha)
    todos = compilar_nomes(nomes)  # once for the whole file
    with open(entrada, encoding="utf-8", errors="replace") as lendo, open(saida, "w", encoding="utf-8") as escrevendo:
        for linha in lendo:
            escrevendo.write(mascarar(linha, todos or ()))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
