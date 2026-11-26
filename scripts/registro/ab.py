"""O teste A/B da 0.4.0 (26/11/2026): as sessões separadas pelos dois experimentos que os pilotos rodam, com os números de cada uma e as medianas de cada braço.

Os dois experimentos (cada um pede ~10 viagens por braço):

- o espaçamento do envio (`SendPacer`, 2 ms entre pacotes), ligado ou desligado: a linha `settings in use:` do começo da projeção traz `pacePackets=true|false`
  (sem ela, o `pace=on|off` das linhas `dash-stats`);
- o codificador do vídeo, «o do celular» (hardware, o padrão) ou «o de software do Android»: `videoEncoder=PHONE|SOFTWARE` da mesma linha (sem ela, o `asked=` da linha `encoder in use:`).

«Sessão» aqui é uma projeção (da marca de ligar à de desligar), a mesma unidade de `codificador.texto` e de `chaves.py`. O braço é «espaçamento X + codificador Y». Ficam num grupo À PARTE
(o texto diz `à parte`, e o grupo não serve para comparar os braços) as sessões em que o codificador não foi o que o braço diz: o de software que falhou e cedeu ao do celular no meio
(`software encoder failed: the phone's own from now on`, o `video shape` mistura os dois), o de software pedido num celular que não lista um (ficou o do celular) e a sessão em que as linhas
`encoder in use:` dizem codificadores diferentes. A sessão sem a linha de chaves nem a de uso cai em «sem registro» (a build não escrevia).

O que sai por sessão (só o que o registro tem):

- STREAMING: o `streaming=` do `resumo` (sem o resumo, a projeção foi cortada e a sessão não conta nos minutos);
- reinícios do painel: as quedas de Wi-Fi do painel que o leitor classifica como «Wi-Fi do painel reiniciou» (o ponto de acesso voltou em outro canal) e as de «painel desligou e religou»
  (a idade do painel no primeiro pacote mostra que ele ligou de novo); a queda que é só a moto desligando (fim de viagem) não conta. `quedas` é o total de quedas sem a moto desligada (a conta de
  `chaves.py`). O leitor não separa o painel que reiniciou sozinho da ignição desligada e ligada de novo: uma ignição ligada de novo no meio da viagem conta, nos dois braços;
- o maior silêncio de vídeo: o `gap max=` do `resumo` (o maior intervalo entre dois quadros durante o STREAMING; o `dark=` do resumo, a maior interrupção da imagem, sai à parte);
- a idade do painel na volta: a idade do batimento do painel no primeiro pacote depois de uma queda que voltou (`dash heartbeat age N s at the first packet`; a maior da sessão). NÃO existe
  no registro a idade do QUADRO na volta: o que há é a do painel (se ela é menor que a queda, o painel religou);
- pace-waited: o total que o `SendPacer` esperou (` pace-waited=Nms` no fim das linhas `dash-stats`, que só existe com o espaçamento ligado; a soma dos totais de cada conexão: o contador
  volta a zero em outra conexão);
- tickwait: o quanto cada imagem nova esperou pelo próximo quadro (`tickwait=média/p95/máx` das linhas `video fps`): a média das médias das janelas e o maior máximo;
- quadro-chave: o tamanho médio e o máximo, em bytes, da última linha `video shape:` (a mistura dos dois codificadores nas sessões à parte é dita);
- CPU do REau: a mediana e o máximo do `cpu=N%` das linhas `device` (a cada 30 s) durante a sessão. É o uso do PROCESSO do REau, em % de UM núcleo (passa de 100 com mais de um núcleo
  ocupado), e inclui o codificador de software, que roda dentro do processo; `cpu=-` (o aparelho não deu o valor) não conta;
- calor: o maior `temp=` (°C) das linhas `device` durante a sessão. É a temperatura da BATERIA, o calor do celular inteiro (não só do REau), então diz o quanto o celular esquentou, nos dois braços
  do mesmo jeito; `temp=?` não conta.

Por braço: as sessões, os minutos de STREAMING, os reinícios e os reinícios por hora, as medianas dos números acima (da CPU, a mediana das medianas e o maior máximo; do calor, o maior), e o AVISO «amostra pequena» com menos de `AMOSTRA_MINIMA` (10) sessões.

Puro: recebe as linhas de cada projeção (qualquer objeto com `arquivo`, `hora`, `etiqueta`, `texto`) e os números que só o `ler_registro.py` sabe fazer (as quedas); não lê arquivo.
"""
import re
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import chaves  # noqa: E402
import codificador  # noqa: E402
import tempo  # noqa: E402

AMOSTRA_MINIMA = 10
JANELA_INICIO_S = chaves.JANELA_INICIO_S
SETTINGS = chaves.SETTINGS
PACE_NA_LINHA = re.compile(r"\bpacePackets=(true|false)\b")
CODIFICADOR_NA_LINHA = re.compile(r"\bvideoEncoder=(PHONE|SOFTWARE)\b")
PACE_DO_DASH_STATS = re.compile(r"\bpace=(on|off)\b")
PACE_ESPERADO = re.compile(r" pace-waited=(\d+)ms\b")
TICKWAIT = re.compile(r"\btickwait=(\d+)/(\d+)/(\d+)ms\b")
GAP = re.compile(r"\bgap max=([\d.]+)s\b")
CPU = re.compile(r"\bcpu=(\d+)%")
TEMP = re.compile(r"\btemp=(-?\d+(?:\.\d+)?)C\b")

PACE_LIGADO, PACE_DESLIGADO, PACE_SEM = "espaçamento ligado", "espaçamento desligado", "espaçamento sem registro"
CELULAR, SOFTWARE = "codificador do celular", "codificador de software"
TROCOU = "o de software falhou e cedeu ao do celular no meio"
SEM_O_DE_SOFTWARE = "o de software pedido, mas o celular não lista um (ficou o do celular)"
MISTURADO = "dois codificadores nas linhas de uso"
CODIFICADOR_SEM = "codificador sem registro"
A_PARTE = (TROCOU, SEM_O_DE_SOFTWARE, MISTURADO)
PRINCIPAIS = [(p, c) for c in (CELULAR, SOFTWARE) for p in (PACE_LIGADO, PACE_DESLIGADO)]


def configuracao_por_projecao(linhas, blocos):
    """[{"pace": bool|None, "codificador": "PHONE"|"SOFTWARE"|None}] alinhado a [blocos]: o que a última linha `settings in use:` até a projeção ligar (ou até `JANELA_INICIO_S` s depois
    dela, que é onde o app a escreve) diz; None onde ela não traz o campo (a build não o escrevia) ou não há a linha."""
    posicao = {id(x): k for k, x in enumerate(linhas)}
    linhas_de_chaves = [(k, x.segundos, x.texto) for k, x in enumerate(linhas) if x.texto.startswith(SETTINGS)]
    saida = []
    for n, bloco in enumerate(blocos):
        inicio = posicao[id(bloco[0])]
        proximo = posicao[id(blocos[n + 1][0])] if n + 1 < len(blocos) else len(linhas)
        escolhida = None
        for k, segundos, texto in linhas_de_chaves:
            # A de antes da projeção; a de dentro dela só se vem logo depois de ela ligar (a de uma projeção seguinte, na mesma hora de outro dia, não é desta).
            if k <= inicio or (k < proximo and tempo.dif(segundos, bloco[0].segundos) <= JANELA_INICIO_S):
                escolhida = texto
            if k >= proximo:
                break
        pace = PACE_NA_LINHA.search(escolhida) if escolhida else None
        enc = CODIFICADOR_NA_LINHA.search(escolhida) if escolhida else None
        saida.append({"pace": pace.group(1) == "true" if pace else None, "codificador": enc.group(1) if enc else None})
    return saida


def _braco_do_pace(bloco, configurado):
    if configurado is None:
        vistos = {m.group(1) for x in bloco if x.etiqueta == "dash" for m in [PACE_DO_DASH_STATS.search(x.texto)] if m and x.texto.startswith("dash-stats ")}
        configurado = (vistos == {"on"}) if len(vistos) == 1 else None
    return PACE_SEM if configurado is None else (PACE_LIGADO if configurado else PACE_DESLIGADO)


def _braco_do_codificador(bloco, pedido):
    """(braço, trocou): o codificador da sessão. O braço é o que foi pedido, a não ser que o registro mostre que a sessão não foi só com ele (`A_PARTE`)."""
    usos = [m for x in bloco if x.etiqueta == codificador.ETIQUETA for m in [codificador.EM_USO.match(x.texto)] if m]
    trocou = any(codificador.TROCA.match(x.texto) for x in bloco if x.etiqueta == codificador.ETIQUETA)
    if pedido is None and usos:
        pedido = usos[0].group(1)
    if pedido is None:
        return CODIFICADOR_SEM, trocou
    if trocou:
        return TROCOU, True
    if len({(m.group(1), m.group(3)) for m in usos}) > 1:
        return MISTURADO, False
    if pedido == "SOFTWARE" and usos and (usos[0].group(3) == "hw" or (usos[0].group(5) and codificador.SEM_SOFTWARE in usos[0].group(5))):
        return SEM_O_DE_SOFTWARE, False
    return (SOFTWARE if pedido == "SOFTWARE" else CELULAR), False


def _pace_esperado(bloco):
    """O total que o `SendPacer` esperou na sessão, em ms: a soma dos últimos totais de cada conexão (o contador volta a zero em outra); None sem nenhuma linha com ele."""
    total, anterior = None, None
    for x in bloco:
        m = PACE_ESPERADO.search(x.texto) if x.etiqueta == "dash" and x.texto.startswith("dash-stats ") else None
        if not m:
            continue
        valor = int(m.group(1))
        if anterior is not None and valor < anterior:
            total = (total or 0) + anterior
        anterior = valor
    return None if anterior is None else (total or 0) + anterior


def fatos(bloco, configuracao, quedas, reiniciou, religou, idades):
    """Os números de uma sessão. [configuracao]: o dicionário de `configuracao_por_projecao`; [quedas]: as quedas do painel sem a moto desligada; [reiniciou]: as que o leitor chama de
    «Wi-Fi do painel reiniciou»; [religou]: as de «painel desligou e religou»; [idades]: a idade do painel (s) no primeiro pacote depois de cada queda que voltou."""
    resumo = next((m for x in bloco for m in [chaves.RESUMO.match(x.texto)] if m), None)
    folga = next((m for x in bloco if x.texto.startswith("resumo ") for m in [GAP.search(x.texto)] if m), None)
    escuro = next((m for x in bloco if x.texto.startswith("resumo ") for m in [chaves.DARK.search(x.texto)] if m), None)
    janelas = [m for x in bloco if x.etiqueta == "stream" for m in [TICKWAIT.search(x.texto)] if m and x.texto.startswith("video fps target=")]
    aparelho = [x.texto for x in bloco if x.texto.startswith("device ")]
    cpus = [int(m.group(1)) for t in aparelho for m in [CPU.search(t)] if m]
    temps = [float(m.group(1)) for t in aparelho for m in [TEMP.search(t)] if m]
    forma = next((m for x in reversed(bloco) if x.etiqueta == codificador.ETIQUETA for m in [codificador.FORMA.match(x.texto)] if m), None)
    codif, trocou = _braco_do_codificador(bloco, configuracao["codificador"])
    pace = _braco_do_pace(bloco, configuracao["pace"])
    return {
        "arquivo": bloco[0].arquivo, "hora": bloco[0].hora, "pace": pace, "codificador": codif, "trocou": trocou,
        "streaming_s": float(tempo.duracao_s(resumo.group(2))) if resumo else None,
        "quedas": quedas, "reiniciou": reiniciou, "religou": religou, "reinicios": reiniciou + religou,
        "silencio_s": float(folga.group(1)) if folga else None,
        "escuro_s": float(tempo.duracao_s(escuro.group(3))) if escuro else None,
        "idade_painel_s": max(idades) if idades else None,
        "pace_esperado_ms": _pace_esperado(bloco),
        "tickwait_medio_ms": statistics.mean(int(m.group(1)) for m in janelas) if janelas else None,
        "tickwait_max_ms": max(int(m.group(3)) for m in janelas) if janelas else None,
        "chave_medio_b": int(forma.group(3)) if forma and int(forma.group(2)) else None,
        "chave_max_b": int(forma.group(4)) if forma and int(forma.group(2)) else None,
        "cpu_mediano": statistics.median(cpus) if cpus else None,
        "cpu_max": max(cpus) if cpus else None,
        "temp_max_c": max(temps) if temps else None,
    }


def braco(sessao):
    return (sessao["pace"], sessao["codificador"])


def _ordem(chave):
    return (PRINCIPAIS.index(chave) if chave in PRINCIPAIS else len(PRINCIPAIS), chave)


def rotulo(chave):
    pace, codif = chave
    return f"{pace} + {codif}" + (" — à parte, não serve para comparar os braços" if codif in A_PARTE else "")


def _n(valor, casas=1):
    return "-" if valor is None else tempo.dec(valor, casas)


def _mediana(valores):
    valores = [v for v in valores if v is not None]
    return statistics.median(valores) if valores else None


def _min(s):
    return f"{tempo.dec(s / 60, 1)} min"


def _por_sessao(s):
    pace = f"{s['pace_esperado_ms']} ms" if s["pace_esperado_ms"] is not None else "-"
    tick = f"{_n(s['tickwait_medio_ms'], 0)}/{_n(s['tickwait_max_ms'], 0)} ms" if s["tickwait_medio_ms"] is not None else "-"
    chave = f"{s['chave_medio_b']}/{s['chave_max_b']} B" if s["chave_medio_b"] is not None else "-"
    streaming = _min(s["streaming_s"]) if s["streaming_s"] is not None else "sem resumo"
    painel = (f"painel reiniciou {s['reinicios']}x (reiniciou {s['reiniciou']}, religou {s['religou']}; {s['quedas']} quedas)" if s["reinicios"]
              else f"painel não reiniciou ({s['quedas']} quedas)")
    cpu = f"{_n(s['cpu_mediano'], 0)}/{_n(s['cpu_max'], 0)} %" if s["cpu_mediano"] is not None else "-"
    calor = f"{_n(s['temp_max_c'])} °C" if s["temp_max_c"] is not None else "-"
    return (f"{s['arquivo']} {s['hora'][:8]}  [{rotulo(braco(s))}]  STREAMING {streaming}; {painel}; maior silêncio de vídeo {_n(s['silencio_s'])} s; "
            f"idade do painel na volta {_n(s['idade_painel_s'], 0) + ' s' if s['idade_painel_s'] is not None else '-'}; pace-waited {pace}; "
            f"tickwait média/máx {tick}; quadro-chave médio/máx {chave}; CPU do REau mediana/máx {cpu}; calor máx da bateria {calor}")


def _do_braco(chave, sessoes):
    horas = sum(s["streaming_s"] for s in sessoes if s["streaming_s"] is not None) / 3600.0
    sem_resumo = sum(1 for s in sessoes if s["streaming_s"] is None)
    reinicios = sum(s["reinicios"] for s in sessoes)
    por_hora = f"{_n(reinicios / horas, 2)}/h" if horas > 0 else "-/h"
    com = sum(1 for s in sessoes if s["reinicios"])
    medianas = [
        f"STREAMING {_n(_mediana([s['streaming_s'] / 60 if s['streaming_s'] is not None else None for s in sessoes]))} min",
        f"maior silêncio de vídeo {_n(_mediana([s['silencio_s'] for s in sessoes]))} s",
        f"idade do painel na volta {_n(_mediana([s['idade_painel_s'] for s in sessoes]), 0)} s",
        f"pace-waited {_n(_mediana([s['pace_esperado_ms'] for s in sessoes]), 0)} ms",
        f"tickwait médio {_n(_mediana([s['tickwait_medio_ms'] for s in sessoes]), 0)} ms (maior máx {_n(max((s['tickwait_max_ms'] for s in sessoes if s['tickwait_max_ms'] is not None), default=None), 0)} ms)",
        f"quadro-chave médio {_n(_mediana([s['chave_medio_b'] for s in sessoes]), 0)} B (maior máx {_n(max((s['chave_max_b'] for s in sessoes if s['chave_max_b'] is not None), default=None), 0)} B)",
        f"CPU do REau mediana {_n(_mediana([s['cpu_mediano'] for s in sessoes]), 0)} % (maior máx {_n(max((s['cpu_max'] for s in sessoes if s['cpu_max'] is not None), default=None), 0)} %; "
        "em % de um núcleo, com o codificador de software dentro)",
        f"calor máx da bateria {_n(max((s['temp_max_c'] for s in sessoes if s['temp_max_c'] is not None), default=None))} °C (o celular inteiro)",
    ]
    cabeca = (f"{rotulo(chave)}: {len(sessoes)} {'sessão' if len(sessoes) == 1 else 'sessões'}, {_n(horas * 60)} min em STREAMING"
              f"{f' ({sem_resumo} sem resumo, fora da conta)' if sem_resumo else ''}, {reinicios} reinícios do painel ({por_hora}; {com} sessões com algum)")
    saida = [cabeca, "    medianas por sessão: " + "; ".join(medianas)]
    if len(sessoes) < AMOSTRA_MINIMA:
        saida.append(f"    AVISO: amostra pequena ({len(sessoes)} {'sessão' if len(sessoes) == 1 else 'sessões'}, o teste pede {AMOSTRA_MINIMA} por braço): "
                     "nenhuma conclusão sobre este braço ainda")
    if any(s["trocou"] for s in sessoes):
        saida.append("    nota: o `video shape` (quadro-chave) destas sessões mistura os dois codificadores")
    return saida


def texto(sessoes):
    """As linhas do relatório A/B: cada sessão, depois cada braço. Vazio sem nenhuma sessão."""
    if not sessoes:
        return []
    por_braco = {}
    for s in sessoes:
        por_braco.setdefault(braco(s), []).append(s)
    saida = [f"SESSÕES ({len(sessoes)}, uma por projeção; arquivo, hora e braço):"]
    for chave in sorted(por_braco, key=_ordem):
        saida += [f"  {_por_sessao(s)}" for s in sorted(por_braco[chave], key=lambda s: (s["arquivo"], s["hora"]))]
    saida.append("")
    saida.append("POR BRAÇO (o espaçamento do envio × o codificador do vídeo):")
    for chave in sorted(por_braco, key=_ordem):
        saida += [f"  {t}" for t in _do_braco(chave, por_braco[chave])]
    return saida
