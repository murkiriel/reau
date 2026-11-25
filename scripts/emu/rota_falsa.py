"""A fake GPS that walks a route, so Android Auto / Google Maps "navigate by themselves" and the REau sends the arrow, the street, the time left
and the arrival time to the panel without anyone leaving home. Development tool: it only talks to an emulator or to a phone you plug in on purpose.

Usage:
    rota_falsa.py rotas/volta_central.gpx                       emulator (emulator-5554), 40 km/h, one point per second
    rota_falsa.py rotas/volta_central.gpx --nmea                emulator, GPGGA + GPRMC (speed and course travel in the sentence)
    rota_falsa.py rotas/volta_central.gpx --alvo celular        phone: the Android test provider "gps" (see the requirement below)
    rota_falsa.py rotas/volta_central.gpx --seco                only prints the adb commands (no adb call, no waiting)
    rota_falsa.py --alvo celular --limpar                       removes the test provider and puts the MOCK_LOCATION appop back to default

The route is a GPX file (trkpt; rtept when there is no trkpt; namespaces ignored) or a JSON list [[lat, lon], ...].
--inicio 1500 (metres) or --inicio p12 (index of the route point) starts in the middle; --repetir loops until Ctrl+C;
--acelerar 4 makes the fake drive 4 times faster (same step per point, a quarter of the waiting).

Where the position goes (the console syntax, longitude BEFORE latitude, is `geo fix <lon> <lat> [<alt>]`, and `geo nmea <sentence>`):
    emulador   adb -s SERIAL emu geo fix LON LAT [ALT]      (or, with --nmea, adb -s SERIAL emu geo nmea '$GPGGA,...' and '$GPRMC,...')
    celular    adb shell cmd location providers ...         (Android 12+; add-test-provider, set-test-provider-enabled/-location, remove-test-provider)

Phone requirement (once per device, with the cable or wireless debugging; "Developer options" is enough, no app has to be picked):
    adb shell appops set --uid 2000 MOCK_LOCATION allow
To undo it: `--alvo celular --limpar`. On exit (end of the route or Ctrl+C) the test provider is removed, unless --manter.

Google Maps must be navigating (a destination chosen, "Start") for the REau to receive the route. The fake only moves the position.

The adb comes from --adb, then from the ADB environment variable, then from the PATH. The serial comes from --serial, then from ANDROID_SERIAL
(emulator: emulator-5554 when neither is given). Coordinates are only ever written to stdout (--seco) and to the adb command line: no log file.
Exit code 0 at the end of the route, 130 on Ctrl+C, 2 on a usage or route error, 1 when adb fails.
"""
import argparse
import bisect
import datetime
import json
import math
import os
import shlex
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Callable, Iterator, NamedTuple, Optional, Sequence

EARTH_RADIUS_M = 6371008.8
KNOTS_PER_MS = 1.0 / 0.514444
DEFAULT_EMU_SERIAL = "emulator-5554"
PROVIDER = "gps"
SHELL_UID = "2000"
ADB_TIMEOUT_S = 20
# With --repetir, a route whose end is farther than this from its start gets a connecting leg back to the start; a closer one just wraps.
LOOP_CLOSE_M = 30.0
# How many seconds of simulated driving between two progress lines.
PROGRESS_EVERY_S = 5.0

Point = tuple[float, float]  # (lat, lon) in degrees


class RotaInvalida(Exception):
    """The route file is missing, empty, malformed or has fewer than 2 distinct points."""


class ErroAdb(Exception):
    """An adb call failed (not found, non-zero exit, or an error text in the output)."""


class Fix(NamedTuple):
    lat: float
    lon: float
    rumo: float  # course in degrees, 0 = north, 90 = east
    vel_ms: float
    dist_m: float  # travelled so far on the route (of the current lap)


# ---- Geometry -------------------------------------------------------------------------------------------------------------------------

def haversine(a: Point, b: Point) -> float:
    """Great-circle distance in metres."""
    lat1, lon1, lat2, lon2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(min(1.0, math.sqrt(h)))


def rumo(a: Point, b: Point) -> float:
    """Initial course from a to b in degrees [0, 360): north 0, east 90."""
    lat1, lat2 = math.radians(a[0]), math.radians(b[0])
    dlon = math.radians(b[1] - a[1])
    y = math.sin(dlon) * math.cos(lat2)
    x = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(dlon)
    return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0


def destino(a: Point, b: Point, fracao: float) -> Point:
    """The point a fraction of the way from a to b (a straight line in lat/lon: the legs of a route are tens of metres)."""
    return (a[0] + (b[0] - a[0]) * fracao, a[1] + (b[1] - a[1]) * fracao)


# ---- Reading the route ----------------------------------------------------------------------------------------------------------------

def _coordenada(lat, lon, origem: str) -> Point:
    try:
        la, lo = float(lat), float(lon)
    except (TypeError, ValueError):
        raise RotaInvalida(f"{origem}: coordenada que não é número") from None
    if not (-90.0 <= la <= 90.0 and -180.0 <= lo <= 180.0) or math.isnan(la) or math.isnan(lo):
        raise RotaInvalida(f"{origem}: coordenada fora do intervalo (lat -90..90, lon -180..180)")
    return (la, lo)


def _sem_repetidos(pontos: Sequence[Point], origem: str) -> list[Point]:
    saida: list[Point] = []
    for p in pontos:
        if not saida or haversine(saida[-1], p) > 0.01:
            saida.append(p)
    if len(saida) < 2:
        raise RotaInvalida(f"{origem}: a rota precisa de pelo menos 2 pontos diferentes (achei {len(saida)})")
    return saida


def ler_gpx(texto: str, origem: str = "GPX") -> list[Point]:
    """trkpt of every track, in file order; when there is none, rtept; namespaces (GPX 1.0, 1.1 or none) are ignored."""
    try:
        raiz = ET.fromstring(texto)
    except ET.ParseError as e:
        raise RotaInvalida(f"{origem}: XML inválido ({e})") from None
    trk: list[Point] = []
    rte: list[Point] = []
    for el in raiz.iter():
        nome = el.tag.rsplit("}", 1)[-1] if isinstance(el.tag, str) else ""
        if nome == "trkpt":
            trk.append(_coordenada(el.get("lat"), el.get("lon"), origem))
        elif nome == "rtept":
            rte.append(_coordenada(el.get("lat"), el.get("lon"), origem))
    return _sem_repetidos(trk or rte, origem)


def ler_json(texto: str, origem: str = "JSON") -> list[Point]:
    try:
        dados = json.loads(texto)
    except json.JSONDecodeError as e:
        raise RotaInvalida(f"{origem}: JSON inválido ({e})") from None
    if not isinstance(dados, list):
        raise RotaInvalida(f"{origem}: esperava uma lista [[lat, lon], ...]")
    pontos = []
    for item in dados:
        if not isinstance(item, (list, tuple)) or len(item) < 2:
            raise RotaInvalida(f"{origem}: cada ponto deve ser [lat, lon]")
        pontos.append(_coordenada(item[0], item[1], origem))
    return _sem_repetidos(pontos, origem)


def ler_rota(caminho: str) -> list[Point]:
    p = Path(caminho)
    try:
        texto = p.read_text(encoding="utf-8")
    except OSError as e:
        raise RotaInvalida(f"não consegui ler {p.name}: {e.strerror or e}") from None
    if not texto.strip():
        raise RotaInvalida(f"{p.name}: arquivo vazio")
    if p.suffix.lower() == ".json" or texto.lstrip().startswith("["):
        return ler_json(texto, p.name)
    return ler_gpx(texto, p.name)


# ---- Walking the route ----------------------------------------------------------------------------------------------------------------

class Trajeto:
    """The route as cumulative distances, to ask 'where am I after d metres?'."""

    def __init__(self, pontos: Sequence[Point], repetir: bool = False):
        pts = list(pontos)
        if repetir and haversine(pts[-1], pts[0]) > LOOP_CLOSE_M:
            pts.append(pts[0])
        self.pontos = pts
        self.acum = [0.0]
        for a, b in zip(pts, pts[1:]):
            self.acum.append(self.acum[-1] + haversine(a, b))
        self.total = self.acum[-1]

    def posicao(self, d: float) -> tuple[Point, float]:
        """(position, course) at d metres from the start (clamped to the route)."""
        d = min(max(d, 0.0), self.total)
        i = min(bisect.bisect_right(self.acum, d) - 1, len(self.pontos) - 2)
        a, b = self.pontos[i], self.pontos[i + 1]
        seg = self.acum[i + 1] - self.acum[i]
        fr = 0.0 if seg <= 0 else (d - self.acum[i]) / seg
        return destino(a, b, fr), rumo(a, b)

    def distancia_do_ponto(self, indice: int) -> float:
        if not 0 <= indice < len(self.pontos):
            raise RotaInvalida(f"--inicio: a rota tem pontos 0 a {len(self.pontos) - 1}, não existe o {indice}")
        return self.acum[indice]


def resolver_inicio(texto: Optional[str], trajeto: Trajeto) -> float:
    """--inicio: '1500' or '1500m' = metres; 'p12' = index of the route point. Returns metres from the start."""
    if texto is None:
        return 0.0
    t = texto.strip().lower()
    try:
        if t.startswith("p"):
            return trajeto.distancia_do_ponto(int(t[1:]))
        d = float(t[:-1] if t.endswith("m") else t)
    except ValueError:
        raise RotaInvalida(f"--inicio {texto!r}: use metros (1500 ou 1500m) ou o índice de um ponto (p12)") from None
    if d < 0 or d >= trajeto.total:
        raise RotaInvalida(f"--inicio {texto}: a rota tem {trajeto.total:.0f} m; o início precisa estar entre 0 e isso")
    return d


def interpolar(trajeto: Trajeto, kmh: float, intervalo: float, inicio_m: float = 0.0, repetir: bool = False) -> Iterator[Fix]:
    """One Fix per `intervalo` seconds at constant speed. Without `repetir` the last Fix is exactly the last point of the route."""
    vel = kmh / 3.6
    passo = vel * intervalo

    def em(d: float) -> Fix:
        (lat, lon), r = trajeto.posicao(d)
        return Fix(lat, lon, r, vel, d)

    d = inicio_m
    if repetir:
        while True:
            yield em(d % trajeto.total)
            d += passo
    while d < trajeto.total - 0.001:  # a millimetre of slack, so a step that lands on the end (give or take rounding) is not told twice
        yield em(d)
        d += passo
    yield em(trajeto.total)


# ---- NMEA -----------------------------------------------------------------------------------------------------------------------------

def nmea_checksum(corpo: str) -> str:
    """XOR of the characters between '$' and '*', as two hex digits."""
    c = 0
    for ch in corpo:
        c ^= ord(ch)
    return f"{c:02X}"


def _nmea_frase(corpo: str) -> str:
    return f"${corpo}*{nmea_checksum(corpo)}"


def _nmea_graus(valor: float, largura: int, positivo: str, negativo: str) -> tuple[str, str]:
    minutos = round(abs(valor) * 60.0, 4)  # rounded first, so that 59.99999' never prints as 60.0000'
    graus = int(minutos // 60)
    return f"{graus:0{largura}d}{minutos - graus * 60:07.4f}", positivo if valor >= 0 else negativo


def sentencas_nmea(fix: Fix, quando: datetime.datetime, altitude: float = 0.0) -> list[str]:
    """GPGGA (position, altitude) and GPRMC (position, speed in knots, course, date). `quando` in UTC."""
    hora = quando.strftime("%H%M%S") + f".{quando.microsecond // 10000:02d}"
    data = quando.strftime("%d%m%y")
    lat, ns = _nmea_graus(fix.lat, 2, "N", "S")
    lon, ew = _nmea_graus(fix.lon, 3, "E", "W")
    gga = f"GPGGA,{hora},{lat},{ns},{lon},{ew},1,08,0.9,{altitude:.1f},M,0.0,M,,"
    rmc = f"GPRMC,{hora},A,{lat},{ns},{lon},{ew},{fix.vel_ms * KNOTS_PER_MS:.1f},{fix.rumo:.1f},{data},,,A"
    return [_nmea_frase(gga), _nmea_frase(rmc)]


# ---- The commands ---------------------------------------------------------------------------------------------------------------------

def base_adb(adb: str, serial: Optional[str]) -> list[str]:
    return [adb] + (["-s", serial] if serial else [])


def cmd_emulador(base: list[str], fix: Fix, nmea: bool, altitude: Optional[float], quando: datetime.datetime) -> list[list[str]]:
    if nmea:
        return [base + ["emu", "geo", "nmea", s] for s in sentencas_nmea(fix, quando, altitude or 0.0)]
    # The emulator console wants LONGITUDE first, then latitude.
    geo = ["emu", "geo", "fix", f"{fix.lon:.6f}", f"{fix.lat:.6f}"]
    if altitude is not None:
        geo.append(f"{altitude:.1f}")
    return [base + geo]


# Syntax of `cmd location providers`, confirmed in the AOSP source (frameworks/base/services/core/java/com/android/server/location/
# LocationShellCommand.java, onHelp and the parsing of each command):
#   add-test-provider <PROVIDER> [--requiresNetwork] [--requiresSatellite] [--requiresCell] [--hasMonetaryCost] [--supportsAltitude]
#                     [--supportsSpeed] [--supportsBearing] [--powerRequirement <N>] [--accuracy <N>] [--extraAttributionTags <TAG>,...]
#   set-test-provider-enabled <PROVIDER> true|false
#   set-test-provider-location <PROVIDER> --location <LATITUDE>,<LONGITUDE> [--accuracy <ACCURACY>] [--time <TIME>]
#   remove-test-provider <PROVIDER>
# set-test-provider-location has NO speed, bearing or altitude option (only the capability flags of add-test-provider exist), so on the phone the
# course and speed are not sent: Google Maps works them out from the movement between two fixes. Latitude comes first here (not in `geo fix`).
def _prov(base: list[str], *resto: str) -> list[str]:
    return base + ["shell", "cmd", "location", "providers", *resto]


def cmd_celular_preparar(base: list[str]) -> list[list[str]]:
    return [_prov(base, "add-test-provider", PROVIDER), _prov(base, "set-test-provider-enabled", PROVIDER, "true")]


def cmd_celular_ponto(base: list[str], fix: Fix) -> list[str]:
    return _prov(base, "set-test-provider-location", PROVIDER, "--location", f"{fix.lat:.6f},{fix.lon:.6f}", "--accuracy", "5")


def cmd_celular_remover_provedor(base: list[str]) -> list[str]:
    return _prov(base, "remove-test-provider", PROVIDER)


def cmd_celular_appop_padrao(base: list[str]) -> list[str]:
    return base + ["shell", "appops", "set", "--uid", SHELL_UID, "MOCK_LOCATION", "default"]


class Executor:
    """Runs (or, in --seco, only prints) adb commands."""

    def __init__(self, seco: bool = False, saida: Callable[[str], None] = print):
        self.seco = seco
        self.saida = saida

    def rodar(self, cmd: list[str], tolerar: bool = False) -> None:
        if self.seco:
            self.saida(shlex.join(cmd))
            return
        try:
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=ADB_TIMEOUT_S)
        except FileNotFoundError:
            raise ErroAdb(f"adb não encontrado ({cmd[0]}); use --adb, a variável ADB ou ponha o adb no PATH") from None
        except subprocess.TimeoutExpired:
            if tolerar:
                return
            raise ErroAdb(f"adb não respondeu em {ADB_TIMEOUT_S} s") from None
        texto = ((r.stdout or "") + (r.stderr or "")).strip()
        falhou = r.returncode != 0 or texto.startswith("KO") or "Exception" in texto
        if falhou and not tolerar:
            dica = ""
            if "mock" in texto.lower():
                dica = f"\nFalta liberar uma vez: adb shell appops set --uid {SHELL_UID} MOCK_LOCATION allow"
            raise ErroAdb(f"adb falhou (código {r.returncode}): {texto[:300] or 'sem mensagem'}{dica}")


# ---- Running --------------------------------------------------------------------------------------------------------------------------

def limpar_celular(ex: Executor, base: list[str], appop: bool) -> None:
    ex.rodar(cmd_celular_remover_provedor(base), tolerar=True)
    if appop:
        ex.rodar(cmd_celular_appop_padrao(base), tolerar=True)


def percorrer(args: argparse.Namespace, pontos: list[Point], ex: Executor, base: list[str], saida: Callable[[str], None] = print,
              dormir: Callable[[float], None] = time.sleep, relogio: Callable[[], float] = time.monotonic,
              agora: Callable[[], datetime.datetime] = lambda: datetime.datetime.now(datetime.timezone.utc)) -> int:
    trajeto = Trajeto(pontos, args.repetir)
    inicio = resolver_inicio(args.inicio, trajeto)
    fixes = interpolar(trajeto, args.kmh, args.intervalo, inicio, args.repetir)
    espera = args.intervalo / args.acelerar
    cada = max(1, round(PROGRESS_EVERY_S / args.intervalo))
    celular = args.alvo == "celular"
    saida(f"Rota de {trajeto.total / 1000:.2f} km, {args.kmh:g} km/h, um ponto a cada {args.intervalo:g} s"
          + (f" (x{args.acelerar:g})" if args.acelerar != 1 else "") + (f", alvo {args.alvo}" + (" (NMEA)" if args.nmea else "")))
    try:
        if celular:
            ex.rodar(cmd_celular_remover_provedor(base), tolerar=True)  # one left over from an earlier run would make add-test-provider fail
            for c in cmd_celular_preparar(base):
                ex.rodar(c)
        proximo = relogio()
        n = 0
        for fix in fixes:
            if celular:
                ex.rodar(cmd_celular_ponto(base, fix))
            else:
                for c in cmd_emulador(base, fix, args.nmea, args.altitude, agora()):
                    ex.rodar(c)
            if n % cada == 0 or fix.dist_m >= trajeto.total:
                saida(f"{fix.dist_m / 1000:.2f}/{trajeto.total / 1000:.2f} km  {fix.vel_ms * 3.6:.0f} km/h")
            n += 1
            if not ex.seco:
                proximo += espera
                dormir(max(0.0, proximo - relogio()))
        saida("Fim da rota.")
        return 0
    except KeyboardInterrupt:
        saida("Interrompido.")
        return 130
    finally:
        if celular and not args.manter:
            limpar_celular(ex, base, appop=False)
            saida("Provedor de teste removido.")


def montar_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="GPS falso: percorre uma rota (GPX ou JSON) injetando a posição no emulador ou no celular.",
        epilog=f"Celular, uma vez por aparelho: adb shell appops set --uid {SHELL_UID} MOCK_LOCATION allow  (desfaz: --alvo celular --limpar). "
               "O Google Maps precisa estar navegando para o REau receber a rota.")
    p.add_argument("rota", nargs="?", help="arquivo .gpx (trkpt ou rtept) ou .json [[lat, lon], ...]")
    p.add_argument("--alvo", choices=("emulador", "celular"), default="emulador", help="para onde vai a posição (padrão: emulador)")
    p.add_argument("--kmh", type=float, default=40.0, help="velocidade constante em km/h (padrão 40)")
    p.add_argument("--intervalo", type=float, default=1.0, help="segundos entre dois pontos (padrão 1)")
    p.add_argument("--inicio", help="começa no meio: metros (1500 ou 1500m) ou índice de ponto da rota (p12)")
    p.add_argument("--repetir", action="store_true", help="dá voltas até Ctrl+C")
    p.add_argument("--acelerar", type=float, default=1.0, metavar="N", help="anda N vezes mais rápido (mesmo passo por ponto, 1/N da espera)")
    p.add_argument("--nmea", action="store_true", help="emulador: manda GPGGA+GPRMC (adb emu geo nmea) em vez de geo fix")
    p.add_argument("--altitude", type=float, help="altitude em metros (emulador)")
    p.add_argument("--serial", help="serial do adb (padrão: ANDROID_SERIAL; emulador: emulator-5554)")
    p.add_argument("--adb", help="caminho do adb (padrão: variável ADB, depois o adb do PATH)")
    p.add_argument("--seco", action="store_true", help="só imprime os comandos; não chama o adb nem espera")
    p.add_argument("--limpar", action="store_true", help="celular: remove o provedor de teste e devolve o appop MOCK_LOCATION a 'default'")
    p.add_argument("--manter", action="store_true", help="celular: não remove o provedor de teste ao terminar")
    return p


def achar_adb(arg: Optional[str], seco: bool) -> str:
    adb = arg or os.environ.get("ADB") or shutil.which("adb")
    if adb:
        return adb
    if seco:
        return "adb"
    raise ErroAdb("adb não encontrado: use --adb, a variável ADB ou ponha o adb no PATH")


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = montar_parser()
    args = parser.parse_args(argv)
    if args.kmh <= 0 or args.intervalo <= 0 or args.acelerar <= 0:
        parser.error("--kmh, --intervalo e --acelerar precisam ser maiores que zero")
    if args.nmea and args.alvo != "emulador":
        parser.error("--nmea só vale com --alvo emulador")
    if args.seco and args.repetir:
        parser.error("--seco com --repetir nunca terminaria")
    if not args.limpar and not args.rota:
        parser.error("falta a rota (arquivo .gpx ou .json)")
    serial = args.serial or os.environ.get("ANDROID_SERIAL") or (DEFAULT_EMU_SERIAL if args.alvo == "emulador" else None)
    if args.alvo == "emulador" and serial and not serial.startswith("emulator-"):
        parser.error(f"--alvo emulador: o serial {serial!r} não é de emulador (emulator-NNNN); para um celular use --alvo celular")
    try:
        base = base_adb(achar_adb(args.adb, args.seco), serial)
        ex = Executor(args.seco)
        if args.limpar:
            if args.alvo != "celular":
                print("Nada a limpar no emulador: a posição some quando ele reinicia.")
                return 0
            limpar_celular(ex, base, appop=True)
            print("Provedor de teste removido e MOCK_LOCATION de volta a 'default'.")
            return 0
        return percorrer(args, ler_rota(args.rota), ex, base)
    except RotaInvalida as e:
        print(f"rota_falsa: {e}", file=sys.stderr)
        return 2
    except ErroAdb as e:
        print(f"rota_falsa: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
