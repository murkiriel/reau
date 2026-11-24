"""O logcat do sistema (`logcat-sistema*.txt`, formato `-v threadtime`) que o `coletar_do_celular.sh` traz, lido para o `ler_registro.py`.

    MM-DD HH:MM:SS.mmm  PID  TID L TAG: texto

Duas coisas só, as que o registro do REau não vê: o Android Auto (`com.google.android.projection.gearhead`) que travou com
`FATAL EXCEPTION`, com o intervalo desde o `byebye` que o REau mandou, e os travamentos de quadros do próprio REau (`Davey!` e
`Skipped N frames` com N de 100 para cima). O arquivo tem dado pessoal (nome do dono, e-mails, o Wi-Fi de casa): aqui só saem a
hora, o pacote e o nome da classe da exceção, nunca a mensagem dela nem o resto das linhas (a mensagem é texto livre).
"""
import re
from collections import namedtuple
from datetime import datetime, timedelta
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mascarar  # noqa: E402
import tempo  # noqa: E402

THREADTIME = re.compile(r"^(\d\d)-(\d\d) (\d\d:\d\d:\d\d\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+([^:]*?)\s*: (.*)$")
GEARHEAD = "com.google.android.projection.gearhead"
PROCESSO = re.compile(r"^Process: ([\w.]+)(?::(\w+))?, PID: (\d+)")
# The app's process: `am_cpu` and `am_proc_start` carry `[pid,uid,package` / `[0,pid,uid,package`; the system's `Start proc` too.
PID_DO_APP = [re.compile(r"\[(?:0,)?(\d+),\d+,com\.reau\.app(?:\.lite)?[,\]]"), re.compile(r"Start proc (\d+):com\.reau\.app(?:\.lite)?[/ ]")]
DAVEY = re.compile(r"Davey! duration=(\d+)ms")
PULADOS = re.compile(r"Skipped (\d+) frames")
PULADOS_MINIMO = 100
DO_SISTEMA = ("logcat-sistema", "logcat-crash")  # os logcats que a coleta tira do aparelho por adb: não são os do app
MASCARADO = ".mascarado"  # `logcat-sistema.mascarado.txt`: a cópia com o nome do painel trocado
BYE_ATE_S = 60.0  # a crash this soon after our byebye is told as the cause being close; later, only the time
Entrada = namedtuple("Entrada", "mes dia hora pid tid nivel tag texto")
# O formato `-v threadtime` não diz de que buffer é a linha; as etiquetas dizem. `main` é o dos apps e dos serviços (o `wpa_supplicant`, o Android Auto `GH.`/`CAR.`,
# o `WifiNetworkFactory`, o codec); `system` é o do `system_server` (o `ActivityManager`, o `AppOps`). Na coleta de 08/11/2026 o `system` ia de 08:39 e o `main` só de 18:24:53.
ETIQUETAS_DO_BUFFER = {
    "main": ("wpa_supplicant", "WifiNetworkFactory", "GH.", "CAR.", "CCodec", "QC2C2DEngine", "FusedLocation", "netd", "Choreographer"),
    "system": ("ActivityManager", "AppOps", "WindowManager", "ThermalManagerService"),
}
# O HAL térmico morto de alguns celulares (`ThermalHAL not initialized properly`) escreve centenas de milhares de linhas e expulsa o resto do buffer: não conta como cobertura.
ENCHENTE_TERMICA = ("ThermalHalWrapper", "android.hardware.thermal")


def e_enchente(e):
    return e.tag.startswith(ENCHENTE_TERMICA)


def etiqueta_do_buffer(e, buffer):
    """A entrada é de uma etiqueta que só o buffer `main` ou `system` carrega (ver `ETIQUETAS_DO_BUFFER`); falso para as outras e para a enchente térmica."""
    return not e_enchente(e) and e.tag.startswith(ETIQUETAS_DO_BUFFER[buffer])


def arquivos(caminhos):
    """Os `logcat-sistema*.txt` dos caminhos: arquivos soltos, ou pastas (e a pasta de cima quando a dada é a `diag`).

    A coleta guarda o logcat duas vezes, `logcat-sistema.txt` e `logcat-sistema.mascarado.txt` (o mesmo texto com o nome do painel trocado): lido os dois, cada
    evento contaria em dobro (o NPE do Android Auto, 03/11/2026). Fica um só: o não mascarado; o mascarado só entra quando não há o outro com o mesmo nome base."""
    achados = []
    for c in map(Path, caminhos):
        if c.is_dir():
            pastas = [c] + ([c.parent] if c.name == "diag" else [])
            achados += [a for p in pastas for a in sorted(p.glob("logcat-sistema*.txt"))]
        elif c.name.startswith("logcat-sistema"):
            achados.append(c)
    unicos = list(dict.fromkeys(a.resolve() for a in achados))
    inteiros = {a.name for a in unicos if MASCARADO not in a.name}
    return [a for a in unicos if MASCARADO not in a.name or a.name.replace(MASCARADO, "") not in inteiros]


def arquivos_do_app(caminhos):
    """Os `logcat-*.txt` do próprio app (a pasta `diag` do aparelho): na pasta dada, na `diag` dentro dela, ou o arquivo solto; nunca o do sistema nem o buffer de quedas (`logcat-crash.txt`)."""
    saida = []
    for c in map(Path, caminhos):
        if c.is_dir():
            saida += [a for p in (c, c / "diag") if p.is_dir() for a in sorted(p.glob("logcat-*.txt")) if not a.name.startswith(DO_SISTEMA)]
        elif c.name.startswith("logcat-") and not c.name.startswith(DO_SISTEMA):
            saida.append(c)
    return list(dict.fromkeys(saida))


def ler(caminhos, so_do_app=False):
    """As entradas `-v threadtime` dos `logcat-sistema*.txt`, ou, com `so_do_app`, dos `logcat-*.txt` do app."""
    entradas = []
    for a in (arquivos_do_app(caminhos) if so_do_app else arquivos(caminhos)):
        for bruta in a.read_text(encoding="utf-8", errors="replace").splitlines():
            m = THREADTIME.match(bruta)
            if m:
                entradas.append(Entrada(int(m.group(1)), int(m.group(2)), m.group(3), int(m.group(4)), int(m.group(5)),
                                        m.group(6), m.group(7), m.group(8)))
    return entradas


def _instante(e, ano, byebyes):
    """O instante da entrada. O logcat não traz o ano: vale o `ano` dos registros, ou o vizinho que cai mais perto de um byebye (virada
    de dezembro para janeiro); um dia que o ano não tem (29/02) fica sem instante."""
    h, m, s = e.hora.split(":")
    candidatos = []
    for a in (ano, ano - 1, ano + 1):
        try:
            candidatos.append(datetime(a, e.mes, e.dia, int(h), int(m), int(s[:2])) + timedelta(milliseconds=int(e.hora[-3:])))
        except ValueError:
            continue
    if candidatos and byebyes:
        return min(candidatos, key=lambda c: min(abs((c - b).total_seconds()) for b in byebyes))
    return candidatos[0] if candidatos else None


NAO_E_DO_REAU = "não é do REau"  # no fim da linha de uma queda do Android Auto que o REau não causou: o `ler_registro.py` a tira dos PROBLEMAS e a põe nos avisos
SEM_DISCAR = "sem discar do REau nos 60 s antes — o REau estava congelado, morto ou sem projeção"


def travadas_do_android_auto(entradas, byebyes, ano, discagens=()):
    """`11:45:19  o Android Auto (gearhead:projection) travou: NullPointerException … (1,2 s depois do byebye do REau)` para cada
    `FATAL EXCEPTION` cujo processo é o do Android Auto (a `Failed to start connection` do `:projection` congelado é uma delas).
    `byebyes` são os instantes (datetime) em que o REau mandou o byebye e `discagens` os em que ele discou o servidor do Android Auto.

    A queda que vem até `BYE_ATE_S` s depois de um discar ou de um byebye do REau é dele: diz o intervalo (a do discar é a do Android Auto congelado que o REau
    acordou sem querer, 13/11 03:36 e 14/11 19:47). A que não tem nenhum dos dois por perto não é dele (o REau estava congelado, morto ou sem projeção, e o Android
    Auto caiu sozinho ao descongelar): a linha termina com `NAO_E_DO_REAU` e o leitor a põe nos avisos, não nos problemas."""
    saida = []
    for i, e in enumerate(entradas):
        if e.tag != "AndroidRuntime" or not e.texto.startswith("FATAL EXCEPTION"):
            continue
        seguintes = [y for y in entradas[i + 1:i + 6] if y.pid == e.pid and y.tag == "AndroidRuntime"]
        proc = next((m for y in seguintes for m in [PROCESSO.match(y.texto)] if m), None)
        if proc is None or proc.group(1) != GEARHEAD:
            continue
        mensagem = next((y.texto for y in seguintes if not PROCESSO.match(y.texto) and not y.texto.startswith("\t")), "")
        excecao = mensagem.split(":")[0].strip()  # the class only: the message is free text
        nome = f"gearhead:{proc.group(2)}" if proc.group(2) else "gearhead"
        quando = _instante(e, ano, [*byebyes, *discagens])
        antes_do_bye = [(quando - b).total_seconds() for b in byebyes if quando and 0 <= (quando - b).total_seconds() <= BYE_ATE_S]
        antes_do_discar = [(quando - d).total_seconds() for d in discagens if quando and 0 <= (quando - d).total_seconds() <= BYE_ATE_S]
        if antes_do_discar and (not antes_do_bye or min(antes_do_discar) <= min(antes_do_bye)):
            depois = f" ({min(antes_do_discar):.1f} s depois do discar do REau)".replace(".", ",")
        elif antes_do_bye:
            depois = f" ({min(antes_do_bye):.1f} s depois do byebye do REau)".replace(".", ",")
        else:
            depois = f" (sem byebye do REau nos 60 s antes) ({SEM_DISCAR}; {NAO_E_DO_REAU})"
        saida.append(f"{tempo.hora8(e)}: o Android Auto ({nome}) travou — {excecao[:100] or 'FATAL EXCEPTION'}{depois}")
    return saida


def pids_do_app(entradas):
    return {int(m.group(1)) for e in entradas for rx in PID_DO_APP for m in [rx.search(e.texto)] if m}


def travadas_do_app(entradas):
    """`Davey!` (um quadro de 700 ms ou mais) e `Skipped N frames` (N ≥ 100) do processo do REau: quantas vezes e a pior."""
    pids = pids_do_app(entradas)
    daveys = [(int(m.group(1)), e) for e in entradas if e.pid in pids for m in [DAVEY.search(e.texto)] if m]
    pulados = [(int(m.group(1)), e) for e in entradas if e.pid in pids and e.tag == "Choreographer"
               for m in [PULADOS.search(e.texto)] if m and int(m.group(1)) >= PULADOS_MINIMO]
    saida = []
    if daveys:
        ms, e = max(daveys, key=lambda d: d[0])
        saida.append(f"{tempo.hora8(e)}: o app travou quadros ({len(daveys)}× Davey!, o pior de {ms} ms)")
    if pulados:
        n, e = max(pulados, key=lambda d: d[0])
        saida.append(f"{tempo.hora8(e)}: o app pulou quadros ({len(pulados)}× com {PULADOS_MINIMO} ou mais, o pior com {n})")
    return saida


POR_EVENTO_MAX = 30
TOLERANCIA_FIM_S = 1.0  # a frame is logged when it ends: a stall that began in the walk is told a moment after it


def travadas_do_app_por_evento(entradas, ano, caminhadas):
    """Cada `Davey!` e cada `Skipped N frames` (N ≥ 100) do logcat do próprio app (todas as linhas são dele), com a hora e, se cai
    numa caminhada do servidor, dizendo qual. `caminhadas`: (modo, início, fim) de cada caminhada, em datetime. As das caminhadas
    primeiro e, no total, até `POR_EVENTO_MAX`, e uma linha com quantas mais houve."""
    eventos = []
    for e in entradas:
        davey = DAVEY.search(e.texto)
        pulados = PULADOS.search(e.texto) if e.tag == "Choreographer" else None
        if davey:
            eventos.append((e, f"Davey! de {int(davey.group(1))} ms"))
        elif pulados and int(pulados.group(1)) >= PULADOS_MINIMO:
            eventos.append((e, f"{int(pulados.group(1))} quadros pulados"))
    contados = []
    for e, o_que in eventos:
        quando = _instante(e, ano, [])
        fase = ""
        for modo, inicio, fim in caminhadas:
            if quando and inicio <= quando <= fim + timedelta(seconds=TOLERANCIA_FIM_S):
                fase = f" (durante a caminhada de {'iniciar' if modo == 'start' else 'parar' if modo == 'stop' else 'visitar'} o servidor)"
                break
        contados.append((e, f"{e.dia:02d}/{e.mes:02d} {tempo.hora8(e)}: o app travou a tela — {o_que}{fase}", bool(fase)))
    # The ones that fell in a walk first (that is what the report asks), then the others, each in the order of the file.
    primeiros = sorted(contados, key=lambda c: not c[2])[:POR_EVENTO_MAX]
    saida = [linha for _, linha, _ in sorted(primeiros, key=lambda c: contados.index(c))]
    if len(eventos) > POR_EVENTO_MAX:
        saida.append(f"(mais {len(eventos) - POR_EVENTO_MAX} travadas do app no logcat; as {POR_EVENTO_MAX} listadas são as das caminhadas e, depois, as primeiras)")
    return saida


# O que o SISTEMA diz do Wi-Fi do painel na hora de uma queda (plano 25). Só o que não identifica ninguém: do `wpa_supplicant`, o nome do evento
# e os campos `reason`, `status_code`, `locally_generated`, `auth_type` (nunca o BSSID nem o SSID); das outras fontes, só as linhas que citam o
# Wi-Fi do painel (`RE_xxxx_nnnnnn`), com o nome e os endereços MAC escondidos e o texto cortado. Outras redes (a de casa) nunca saem.
WIFI_ANTES_S = 30.0
WIFI_DEPOIS_S = 10.0
WIFI_POR_QUEDA = 8
WIFI_TAGS = ("wpa_supplicant", "WifiService", "WifiClientModeImpl", "ClientModeImpl", "WifiConnectivityManager", "WifiNetworkFactory",
             "WifiNative", "WifiHealthMonitor", "ConnectivityService", "NetworkMonitor", "NetworkAgent", "netd")
EVENTO_WPA = re.compile(
    r"CTRL-EVENT-(?:DISCONNECTED|CONNECTED|ASSOC-REJECT|AUTH-REJECT|SSID-TEMP-DISABLED|SSID-REENABLED|NETWORK-NOT-FOUND|TERMINATING|CHANNEL-SWITCH)"
)  # not the signal changes, one every few seconds
CAMPO_WPA = re.compile(r"\b(reason|status_code|locally_generated|auth_type)=(\S+)")
DASH = mascarar.DASH_QUALQUER  # the masks are the ones of mascarar.py: one place says what is hidden
MAC = mascarar.MAC
WIFI_TEXTO_MAX = 140


def _resumo_wifi(e):
    """A linha do sistema como pode ser contada, ou None se não é do Wi-Fi do painel nem um evento do `wpa_supplicant`."""
    if e.tag == "wpa_supplicant":
        evento = EVENTO_WPA.search(e.texto)
        if not evento:
            return None
        campos = " ".join(f"{k}={v}" for k, v in CAMPO_WPA.findall(e.texto))
        return f"wpa_supplicant {evento.group(0)}" + (f" {campos}" if campos else "")
    if e.tag in WIFI_TAGS and DASH.search(e.texto):
        texto = MAC.sub(mascarar.MASK_MAC, DASH.sub(mascarar.MASK, e.texto)).strip()
        return f"{e.tag} {texto[:WIFI_TEXTO_MAX]}"
    return None


def wifi_nas_quedas(entradas, quedas, ano, da_moto=()):
    """Para cada queda do Wi-Fi do painel (`quedas`: instantes em datetime, do registro do app), o que o sistema disse nos `WIFI_ANTES_S` s antes e
    nos `WIFI_DEPOIS_S` s depois: `10:06:55: o Wi-Fi do painel caiu — o sistema disse: 10:06:54.9 wpa_supplicant CTRL-EVENT-DISCONNECTED reason=3 … `.
    Até `WIFI_POR_QUEDA` linhas por queda; uma queda sem nada no logcat não gera linha (o logcat guarda pouca história). `da_moto` são as quedas (instantes) que são o painel
    desligando com a moto: a linha diz `(moto desligada)` para o leitor não a contar como defeito."""
    saida = []
    com_instante = [(e, _instante(e, ano, [])) for e in entradas if e.tag in WIFI_TAGS]
    for queda in sorted(set(quedas)):
        achados = []
        for e, quando in com_instante:
            if quando is None or not -WIFI_ANTES_S <= (quando - queda).total_seconds() <= WIFI_DEPOIS_S:
                continue
            resumo = _resumo_wifi(e)
            if resumo:
                achados.append(f"{e.hora[:10]} {resumo}")
        if achados:
            mais = f" (+{len(achados) - WIFI_POR_QUEDA} linhas)" if len(achados) > WIFI_POR_QUEDA else ""
            moto = " (moto desligada)" if queda in da_moto else ""
            saida.append(f"{queda:%H:%M:%S}: o Wi-Fi do painel caiu{moto} — o sistema disse: " + " | ".join(achados[:WIFI_POR_QUEDA]) + mais)
    return saida


# O que o SISTEMA diz de quem derrubou o Wi-Fi: o `Deauth RX … reason 3` do driver (o ponto de acesso do painel avisou que estava saindo: a moto desligou),
# o `Disconnect reason: 3 … LG: 0` que o driver escreve em seguida e o `CTRL-EVENT-DISCONNECTED … reason=3` do `wpa_supplicant` (`locally_generated=1` é o celular que saiu).
# O driver escreve numa etiqueta com `:` dentro (`[schedu][0x…][12:03:58.477855] wlan`), que o formato `-v threadtime` acima não lê: estas linhas são lidas cruas.
HORA_CRUA = re.compile(r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d{3})\s")
DEAUTH_RX = re.compile(r"Deauth RX: .*?\breason (\d+)\b")
DESCONEXAO_HDD = re.compile(r"Disconnect reason: (\d+)\b.*?\bLG: (\d)")
DESCONEXAO_WPA = re.compile(r"CTRL-EVENT-DISCONNECTED\b.*?\breason=(\d+)(?:.*?\blocally_generated=(\d))?")
MARCAS_DE_DESCONEXAO = ("Deauth RX", "Disconnect reason", "CTRL-EVENT-DISCONNECTED")
DesconexaoDoSistema = namedtuple("DesconexaoDoSistema", "quando motivo local")


def _cruas(caminhos, ano, marcas):
    """(instante, linha) de cada linha crua dos `logcat-sistema*.txt` que traz alguma das [marcas]; um dia que o ano não tem (29/02) fica de fora."""
    for a in arquivos(caminhos):
        with open(a, encoding="utf-8", errors="replace") as f:
            for bruta in f:
                if not any(marca in bruta for marca in marcas):
                    continue
                h = HORA_CRUA.match(bruta)
                if h is None:
                    continue
                try:
                    quando = datetime(ano, int(h.group(1)), int(h.group(2)), int(h.group(3)), int(h.group(4)), int(h.group(5))) + timedelta(milliseconds=int(h.group(6)))
                except ValueError:
                    continue
                yield quando, bruta


def desconexoes_do_wifi(caminhos, ano):
    """As desconexões do Wi-Fi que o logcat do sistema registrou: [DesconexaoDoSistema(quando, motivo, local)], em ordem de hora. `motivo` é o número (3 é `DEAUTH_LEAVING`),
    `local` é verdadeiro quando foi o celular que saiu (`locally_generated=1`, `LG: 1`); o `Deauth RX` é sempre o outro lado. Só a hora, o motivo e quem saiu: nunca o endereço nem o nome da rede."""
    saida = []
    for quando, bruta in _cruas(caminhos, ano, MARCAS_DE_DESCONEXAO):
        if "Deauth RX" in bruta and (m := DEAUTH_RX.search(bruta)):
            motivo, local = int(m.group(1)), False
        elif "Disconnect reason" in bruta and (m := DESCONEXAO_HDD.search(bruta)):
            motivo, local = int(m.group(1)), m.group(2) == "1"
        elif "CTRL-EVENT-DISCONNECTED" in bruta and (m := DESCONEXAO_WPA.search(bruta)):
            motivo, local = int(m.group(1)), m.group(2) == "1"
        else:
            continue
        saida.append(DesconexaoDoSistema(quando, motivo, local))
    return sorted(saida)


# O driver escreve `lim_process_join_failure_timeout: Join Failure Timeout` quando o celular pediu para entrar numa rede e o ponto de acesso não respondeu a tempo
# (o painel que ainda não está pronto, ou que atendia outro pedido). Só a hora: a linha traz o endereço do ponto de acesso.
FALHA_DE_ENTRADA = ("lim_process_join_failure_timeout", "Join Failure Timeout")


def falhas_de_entrada(caminhos, ano):
    """Os instantes (datetime, em ordem) das falhas de entrada numa rede Wi-Fi que o driver registrou (`FALHA_DE_ENTRADA`)."""
    return sorted(quando for quando, _ in _cruas(caminhos, ano, FALHA_DE_ENTRADA))


# O TIPO de cada perda do Wi-Fi do painel, pelo que o driver e o `wpa_supplicant` dizem em volta da queda (plano 32). Quatro tipos, e `?` sem logcat do sistema na hora:
#   A  o painel saiu: o ponto de acesso do painel mandou `Deauth RX … reason 3` (`DEAUTH_LEAVING`, `LG: 0`, o `wpa_supplicant` sem `locally_generated=1`): a ignição desligou;
#   B  o painel sumiu do ar: `ROAM_TRIGGER … "BEACON MISS"` ou `QCA_DISCONNECT_REASON_BEACON_MISS_FAILURE` (reason 0; o dumpsys do Wi-Fi o conta como -1), sem aviso de saída antes;
#   C  o painel parou de responder com o Wi-Fi no ar: `STA kickout … reason:2` / `QCA_DISCONNECT_REASON_PEER_INACTIVITY` (reason 1) sem nenhum `BEACON MISS`;
#   D  4-way recusado: `Deauth RX … reason 15` / `WLAN_REASON_4WAY_HANDSHAKE_TIMEOUT`, a assinatura do painel travado (21/11/2026 07:25).
# A ganha de D, que ganha de B, que ganha de C: o aviso de saída explica tudo o que vem depois; o 4-way recusado vem DEPOIS da queda (o celular tenta entrar de novo) e diz mais do
# que o kickout que o abriu (21/11 07:25 tem kickout e 4-way); o `BEACON MISS` vem junto do kickout (10:01 de 21/11) e o diz melhor. O driver escreve a hora do logcat até 3 s antes da
# do `wpa_supplicant` (21/11 07:47: `07:47:24.0` e `07:47:27.1` para o mesmo evento), então a janela olha para trás mais do que para a frente.
TIPO_SAIU, TIPO_SUMIU, TIPO_CALOU, TIPO_RECUSADO, TIPO_SEM_LOGCAT = "A", "B", "C", "D", "?"
PERDA_ANTES_S = 20.0  # o sinal do sistema até tanto antes da primeira linha da queda
PERDA_DEPOIS_SAIU_S = 5.0  # o aviso de saída vem na hora; mais tarde que isso já é o da queda seguinte
PERDA_DEPOIS_S = 15.0  # o `BEACON MISS` e o kickout vêm até ~9 s depois da primeira linha (o vigia do app cala o painel antes de o sistema desistir: 21/11 11:46)
PERDA_DEPOIS_4WAY_S = 30.0  # o 4-way recusado é a nova tentativa do celular, 8 a 11 s depois (21/11 07:25)
PERDA_ANTES_4WAY_S = 5.0
SINAL_SAIU, SINAL_SUMIU, SINAL_INATIVIDADE, SINAL_4WAY = "saiu", "sumiu", "inatividade", "4way"
MOTIVO_4WAY = 15
MARCAS_DA_PERDA = ("Deauth RX", "Disconnect reason", "CTRL-EVENT-DISCONNECTED", "STA kickout", "BEACON MISS")
SinalDaPerda = namedtuple("SinalDaPerda", "quando sinal")
TipoDaPerda = namedtuple("TipoDaPerda", "tipo quando coberto")
NOME_DO_TIPO = {
    TIPO_SAIU: "o painel saiu",
    TIPO_SUMIU: "o painel sumiu do ar",
    TIPO_CALOU: "o painel parou de responder com o Wi-Fi no ar",
    TIPO_RECUSADO: "4-way recusado",
    TIPO_SEM_LOGCAT: "sem logcat do sistema na hora",
}
PROVA_DO_TIPO = {
    TIPO_SAIU: "o ponto de acesso do painel avisou que estava saindo (reason 3, DEAUTH_LEAVING; a ignição desligou)",
    TIPO_SUMIU: "o celular parou de ouvir o painel (BEACON MISS), sem aviso de saída",
    TIPO_CALOU: "o ponto de acesso expulsou o celular por inatividade (STA kickout, PEER_INACTIVITY), sem BEACON MISS",
    TIPO_RECUSADO: "o painel recusou o aperto de mão de 4 vias (reason 15, 4WAY_HANDSHAKE_TIMEOUT; painel travado, só desligar e ligar a ignição resolve)",
}
COBERTURA_PERDA_MIN = 20  # linhas do logcat (sem a enchente térmica) na janela da queda para dizer que ele cobre a hora
COBERTURA_PERDA_BALDE_S = 10


def _sinal_da_linha(bruta):
    """SINAL_* que a linha crua do driver, do `wpa_supplicant` ou do `STA kickout` conta, ou None. Só o nome do sinal: nunca o endereço."""
    if "BEACON MISS" in bruta:
        return SINAL_SUMIU
    if "STA kickout" in bruta:
        return SINAL_INATIVIDADE
    if "Deauth RX" in bruta and (m := DEAUTH_RX.search(bruta)):
        motivo, local = int(m.group(1)), False
    elif "Disconnect reason" in bruta and (m := DESCONEXAO_HDD.search(bruta)):
        motivo, local = int(m.group(1)), m.group(2) == "1"
        if "BEACON_MISS_FAILURE" in bruta:
            return SINAL_SUMIU
        if "PEER_INACTIVITY" in bruta:
            return SINAL_INATIVIDADE
    elif "CTRL-EVENT-DISCONNECTED" in bruta and (m := DESCONEXAO_WPA.search(bruta)):
        motivo, local = int(m.group(1)), m.group(2) == "1"
    else:
        return None
    if motivo == MOTIVO_4WAY:
        return SINAL_4WAY
    return SINAL_SAIU if motivo == 3 and not local else None


def sinais_da_perda(caminhos, ano):
    """(sinais, cobertura) dos `logcat-sistema*.txt`: os `SinalDaPerda(quando, sinal)` em ordem de hora e quantas linhas o logcat tem em cada balde de `COBERTURA_PERDA_BALDE_S` s
    (a chave é `MM-DD HH:MM:D`, dezenas de segundos), sem a enchente térmica. Uma passada só pelo arquivo, que pode ter milhões de linhas."""
    sinais, cobertura = [], {}
    for a in arquivos(caminhos):
        with open(a, encoding="utf-8", errors="replace") as f:
            for bruta in f:
                if len(bruta) < 19 or bruta[2] != "-" or bruta[5] != " " or ENCHENTE_TERMICA[0] in bruta or ENCHENTE_TERMICA[1] in bruta:
                    continue
                balde = bruta[:13]
                cobertura[balde] = cobertura.get(balde, 0) + 1
                if not any(marca in bruta for marca in MARCAS_DA_PERDA):
                    continue
                sinal = _sinal_da_linha(bruta)
                h = HORA_CRUA.match(bruta)
                if sinal is None or h is None:
                    continue
                try:
                    quando = datetime(ano, int(h.group(1)), int(h.group(2)), int(h.group(3)), int(h.group(4)), int(h.group(5))) + timedelta(milliseconds=int(h.group(6)))
                except ValueError:
                    continue
                sinais.append(SinalDaPerda(quando, sinal))
    return sorted(sinais), cobertura


def _cobre(cobertura, de, ate):
    """O logcat tem `COBERTURA_PERDA_MIN` linhas ou mais entre `de` e `ate` (datetime)."""
    total, t = 0, de - timedelta(seconds=de.second % COBERTURA_PERDA_BALDE_S, microseconds=de.microsecond)
    while t <= ate:
        total += cobertura.get(f"{t:%m-%d %H:%M:}{t.second // COBERTURA_PERDA_BALDE_S}", 0)
        t += timedelta(seconds=COBERTURA_PERDA_BALDE_S)
    return total >= COBERTURA_PERDA_MIN


def tipo_da_perda(inicio, fim, sinais, cobertura):
    """O `TipoDaPerda(tipo, quando, coberto)` de uma queda do Wi-Fi do painel cuja primeira linha é de `inicio` e a última de `fim` (datetime), pelos `sinais` e pela `cobertura` de
    `sinais_da_perda`. `quando` é a hora do sinal que decidiu (None em `?`); `coberto` diz se o logcat tem linhas na hora (`?` com ele coberto é um logcat que não viu a perda)."""
    def entre(sinal, antes, depois):
        return [s.quando for s in sinais if s.sinal == sinal and inicio - timedelta(seconds=antes) <= s.quando <= fim + timedelta(seconds=depois)]
    coberto = _cobre(cobertura, inicio - timedelta(seconds=PERDA_ANTES_S), fim + timedelta(seconds=PERDA_DEPOIS_4WAY_S))
    for tipo, achados in ((TIPO_SAIU, entre(SINAL_SAIU, PERDA_ANTES_S, PERDA_DEPOIS_SAIU_S)),
                          (TIPO_RECUSADO, entre(SINAL_4WAY, PERDA_ANTES_4WAY_S, PERDA_DEPOIS_4WAY_S)),
                          (TIPO_SUMIU, entre(SINAL_SUMIU, PERDA_ANTES_S, PERDA_DEPOIS_S)),
                          (TIPO_CALOU, entre(SINAL_INATIVIDADE, PERDA_ANTES_S, PERDA_DEPOIS_S))):
        if achados:
            return TipoDaPerda(tipo, min(achados), True)
    return TipoDaPerda(TIPO_SEM_LOGCAT, None, coberto)


def texto_do_tipo(t):
    """`tipo da perda: A, o painel saiu — o ponto de acesso … (reason 3, …), às 21:16:07` para a linha do tempo; `?` diz se faltou o logcat ou se ele não viu a perda."""
    if t.tipo == TIPO_SEM_LOGCAT:
        return f"tipo da perda: ? ({'o logcat do sistema cobre a hora mas não registrou a perda' if t.coberto else NOME_DO_TIPO[TIPO_SEM_LOGCAT]})"
    return f"tipo da perda: {t.tipo}, {NOME_DO_TIPO[t.tipo]} — {PROVA_DO_TIPO[t.tipo]}, às {t.quando:%H:%M:%S}"
