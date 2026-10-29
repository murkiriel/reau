"""Lê registros do REau puxados do celular e mostra a linha do tempo resumida, acusando os problemas conhecidos.

    python -B scripts\\registro\\ler_registro.py <arquivo ou pasta> [...]

Lê os arquivos `ride-*.log` (uma pasta inteira, ou arquivos soltos), em ordem de nome, e escreve:

- a linha do tempo de cada projeção: ligou, Android Auto conectado, painel no Wi-Fi, transmitindo, primeiro quadro
  confirmado, quedas, vigias, marcas do piloto, travamentos, estado térmico e bateria, desligou, o resumo, e a pior
  leitura do Android Auto presa no decodificador (o `readHeld` das linhas aa-stats);
- os problemas conhecidos: transmitindo sem confirmação do primeiro quadro, vigias, buracos nas linhas de 5 s (ou no
  resumo), quedas seguidas (duas em menos de 60 s), temperatura subindo, o app travou, o Android Auto calado (15 s
  sem mandar nada) e o Android Auto que aceita e não responde (três negociações seguidas sem resposta).

Uma queda de Wi-Fi que estava firme até sumir de uma vez (sinal bom e parado nas últimas amostras) sai na linha do tempo como
"painel desligou (moto desligada?)" e não vira problema. As linhas de uma mesma queda são uma queda só; buraco nas linhas de 5 s só com o painel no ar; o calor
da bateria só conta sem carregador e com a tela ligada; e no fim de cada projeção a linha `vídeo:` (taxa, quadro, quadros-chave,
redução, trocas de perfil).

O Wi-Fi "antes da queda" é o do painel (o último `bike=` das olhadas `[device]`, até 90 s antes); sem ele, o `[link]` do celular, dito como tal.
Um laço de espera pelo painel (pedidos de rede sem um `connected` no meio) sai com a duração e o número de pedidos, e vira problema a
partir de 3 min. Se uma pasta (ou a `diag`, e a de cima) tem `logcat-sistema*.txt`, entram nos problemas o Android Auto que travou
(`FATAL EXCEPTION` do `gearhead`, com o intervalo desde o `byebye` do REau) e o app com `Davey!` ou `Skipped` de 100 quadros ou mais.

Na edição Completa, por Ligar, o bloco `servidor do AA:` conta a caminhada que liga o servidor do Android Auto (toque, início
da caminhada, clique em Iniciar, Voltar, fim, primeira conexão, em ms) e acusa a causa: porta fechada 5 s depois do clique, servidor
que só atendeu quando a tela do Android Auto abriu, menu lido pela metade (Android Auto frio?). Sem linhas `aa-server` (edição Lite) não diz nada.
"""
import re
import sys
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from pathlib import Path

import logcat_sistema

# A warning or an error has its level at the start of its text ("W: ", "E: "); the reader keeps it apart.
NIVEL = re.compile(r"^([WE]): (.*)$")
LINHA = re.compile(r"^(\d\d:\d\d:\d\d\.\d{3})\s+(?:\+\s*\d+ms\s+)?(?:\[([\w-]+)\]\s+)?(.*)$")
LIGOU = ("---- projeção ligada", "==== session start: projection")
DESLIGOU = ("---- projeção desligada", "==== session end: projection")
TERMICO = ["none", "light", "moderate", "severe", "critical", "emergency", "shutdown"]
# `dark=2/3m02s (max 1m08s)` in the resumo: the interruptions of the picture on the dash (since 20/10/2026), the longest listed above this.
DARK = re.compile(r"dark=(\d+)/(\S+) \(max (\S+)\)")
DARK_PROBLEMA_S = 10
# The dash's Wi-Fi that comes back within this of going is a restart of its access point, not the bike switched off.
VOLTA_S = 120.0
CANAL_ANTES = re.compile(r"after .+? on (\d+) MHz")
CANAL_DEPOIS = re.compile(r"back on another channel \((\d+) → (\d+) MHz\)|back on the same channel \((\d+) MHz\)")
CANAL_DEVICE = re.compile(r"bike=-?\d+dBm/(\d+)MHz")
# The dash's own Wi-Fi in the phone's [device] look: `bike=-74dBm/2427MHz/58Mbps` (`bike=none` when it is not joined).
BIKE_DEVICE = re.compile(r"bike=(-?\d+)dBm/(\d+)MHz(?:/(\d+)Mbps)?")
BIKE_ATE_S = 90.0  # the look comes every 30 s: older than this is no longer the Wi-Fi "before the fall"
ARQUIVO_DIA = re.compile(r"ride-(\d{4})(\d\d)(\d\d)-")
# The wait for the dash: requests for its Wi-Fi in a row with no `connected` between them.
PEDIDO_DE_REDE = re.compile(r"requestNetwork '.*?' .*?request #(\d+)")
SEGUNDOS_NO_DIA = 86400
CAMINHADA_FIM = re.compile(r"\b(start|stop|visit) walk ended: \w+ in (\d+) ms")
ESPERA_NA_LINHA_S = 60.0
ESPERA_PROBLEMA_S = 180.0
# A rise of this many degrees, or this temperature, is heat worth telling (a phone in a hand, in the sun, is warm already).
SOBE_GRAUS = 8.0
QUENTE_GRAUS = 38.0
CHAVE_CHEGOU = re.compile(r"key arrived ([\d.]+) s after the first greeting, after (\d+) resends?")
AVISO_DE_10S = ("dash DECODED first IDR", "dash notice 09 06 55", "dash sent 09 06 55 while")
# Android Auto closes its link together with the phone's screen going off or on (EOF, 12:01:07 and 12:13:36 on 20/10/2026): not a fall.
TELA_JUNTO_S = 3.0
TELA_EVENTOS = ("screen on", "screen off", "screen unlocked")
SPS = "H.264 SPS parsed"
BURACO_5S = 6.0  # a linha de 5 s chegando mais de 1 s atrasada
QUEDAS_SEGUIDAS_S = 60.0
QUEDA_JUNTA_S = 10.0  # the signs of one fall (Wi-Fi lost, dash silent, session ended) come within this of each other
DESLIGAMENTO_S = 3.0
NEGOCIACOES_SEM_RESPOSTA = 3
NEGOCIACAO_OK = "Handshake successful"
NEGOCIACAO_FALHOU = "AaReceiver: the handshake failed"
CALADO = "WiFi read timeout (15s)"
BYE = "AapTransport stopping and sending byebye"
BYE_S = 2.0
# The detailed log's lines (~98% of a file with the switch on); nothing here reads them, so they are skipped.
DETALHE = "  [detail] "
# Sinais de que a transmissão acabou ou recomeçou entre duas linhas de 5 s: o intervalo não é buraco.
SEM_VIDEO = ("session fail", "video loop stop", "→ STREAMING")

# (o que procurar no texto, o que dizer na linha do tempo)
EVENTOS = [
    # Conectado só com a negociação respondida; discar e o servidor aceitar não basta.
    (NEGOCIACAO_OK, "Android Auto conectado"),
    (NEGOCIACAO_FALHOU, "Android Auto não respondeu à negociação"),
    (CALADO, "Android Auto calado (15 s sem mandar nada)"),
    ("AaReceiver: session ended (clean=false", "Android Auto caiu"),
    ("→ STREAMING", "transmitindo"),
    ("dash DECODED first IDR", "primeiro quadro confirmado pelo painel"),
    # Since 20/10/2026 the log says what the notice is: the dash's own 10 s mark, not proof of a decoded frame.
    ("dash notice 09 06 55", "aviso de 10 s do painel (09 06 55)"),
    ("link lost — reconnecting", "Wi-Fi da moto caiu"),
    ("bike network lost", "Wi-Fi da moto perdido"),
    # O Wi-Fi no instante de um vigia do painel (sinal, velocidades, banda) e o último minuto, para dizer
    # Wi-Fi fraco de painel calado. A linha é longa de propósito: o minuto de sinal é o que interessa.
    ("link at failure:", "Wi-Fi na falha"),
    # The entry: how long the dash had been on at its first heartbeat, and what its key cost.
    ("dash heartbeat age", "auth: idade do painel"),
    ("key arrived", "auth: chave"),
    ("watchdog:", "vigia"),
    ("Decoder restart requested", "vigia do decodificador"),
    ("the app crashed", "o app travou"),
]
# "RX watchdog:" with the colon: the dash's "no 09 06 55 after 15s" notice only mentions the watchdog in passing.
QUEDAS = ("link lost — reconnecting", "RX watchdog:", "bike network lost", "AaReceiver: session ended (clean=false")
# A fall whose Wi-Fi was steady until it went (the dash's own Wi-Fi vanished at once) is the bike being switched off.
HISTORICO_RSSI = re.compile(r"last 60 s, every 5 s: rssi ([^|]*)")
RSSI_FRACO_DBM = -80
RSSI_VARIACAO_DB = 12
AMOSTRAS_RSSI = 4
# The server's start walk (edição Completa): the lines of the tags aa-server and AaReceiver that tell it.
SERVIDOR_TOQUE = "the cover up at the rider's Ligar"
SERVIDOR_INICIO = "start walk begins"
SERVIDOR_CLIQUE = "clicked start"
SERVIDOR_VOLTAR = re.compile(r"\+\d+ ms back$")
SERVIDOR_FIM = "start walk ended: "
SERVIDOR_PORTA_FECHADA = "port still closed after 5 s"
SERVIDOR_ABRIU = ("rider opened Android Auto settings", "opened the settings")
SERVIDOR_CONECTOU = "AaReceiver: connected at last after"
# The visit: the settings in front, nothing touched; the next walk, if the port stayed closed, is the one more start.
SERVIDOR_VISITA_OK = "the visit brought the server up"
SERVIDOR_VISITA_NAO = "the visit did not bring the port up"
SERVIDOR_MENU = re.compile(r"\(menu: ([^)]*)\)")
SERVIDOR_ATENDEU_APOS_S = 2.0  # connected this soon after Android Auto's screen opened, with no click between: it answered because of the screen
SERVIDOR_TOQUE_PERTO_S = 5.0  # the projection mark counts as the tap when the walk begins this soon after it
SERVIDOR_MENU_PELA_METADE = 2  # a menu of at most this many items is Android Auto still being built


@dataclass
class Linha:
    arquivo: str
    hora: str
    etiqueta: str
    texto: str
    nivel: str = ""  # "W" or "E" when the line says so at the start of its text, else empty
    dia: str = ""  # the day (YYYY-MM-DD) from the file's name, one more when the clock passes midnight inside it; empty without a dated name

    @property
    def segundos(self):
        h, m, s = self.hora.split(":")
        return int(h) * 3600 + int(m) * 60 + float(s)


def ler(caminhos):
    """As linhas de todos os `ride-*.log` dos caminhos (pastas ou arquivos), em ordem de nome."""
    arquivos = []
    for c in map(Path, caminhos):
        if c.is_dir():
            arquivos += sorted(c.glob("ride-*.log"))
        elif not c.name.startswith("logcat"):
            arquivos.append(c)  # the system logcat is read apart (logcat_sistema)
    linhas = []
    for a in arquivos:
        dia, anterior = _dia_do_arquivo(a), -1.0
        for bruta in a.read_text(encoding="utf-8", errors="replace").splitlines():
            if DETALHE in bruta:
                continue
            m = LINHA.match(bruta)
            if m:
                nivel = NIVEL.match(m.group(3))
                texto, letra = (nivel.group(2), nivel.group(1)) if nivel else (m.group(3), "")
                x = Linha(a.name, m.group(1), m.group(2) or "", texto, letra, dia.isoformat() if dia else "")
                if dia and x.segundos < anterior - 3600:
                    dia += timedelta(days=1)  # the clock went back by hours: it is the next day
                    x.dia = dia.isoformat()
                anterior = x.segundos
                linhas.append(x)
    return linhas


def _dia_do_arquivo(arquivo):
    m = ARQUIVO_DIA.match(arquivo.name)
    try:
        return date(int(m.group(1)), int(m.group(2)), int(m.group(3))) if m else None
    except ValueError:
        return None


def instante(x):
    """O instante (datetime) de uma linha, ou None se o nome do arquivo não traz o dia."""
    if not x.dia:
        return None
    h, m, s = x.hora.split(":")
    return datetime.fromisoformat(x.dia) + timedelta(hours=int(h), minutes=int(m), seconds=float(s))


def do_sistema(caminhos, linhas):
    """Os achados do logcat do sistema (`logcat-sistema*.txt` nos caminhos): o Android Auto que travou e o app que perdeu quadros.
    O logcat não traz o ano; vem do dia dos registros."""
    entradas = logcat_sistema.ler(caminhos)
    do_app = logcat_sistema.ler(caminhos, so_do_app=True)
    if not entradas and not do_app:
        return []
    byebyes = [t for x in linhas if BYE in x.texto for t in [instante(x)] if t]
    dias = [x.dia for x in linhas if x.dia]
    ano = int(dias[0][:4]) if dias else datetime.now().year
    # Only the days of the registros read: the logcat files of a whole folder cover many days, and the report is about these.
    if dias:
        dos_dias = {(int(d[5:7]), int(d[8:10])) for d in dias}
        entradas = [e for e in entradas if (e.mes, e.dia) in dos_dias]
        do_app = [e for e in do_app if (e.mes, e.dia) in dos_dias]
    saida = logcat_sistema.travadas_do_android_auto(entradas, byebyes, ano) if entradas else []
    if do_app:
        # The app's own logcat has the stalls one by one, with the walk they fell in; the system's would only repeat them as a total.
        por_evento = logcat_sistema.travadas_do_app_por_evento(do_app, ano, _caminhadas(linhas))
        # An app logcat with no stall in it (small, or rolled): the system's total, if there is one, still says what it saw.
        return saida + (por_evento or logcat_sistema.travadas_do_app(entradas))
    return saida + logcat_sistema.travadas_do_app(entradas)


def _caminhadas(linhas):
    """As caminhadas do servidor do Android Auto do registro: (modo, início, fim), do `<modo> walk ended: … in N ms`."""
    saida = []
    for x in linhas:
        m = CAMINHADA_FIM.search(x.texto)
        fim = instante(x) if m else None
        if fim:
            saida.append((m.group(1), fim - timedelta(milliseconds=int(m.group(2))), fim))
    return saida


def projecoes(linhas):
    """As linhas de cada projeção, da marca de ligar à de desligar (ou ao fim do arquivo)."""
    blocos, atual = [], None
    for x in linhas:
        if x.texto.startswith(LIGOU):
            atual = [x]
            blocos.append(atual)
        elif atual is not None:
            atual.append(x)
            if x.texto.startswith(DESLIGOU):
                atual = None
    return blocos


def _do_desligamento(x, bloco):
    """O fim da sessão do Android Auto que o próprio desligamento provoca (menos de 3 s antes da marca): não é queda."""
    fim = bloco[-1]
    return ("AaReceiver: session ended" in x.texto and fim.texto.startswith(DESLIGOU)
            and fim.segundos - x.segundos < DESLIGAMENTO_S)


def _causa_da_queda(x):
    """O nome curto do que uma linha de queda diz."""
    if "link lost — reconnecting" in x.texto or "bike network lost" in x.texto:
        return "Wi-Fi da moto"
    if "RX watchdog" in x.texto:
        return "painel calado"
    return "Android Auto caiu"


def _grupos_de_queda(bloco):
    """As linhas de queda de uma mesma queda (o Wi-Fi caiu, a rede da moto se perdeu e, até 10 s depois,
    o vigia do painel) são uma queda só; cada grupo é a lista de linhas, a primeira marcando a hora."""
    grupos = []
    for x in bloco:
        if not (any(q in x.texto for q in QUEDAS) and not _nao_e_queda(x, bloco)):
            continue
        if grupos and x.segundos - grupos[-1][-1].segundos <= QUEDA_JUNTA_S:
            grupos[-1].append(x)
        else:
            grupos.append([x])
    return grupos


def _resumo_da_queda(grupo, bloco):
    """A linha de uma queda: a causa de cada sinal e quanto depois veio; mais o Wi-Fi de antes, se o registro o tem."""
    causas = []
    for x in grupo:
        nome = _causa_da_queda(x)
        if nome not in [c for c, _ in causas]:
            causas.append((nome, x.segundos - grupo[0].segundos))
    partes = [nome if atraso < 0.5 else f"{nome} {atraso:.0f} s depois" for nome, atraso in causas]
    volta = _voltou(grupo, bloco)
    if volta:
        # Another channel is the access point restarting; the same one, or none known, only says it came back.
        reiniciou = "→" in volta and "mesmo canal" not in volta
        saida = [f"{grupo[0].hora}  Wi-Fi do painel {'reiniciou' if reiniciou else 'caiu e voltou'} ({volta})"]
    elif _painel_desligou(grupo, bloco):
        saida = [f"{grupo[0].hora}  painel desligou (moto desligada?): o Wi-Fi estava firme e sumiu de uma vez ({', '.join(partes)})"]
    else:
        saida = [f"{grupo[0].hora}  queda ({', '.join(partes)})"]
    painel = _wifi_do_painel(grupo, bloco)
    if painel:
        saida.append(f"{grupo[0].hora}  Wi-Fi do painel antes da queda: {painel}")
    else:
        # The periodic [link] look is the phone's own active Wi-Fi (the home one once the dash is gone): said as such.
        antes = _wifi_antes(grupo, bloco)
        if antes:
            saida.append(f"{grupo[0].hora}  Wi-Fi do celular antes da queda (o do painel não está no registro): {antes[-1].texto.split(' | ')[0]}")
    return saida


def _wifi_do_painel(grupo, bloco):
    """`-74 dBm, 2427 MHz, 58 Mbps (12 s antes)` do último `bike=` das olhadas `[device]` até 90 s antes da queda; vazio sem ele."""
    queda = grupo[0]
    for x in reversed(bloco[:bloco.index(queda)]):
        m = BIKE_DEVICE.search(x.texto) if x.texto.startswith("device ") else None
        if m and queda.segundos - x.segundos <= BIKE_ATE_S:
            mbps = f", {m.group(3)} Mbps" if m.group(3) else ""
            return f"{m.group(1)} dBm, {m.group(2)} MHz{mbps} ({queda.segundos - x.segundos:.0f} s antes)"
    return ""


def _voltou(grupo, bloco):
    """`voltou em 22 s, 2447 → 2427 MHz` se o Wi-Fi do painel voltou em até 2 min da queda (o ponto de acesso do painel reiniciou);
    vazio se não voltou, ou se a queda não é de Wi-Fi. O canal vem do que o registro tem: a linha da perda, o último `bike=` antes,
    a linha da volta ou o primeiro `bike=` depois."""
    # The Wi-Fi's own loss only: the dash-silent watchdog's `… → link lost` can come with the Wi-Fi still up.
    if not any("link lost — reconnecting" in x.texto or "bike network lost" in x.texto for x in grupo):
        return ""
    queda = grupo[0]
    depois = [x for x in bloco if x.segundos > queda.segundos]
    volta = next((x for x in depois if "bike network available" in x.texto or (x.etiqueta == "wifi" and x.texto.startswith("connected "))), None)
    if volta is None or volta.segundos - queda.segundos > VOLTA_S:
        return ""
    antes = next((m.group(1) for x in grupo for m in [CANAL_ANTES.search(x.texto)] if m), None)
    if antes is None:
        antes = next((m.group(1) for x in reversed(bloco[:bloco.index(queda)]) for m in [CANAL_DEVICE.search(x.texto)] if m), None)
    agora = None
    for x in depois:
        m = CANAL_DEPOIS.search(x.texto)
        # The app writes it as the network comes (before `connected`), so from the loss up to 5 s after the return.
        if m and x.segundos - volta.segundos <= 5:
            agora = m.group(2) or m.group(3)
            break
    if agora is None:
        agora = next((m.group(1) for x in depois if x.segundos >= volta.segundos for m in [CANAL_DEVICE.search(x.texto)] if m), None)
    tempo = f"voltou em {volta.segundos - queda.segundos:.0f} s"
    if antes and agora:
        return f"{tempo}, {antes} → {agora} MHz" if antes != agora else f"{tempo}, mesmo canal {agora} MHz"
    return tempo


def _wifi_antes(grupo, bloco):
    """As linhas `[link]` dos 70 s antes de uma queda (a última é a do Wi-Fi de antes)."""
    return [y for y in bloco if y.etiqueta == "link" and not y.texto.startswith("link at failure")
            and 0 <= grupo[0].segundos - y.segundos <= 70]


def _painel_desligou(grupo, bloco):
    """A queda de um Wi-Fi que estava firme (sinal bom e quase parado nas últimas amostras) e sumiu de uma vez: é o
    painel desligando junto com a moto, não um problema de rádio. Sem o histórico do sinal no registro, não se afirma."""
    if not any("link lost — reconnecting" in x.texto or "bike network lost" in x.texto for x in grupo):
        return False
    antes = _wifi_antes(grupo, bloco)
    historico = HISTORICO_RSSI.search(antes[-1].texto) if antes else None
    if not historico:
        return False
    amostras = [int(v) for v in historico.group(1).split() if re.fullmatch(r"-?\d+", v)][-AMOSTRAS_RSSI:]
    return bool(amostras) and min(amostras) >= RSSI_FRACO_DBM and max(amostras) - min(amostras) <= RSSI_VARIACAO_DB


def _fim_pedido(x, bloco):
    """O fim da sessão que o próprio REau pediu com o bye (desligar, ou uma sessão nova para os ajustes) até 2 s antes:
    não é queda. Versões antigas do app registravam esse fim como `clean=false` mesmo com o bye respondido."""
    return "AaReceiver: session ended" in x.texto and any(
        BYE in y.texto and 0 <= x.segundos - y.segundos < BYE_S for y in bloco)


def _com_a_tela(x, bloco):
    """O fim da sessão do Android Auto com um evento de tela (`[system] screen on|off|unlocked`, ou a mudança de tela na olhada do
    celular) a até 3 s, antes ou depois: o Android Auto fecha junto com a tela, e o REau reconecta sozinho."""
    return "AaReceiver: session ended" in x.texto and any(
        abs(y.segundos - x.segundos) <= TELA_JUNTO_S
        and ((y.etiqueta == "system" and y.texto in TELA_EVENTOS) or (y.texto.startswith("device ") and "[change: screen]" in y.texto))
        for y in bloco)


def _nao_e_queda(x, bloco):
    return _do_desligamento(x, bloco) or _fim_pedido(x, bloco) or _com_a_tela(x, bloco)


def _fechou_com_a_tela(x, bloco):
    """`Android Auto fechou junto com a tela (reconectou em 1,2 s; imagem nova em 2,8 s)`."""
    depois = bloco[bloco.index(x) + 1:]
    reconectou = next((y for y in depois if "connected to the head unit server" in y.texto), None)
    imagem = next((y for y in depois if SPS in y.texto), None)
    partes = []
    if reconectou:
        partes.append(f"reconectou em {(reconectou.segundos - x.segundos):.1f} s".replace(".", ","))
    if imagem:
        partes.append(f"imagem nova em {(imagem.segundos - x.segundos):.1f} s".replace(".", ","))
    return "Android Auto fechou junto com a tela" + (f" ({'; '.join(partes)})" if partes else "")


def _device(texto, campo):
    m = re.search(rf"\b{campo}=([^\s%C]+)", texto)
    return m.group(1) if m else None


def linha_do_tempo(linhas):
    saida = []
    for bloco in projecoes(linhas):
        bateria = [b for x in bloco if x.texto.startswith("device ") for b in [_device(x.texto, "battery")] if b and b != "?"]
        termico_antes = None
        quedas = {id(g[0]): g for g in _grupos_de_queda(bloco)}
        esperas = {id(e[0]): e for e in _esperas(bloco) if e[1] >= ESPERA_NA_LINHA_S}
        for x in bloco:
            t = x.texto
            if _com_a_tela(x, bloco) and not _do_desligamento(x, bloco) and not _fim_pedido(x, bloco):
                saida.append(f"{x.hora}  {_fechou_com_a_tela(x, bloco)}")
                continue
            if id(x) in quedas:
                saida += _resumo_da_queda(quedas[id(x)], bloco)
            if id(x) in esperas:
                saida.append(f"{x.hora}  {_texto_da_espera(esperas[id(x)])}")
            if t.startswith(LIGOU):
                saida.append(f"{x.hora}  projeção ligada ({x.arquivo})")
                historia, _ = _servidor_aa(bloco)
                if historia:
                    saida.append(f"{x.hora}  {historia}")
            elif t.startswith(DESLIGOU):
                if bateria:
                    saida.append(f"{x.hora}  bateria {bateria[0]}% → {bateria[-1]}%")
                presa = _leitura_presa(bloco)
                if presa:
                    saida.append(f"{x.hora}  {presa}")
                video = _video(bloco)
                if video:
                    saida.append(f"{x.hora}  {video}")
                saida.append(f"{x.hora}  projeção desligada")
            elif x.etiqueta == "mark":
                saida.append(f"{x.hora}  marca do piloto {t.split()[-1]}")
            elif x.etiqueta == "settings" and t.startswith("settings in use: "):
                saida.append(f"{x.hora}  {t}")  # the settings the ride was made with, at the start of each projection
            elif x.etiqueta == "wifi" and t.startswith("connected "):
                saida.append(f"{x.hora}  painel no Wi-Fi")
            elif t.startswith("resumo "):
                saida.append(f"{x.hora}  {t}")
            elif t.startswith("device "):
                termico = _device(t, "thermal")
                if termico_antes is not None and termico != termico_antes:
                    saida.append(f"{x.hora}  estado térmico {termico_antes} → {termico}")
                termico_antes = termico
            elif not _nao_e_queda(x, bloco):
                for procura, diz in EVENTOS:
                    if procura in t:
                        if diz.startswith("Wi-Fi na falha"):
                            saida.append(f"{x.hora}  {diz}: {t[len(procura):].strip()}")
                        else:
                            saida.append(f"{x.hora}  {diz}: {t[:110]}" if diz.startswith(("vigia", "o app", "auth:")) else f"{x.hora}  {diz}")
                        break
    return saida



READ_HELD = re.compile(r"aa-stats ([\d.]+)s: .* readHeld=([\d.]+)/([\d.]+)ms")


def _leitura_presa(bloco):
    """O pior tempo que a leitura do Android Auto ficou presa entregando quadros ao decodificador, numa janela
    das linhas aa-stats e numa vez só; None quando o registro é de antes do campo."""
    janelas = [(x, m) for x in bloco for m in [READ_HELD.search(x.texto)] if m]
    if not janelas:
        return None
    pior_janela = max(janelas, key=lambda j: float(j[1].group(2)))
    pior_vez = max(janelas, key=lambda j: float(j[1].group(3)))
    return (f"leitura do Android Auto presa no decodificador: pior janela {float(pior_janela[1].group(2)):.1f} ms em "
            f"{float(pior_janela[1].group(1)):g} s ({pior_janela[0].hora}), pior vez {float(pior_vez[1].group(3)):.1f} ms "
            f"({pior_vez[0].hora})")


VIDEO_5S = re.compile(r"kbps=(\d+) .*?frame=(\d+)B")
VIDEO_COMPLETA = re.compile(r"full=(true|false)")
VIDEO_IDR = re.compile(r"idr=(\d+)\(max (\d+)B/(\d+)pkt\)")
VIDEO_REDUCAO = re.compile(r"reduction (\w+)")
VIDEO_DESCARTADOS = re.compile(r"(\d+) packets discarded")
VIDEO_ESPERA_TICK = re.compile(r"tickwait=(\d+)/(\d+)/(\d+)ms")
VIDEO_AVISO = re.compile(r"notice 09 06 55 (#\d+), ([\d,.]+) s into")


def _video(bloco):
    """A qualidade do vídeo de uma projeção, só com o que o registro traz: a taxa média em movimento e a pior janela,
    o quadro médio, os quadros-chave (`idr=`, quando a linha os traz), a redução da imagem, as trocas de perfil de
    navegação, o perfil 2 que limitou o bitrate, os pacotes descartados do início e o primeiro aviso 09 06 55. None sem nenhuma linha de 5 s."""
    janelas = [x for x in bloco if x.texto.startswith("video fps target=")]
    taxas = []
    for x in janelas:
        m = VIDEO_5S.search(x.texto)
        if m:
            cheia = VIDEO_COMPLETA.search(x.texto)
            taxas.append((int(m.group(1)), int(m.group(2)), bool(cheia and cheia.group(1) == "true")))
    if not taxas:
        return None
    em_movimento = [k for k, _, cheia in taxas if cheia] or [k for k, _, _ in taxas]
    partes = [f"kbps médio {sum(em_movimento) / len(em_movimento):.0f} em movimento (pior janela {max(k for k, _, _ in taxas)})",
              f"quadro médio {sum(q for _, q, _ in taxas) / len(taxas):.0f} B"]
    idr = [m for x in janelas for m in [VIDEO_IDR.search(x.texto)] if m]
    if idr:
        partes.append(f"quadros-chave {sum(int(m.group(1)) for m in idr)} (o maior {max(int(m.group(2)) for m in idr)} B "
                      f"em {max(int(m.group(3)) for m in idr)} pacotes)")
    reducao = [m.group(1) for x in bloco for m in [VIDEO_REDUCAO.search(x.texto)] if m and x.texto.startswith("video loop start")]
    if reducao:
        partes.append(f"redução {reducao[-1]}")
    trocas = sum(1 for x in bloco if x.texto.startswith("nav profile:"))
    if trocas:
        partes.append(f"{trocas} trocas de perfil")
    if any("profile 2 measured" in x.texto for x in bloco):
        partes.append("perfil 2 limitou o bitrate")
    descartados = sum(int(m.group(1)) for x in bloco for m in [VIDEO_DESCARTADOS.search(x.texto)] if m)
    if descartados:
        partes.append(f"{descartados} pacotes descartados")
    espera = [m for x in janelas for m in [VIDEO_ESPERA_TICK.search(x.texto)] if m]
    if espera:
        # The wait of a new picture for the compositor's tick: mean of the windows' means, the worst 95th and the worst.
        partes.append(f"espera pelo tick média {sum(int(m.group(1)) for m in espera) / len(espera):.0f} ms "
                      f"(pior p95 {max(int(m.group(2)) for m in espera)}, máx {max(int(m.group(3)) for m in espera)})")
    aviso = next((m for x in bloco for m in [VIDEO_AVISO.search(x.texto)] if m), None)
    if aviso:
        partes.append(f"aviso 09 06 55 {aviso.group(1)} aos {aviso.group(2)} s")
    return "vídeo: " + ", ".join(partes)


def problemas(linhas):
    saida = []
    for bloco in projecoes(linhas):
        inicio = f"{bloco[0].hora} ({bloco[0].arquivo})"
        textos = [x.texto for x in bloco]
        # The dash's own 10 s mark (09 06 55) counts in any state: it comes with no video at all (20/10/2026), so it is no first frame
        # and its absence is only the stream being shorter than 10 s.
        if any("→ STREAMING" in t for t in textos) and not any(m in t for t in textos for m in AVISO_DE_10S):
            saida.append(f"projeção de {inicio}: transmitindo sem confirmação do primeiro quadro pelo painel")
        desligou = {id(x) for g in _grupos_de_queda(bloco) if _painel_desligou(g, bloco) for x in g}
        for x in bloco:
            if id(x) in desligou:
                continue  # the dash going off with the bike: the watchdog is its echo, not a problem
            if "watchdog:" in x.texto or "Decoder restart requested" in x.texto:
                saida.append(f"{x.hora}: vigia — {x.texto[:120]}")
            if "the app crashed" in x.texto:
                saida.append(f"{x.hora}: o app travou — {x.texto[:120]}")
            if "Auth timed out" in x.texto and x.nivel == "E":
                saida.append(f"{x.hora}: autenticação do painel falhou — {x.texto[x.texto.index('Auth timed out'):][:110]}")
            reenvios = CHAVE_CHEGOU.search(x.texto)
            if reenvios and int(reenvios.group(2)) >= 2:
                saida.append(f"{x.hora}: a chave do painel só veio depois de {reenvios.group(2)} reenvios da saudação ({reenvios.group(1)} s)")
            if "unavailable after" in x.texto and "seenInScan=true" in x.texto:
                saida.append(f"{x.hora}: pedido de rede expirou com o painel na varredura (seenInScan=true)")
        saida += [f"{e[0].hora}: laço de espera pelo painel — {e[2]} pedidos de rede em {_duracao_texto(e[1])}"
                  for e in _esperas(bloco) if e[1] >= ESPERA_PROBLEMA_S]
        saida += _sem_imagem(bloco) + _buracos(bloco) + _quedas(bloco) + _temperatura(bloco) + _sem_resposta(bloco)
        saida += [f"projeção de {inicio}: servidor do AA — {causa}" for causa in _servidor_aa(bloco)[1]]
    return saida


def _sem_imagem(bloco):
    """Cada vez que a imagem do painel parou por mais de 10 s e voltou, calculada de `video loop stop` ao próximo `→ STREAMING` quando o
    resumo não traz o `dark=` (registros antigos). Com o `dark=`, é ele que conta (ver `_video`)."""
    if any(x.texto.startswith("resumo ") and DARK.search(x.texto) for x in bloco):
        return []
    saida, parou = [], None
    for x in bloco:
        if "video loop stop" in x.texto:
            parou = x
        elif "→ STREAMING" in x.texto and parou is not None:
            if x.segundos - parou.segundos > DARK_PROBLEMA_S:
                saida.append(f"{x.hora}: painel sem imagem — {x.segundos - parou.segundos:.0f} s entre o vídeo parar ({parou.hora}) e voltar")
            parou = None
    return saida


def _esperas(bloco):
    """Os laços de espera pelo painel: pedidos do Wi-Fi dele (`requestNetwork`) um atrás do outro, sem um `connected` (ou `using already-connected WiFi`) no meio.
    Cada um é (primeira linha, duração em s, quantos pedidos); a duração vai do primeiro pedido ao `connected` (ou ao último pedido,
    se a projeção acabou esperando)."""
    saida, pedidos = [], []

    def fecha(fim):
        if pedidos:
            # The clock of a line is the time of day: a wait that crosses midnight goes back, and is a day longer on the other side.
            saida.append((pedidos[0], ((fim or pedidos[-1]).segundos - pedidos[0].segundos) % SEGUNDOS_NO_DIA, len(pedidos)))
            pedidos.clear()

    for x in bloco:
        if x.etiqueta == "wifi" and PEDIDO_DE_REDE.search(x.texto):
            pedidos.append(x)
        elif x.etiqueta == "wifi" and x.texto.startswith(("connected ", "using already-connected WiFi")):
            fecha(x)
    fecha(None)
    return saida


def _texto_da_espera(espera):
    """`espera pelo painel: 25 pedidos de rede em 17 min 53 s sem achá-lo`"""
    _, duracao, pedidos = espera
    return f"espera pelo painel: {pedidos} pedidos de rede em {_duracao_texto(duracao)}"


def _duracao_texto(s):
    return f"{int(s // 60)} min {int(s % 60):02d} s" if s >= 60 else f"{s:.0f} s"


def _ms(a, b):
    return round((b.segundos - a.segundos) * 1000)


def _servidor_aa(bloco):
    """A história de uma caminhada que liga o servidor do Android Auto por Ligar, e as causas que o registro mostra.

    Devolve (linha, causas): a linha com os intervalos toque → início da caminhada → clique em Iniciar → Voltar → fim →
    primeira conexão (só os que o registro tem), e as causas (porta fechada em 5 s; servidor que só atendeu quando a tela do
    Android Auto abriu; menu lido pela metade). ("", []) sem nenhuma linha `start walk begins` (a edição Lite não caminha).
    """
    inicio = next((x for x in bloco if SERVIDOR_INICIO in x.texto), None)
    if inicio is None:
        return "", []
    depois = bloco[bloco.index(inicio):]
    # The first walk is the story of this Ligar: its click and its Back are looked for up to its end, not in a later walk.
    fim = next((x for x in depois if SERVIDOR_FIM in x.texto), None)
    caminhada = depois[:depois.index(fim) + 1] if fim else depois
    clique = next((x for x in caminhada if SERVIDOR_CLIQUE in x.texto), None)
    voltar = next((x for x in caminhada[caminhada.index(clique):] if SERVIDOR_VOLTAR.search(x.texto)), None) if clique else None
    conectou = next((x for x in depois if SERVIDOR_CONECTOU in x.texto), None)
    toque = next((x for x in bloco if SERVIDOR_TOQUE in x.texto), None)
    if toque is None and inicio.segundos - bloco[0].segundos <= SERVIDOR_TOQUE_PERTO_S:
        toque = bloco[0]  # no line for the tap: the mark of the projection, when it is right before the walk
    passos = [("toque", toque), ("início da caminhada", inicio), ("clique em Iniciar", clique), ("Voltar", voltar),
              ("fim da caminhada", fim), ("primeira conexão", conectou)]
    presentes = []
    for nome, x in passos:
        if x is not None and (not presentes or x.segundos >= presentes[-1][1].segundos):
            presentes.append((nome, x))  # a step that came before the one kept (the connection came during the walk) is left out
    partes = [presentes[0][0]] + [f"{nome} +{_ms(anterior, x)} ms" for (_, anterior), (nome, x) in zip(presentes, presentes[1:])]
    causas = []
    if any(SERVIDOR_PORTA_FECHADA in x.texto for x in depois):
        causas.append("toque ok, porta fechada em 5 s")
    for x in depois:
        if SERVIDOR_CONECTOU not in x.texto:
            continue
        abriu = next((y for y in reversed(depois[:depois.index(x)]) if any(k in y.texto for k in SERVIDOR_ABRIU)), None)
        if abriu is None or x.segundos - abriu.segundos >= SERVIDOR_ATENDEU_APOS_S:
            continue
        if not any(SERVIDOR_CLIQUE in y.texto for y in depois[depois.index(abriu):depois.index(x)]):
            causas.append(f"servidor atendeu {_ms(abriu, x)} ms depois de a tela do AA abrir")
    for x in depois:
        menu = SERVIDOR_MENU.search(x.texto)
        if "DEVELOPER_MODE_OFF" in x.texto and menu and len(menu.group(1).split(" | ")) <= SERVIDOR_MENU_PELA_METADE:
            causas.append("menu pela metade (AA frio?)")
    if any(SERVIDOR_VISITA_OK in x.texto for x in depois):
        causas.append("a visita à tela do AA subiu o servidor")
    elif any(SERVIDOR_VISITA_NAO in x.texto for x in depois):
        causas.append("a visita à tela do AA não subiu o servidor")
    return "servidor do AA: " + " → ".join(partes) + (f" ({'; '.join(causas)})" if causas else ""), causas


def _sem_resposta(bloco):
    """O Android Auto calado (a leitura desistiu em 15 s) e o que aceita a conexão e não responde."""
    saida = [f"{x.hora}: Android Auto calado, 15 s sem mandar nada (a imagem do painel parou)" for x in bloco if CALADO in x.texto]
    seguidas, inicio = 0, None
    for x in bloco:
        if NEGOCIACAO_FALHOU in x.texto:
            seguidas += 1
            inicio = inicio or x.hora
        elif NEGOCIACAO_OK in x.texto:
            seguidas, inicio = _fecha_sem_resposta(saida, seguidas, inicio)
    _fecha_sem_resposta(saida, seguidas, inicio)
    return saida


def _fecha_sem_resposta(saida, seguidas, inicio):
    if seguidas >= NEGOCIACOES_SEM_RESPOSTA:
        saida.append(f"{inicio}: Android Auto aceita e não responde ({seguidas} negociações seguidas sem resposta)")
    return 0, None


def _buracos(bloco):
    saida = []
    cinco = [x for x in bloco if x.texto.startswith("video fps target=")]
    for a, b in zip(cinco, cinco[1:]):
        # With the dash session over in between (it failed, the video stopped, a new stream began) nothing was due.
        entre = bloco[bloco.index(a):bloco.index(b)]
        if any(sinal in y.texto for y in entre for sinal in SEM_VIDEO):
            continue
        if b.segundos - a.segundos > BURACO_5S:
            saida.append(f"{b.hora}: buraco nas linhas de 5 s — {b.segundos - a.segundos:.1f} s desde a anterior")
    for x in bloco:
        m = re.search(r"gap max=([\d.]+)s", x.texto)
        if x.texto.startswith("resumo ") and m and float(m.group(1)) > 1.0:
            saida.append(f"{x.hora}: buraco de {float(m.group(1)):.1f} s sem quadro (resumo)")
        escuro = DARK.search(x.texto) if x.texto.startswith("resumo ") else None
        if escuro and _duracao_s(escuro.group(3)) > DARK_PROBLEMA_S:
            saida.append(f"{x.hora}: painel sem imagem — {escuro.group(1)} interrupções, {escuro.group(2)} no total, "
                         f"a maior de {escuro.group(3)} (resumo)")
    return saida


def _duracao_s(texto):
    """Os segundos de uma duração do resumo: `45s` ou `1m08s`."""
    m = re.fullmatch(r"(?:(\d+)m)?(\d+)s", texto)
    return int(m.group(1) or 0) * 60 + int(m.group(2)) if m else 0


def _quedas(bloco):
    grupos = _grupos_de_queda(bloco)
    return [f"{b[0].hora}: quedas seguidas — {b[0].segundos - a[-1].segundos:.0f} s depois da anterior"
            for a, b in zip(grupos, grupos[1:]) if b[0].segundos - a[-1].segundos < QUEDAS_SEGUIDAS_S]


def _em_uso(texto):
    """Uma olhada do celular sem carregador e sem a tela apagada (o que não diz, vale): só nela o calor é do uso."""
    return _device(texto, "charging") != "yes" and _device(texto, "screen") != "off"


def _temperatura(bloco):
    olhadas = [x for x in bloco if x.texto.startswith("device ")]
    saida = []
    termicos = [_device(x.texto, "thermal") for x in olhadas]
    niveis = [TERMICO.index(t) for t in termicos if t in TERMICO]
    if niveis and max(niveis) >= TERMICO.index("moderate") and max(niveis) > niveis[0]:
        saida.append(f"temperatura subindo: estado térmico {TERMICO[niveis[0]]} → {TERMICO[max(niveis)]}")
    # Com o carregador ligado (a bateria esquenta carregando) ou a tela apagada o calor não é do uso: não conta.
    graus = [float(g) for x in olhadas if _em_uso(x.texto) for g in [_device(x.texto, "temp")] if g and g != "?"]
    if graus and (max(graus) - graus[0] >= SOBE_GRAUS or max(graus) >= QUENTE_GRAUS):
        saida.append(f"temperatura subindo: bateria de {graus[0]:.1f} °C a {max(graus):.1f} °C (sem carregador, tela ligada)")
    return saida


def main(argv):
    if not argv:
        print(__doc__)
        return 2
    linhas = ler(argv)
    print("\n".join(linha_do_tempo(linhas)) or "nenhuma projeção nos arquivos")
    achados = problemas(linhas) + do_sistema(argv, linhas)
    print("\nPROBLEMAS:" if achados else "\nnenhum problema conhecido")
    for p in achados:
        print(f"  - {p}")
    return 1 if achados else 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main(sys.argv[1:]))
