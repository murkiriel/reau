"""As leituras do classificador de imagem (o MLP) que o registro da build debug traz, `picture look: MAP p=0.942 (p under 0.98; 3 looks not written)`: as que ficaram abaixo de
`P_BAIXO`, o alarme da taxa de "sem certeza" e o aviso de que a auditoria do app foi cortada. Puro: recebe as linhas de uma projeção (qualquer objeto com `hora`, `segundos`, `etiqueta`,
`texto`); não lê arquivo.

O app só escreve uma `picture look:` quando a classe muda ou quando a rede não tem certeza (p abaixo de 0,98, a linha de auditoria), no máximo uma por segundo e, desde 15/11/2026, no
máximo 20 por minuto, e diz quantas deixou de escrever (`N looks not written`). `p=0.000` é a imagem preta que nem chegou à rede (a primeira leitura de toda projeção): não é incerteza,
vai à parte.

Duas contas, que o reader separava mal até 15/11/2026 (o alarme contava a linha de auditoria, p < 0,98, e não o que o app faz):
  - **sem certeza** (a classe UNSURE, p < 0,9, `ScreenNet.MIN_CONFIDENCE`): o app mantém a tela anterior. É a única que alarma.
  - **faixa de 0,90 a 0,98**: a classe vale no app, só a rede estava menos firme. Aparece como informação (no texto da taxa e na tabela), nunca alarma. (Um Waze com o mapa escuro lido
    como MAPA com p = 0,979 deu 73 % de "sem certeza" na conta velha e zero na nova.)
As leituras que o app diz não ter escrito não trazem o p, mas têm a classe da linha de antes: uma mudança de classe é sempre escrita, então tudo o que ficou de fora entre duas linhas
é da classe da primeira delas, e a linha seguinte diz qual era (`was MAP, ...`) ou, se a classe não mudou, é a dela mesma. Por isso as não escritas contam como sem certeza exatamente
quando essa classe é UNSURE (a linha de depois, quando a classe muda de volta, as carrega; contar a classe dela errava para mais ou para menos em cada virada).

Alarme (limite escolhido de 11/11/2026, com a única coleta que tem estas linhas, a de 08/11; os limites não mudaram em 15/11/2026): a taxa é «leituras sem certeza ÷ leituras feitas» (as
leituras feitas vêm do campo `look=N` das linhas `video fps` de 5 s). Nas projeções boas da coleta de 08/11 (mapa, com um cartão de chamada sobre ele por 33 s, que dá 92 a 95 %) a taxa
foi de 1,2 % e 1,4 % na conta velha (e menos na nova): o alarme dispara acima de `LIMITE_PCT` = 3 %, o dobro do que as boas deram e ainda pouco para um mapa de que a rede duvida de verdade.
Sem as linhas `video fps` (o celular sem o painel, em casa) a medida é por minuto de projeção: acima de `LIMITE_POR_MINUTO` = 7 por minuto (3 % de ~4 leituras por segundo, que é o `look=20`
por janela de 5 s; fora do painel o ritmo pode ser outro, e a medida é só uma segunda opinião). Nenhum dos dois alarma com menos de `MINIMO_BAIXAS` leituras sem certeza (uma projeção
curta com duas dúvidas não é taxa). Os limites estão aqui e valem até haver mais coletas com o J4; mudá-los é mudar este arquivo.

Auditoria cortada: o app pára de escrever ao chegar no teto de linhas (`picture look: 300 lines written, no more in this session`, 300 até 14/11/2026, 3 000 depois) e dali em diante a
conta está incompleta (uma corrida de 52 min foi cortada aos 14); `avisos` diz isso.
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import tempo  # noqa: E402

LEITURA = re.compile(r"^picture look: (\w+) p=([\d.]+) \((.*)\)$")
NAO_ESCRITAS = re.compile(r"(\d+) looks? not written")
ERA = re.compile(r"\bwas (\w+)")
CORTADA = re.compile(r"^picture look: (\d+) lines written, no more in this session$")
LOOK_NA_JANELA = re.compile(r"\blook=(\d+)(?:/[\d.]+/[\d.]+ms)?")
TELA_AA = re.compile(r"^aa-screen: (nav|menu|apps)\b")
CLASSE_DA_TELA = {"MAP": "nav", "MENU": "menu", "APPS": "apps"}
TELA_NOME = {"nav": "mapa", "menu": "menu", "apps": "apps"}
P_BAIXO = 0.98  # LookAudit.LOW_P do app: a linha de auditoria; abaixo disto a leitura é listada (informação)
P_INCERTO = 0.9  # ScreenNet.MIN_CONFIDENCE do app: abaixo disto a classe é UNSURE e o app mantém a tela anterior (a única conta que alarma)
LIMITE_PCT = 3.0
LIMITE_POR_MINUTO = 7.0
MINIMO_BAIXAS = 10
LISTA_MAX = 40  # linhas da tabela por projeção; o resto é contado


def leituras(bloco):
    """As linhas `picture look:` de uma projeção, pela ordem: [{linha, classe, p, motivo, puladas, puladas_incertas, tela_aa}]. `tela_aa`: a última `aa-screen:` antes (nav, menu, apps) ou None.
    `puladas_incertas`: das `puladas`, as que eram da classe UNSURE (p < 0,9): a classe das não escritas é a da linha de antes, que o `was X` da linha diz (sem `was`, a classe não mudou: é a dela)."""
    saida, tela = [], None
    for x in bloco:
        t = TELA_AA.match(x.texto)
        if t:
            tela = t.group(1)
            continue
        m = LEITURA.match(x.texto)
        if not m:
            continue
        puladas = NAO_ESCRITAS.search(m.group(3))
        n = int(puladas.group(1)) if puladas else 0
        era = ERA.search(m.group(3))
        classe_das_puladas = era.group(1) if era else m.group(1)
        saida.append({"linha": x, "classe": m.group(1), "p": float(m.group(2)), "motivo": m.group(3), "puladas": n,
                      "puladas_incertas": n if classe_das_puladas == "UNSURE" else 0, "tela_aa": tela})
    return saida


def baixas(lidas):
    """As leituras com p entre 0 (exclusive: a imagem preta não é leitura) e `P_BAIXO` (exclusive): as listadas, de qualquer classe."""
    return [r for r in lidas if 0 < r["p"] < P_BAIXO]


def sem_certeza(lidas):
    """As leituras escritas da classe UNSURE com p > 0 (a imagem preta, p = 0, nem chegou à rede): o app ficou sem certeza e manteve a tela anterior."""
    return [r for r in lidas if r["classe"] == "UNSURE" and r["p"] > 0]


def _leituras_feitas(bloco):
    return sum(int(m.group(1)) for x in bloco if x.texto.startswith("video fps target=") for m in [LOOK_NA_JANELA.search(x.texto)] if m)


def cortada(bloco):
    """A linha `N lines written, no more in this session` da projeção, ou None: a auditoria do app parou ali e a conta dali em diante está incompleta."""
    return next((x for x in bloco if CORTADA.match(x.texto)), None)


def taxa(bloco, lidas=None):
    """{sem_certeza (as UNSURE escritas mais as não escritas que eram UNSURE), sem_certeza_escritas, sem_certeza_puladas, faixa (as outras abaixo de 0,98, escritas e não escritas),
    baixas (as duas), escritas, puladas, pretas, incertas (linhas com a classe UNSURE, a preta inclusive), feitas, pct (de `sem_certeza`; None sem `look=`), minutos, por_minuto, alarme,
    minimo_p (a pior p das UNSURE escritas; None sem nenhuma), cortada (a linha do corte, ou None)} de uma projeção; o alarme conta só `sem_certeza` e segue as regras do cabeçalho do módulo."""
    lidas = leituras(bloco) if lidas is None else lidas
    escritas = baixas(lidas)
    puladas = sum(r["puladas"] for r in lidas)
    total = len(escritas) + puladas
    incertas_escritas = sem_certeza(lidas)
    incertas_puladas = sum(r["puladas_incertas"] for r in lidas)
    incerto = len(incertas_escritas) + incertas_puladas
    feitas = _leituras_feitas(bloco)
    minutos = tempo.dif(bloco[-1].segundos, bloco[0].segundos) / 60.0 if bloco else 0.0
    pct = 100.0 * incerto / feitas if feitas else None
    por_minuto = incerto / minutos if minutos > 0 else 0.0
    if incerto < MINIMO_BAIXAS:
        alarme = False
    elif pct is not None:
        alarme = pct > LIMITE_PCT
    else:
        alarme = por_minuto > LIMITE_POR_MINUTO
    return {"sem_certeza": incerto, "sem_certeza_escritas": len(incertas_escritas), "sem_certeza_puladas": incertas_puladas, "faixa": total - incerto, "baixas": total,
            "escritas": len(escritas), "puladas": puladas, "pretas": sum(1 for r in lidas if r["p"] == 0), "incertas": sum(1 for r in lidas if r["classe"] == "UNSURE"),
            "feitas": feitas, "pct": pct, "minutos": minutos, "por_minuto": por_minuto, "alarme": alarme,
            "minimo_p": min((r["p"] for r in incertas_escritas), default=None), "cortada": cortada(bloco)}


def _v(n, casas=3):
    return f"{n:.{casas}f}".replace(".", ",")


def texto_da_taxa(t):
    """`58 leituras sem certeza (p < 0,90; 40 escritas e 18 que o app disse não ter escrito) em 4 190 leituras: 1,4 % (limite 3 %), a pior p=0,850; mais 120 na faixa de 0,90 a 0,98
    (só informação, não alarma)`; sem as linhas `video fps`, por minuto. Com o corte da auditoria, diz a hora dele."""
    base = (f"{t['sem_certeza']} leituras sem certeza (p < {_v(P_INCERTO, 2)}; {t['sem_certeza_escritas']} escritas e {t['sem_certeza_puladas']} "
            "que o app disse não ter escrito)")
    if t["pct"] is not None:
        base += f" em {t['feitas']} leituras: {_v(t['pct'], 1)} % (limite {_v(LIMITE_PCT, 0)} %)"
    else:
        base += f" em {_v(t['minutos'], 1)} min, sem as linhas `video fps` para contar as leituras: {_v(t['por_minuto'], 1)} por minuto (limite {_v(LIMITE_POR_MINUTO, 0)})"
    if t["minimo_p"] is not None:
        base += f", a pior p={_v(t['minimo_p'])}"
    base += f"; mais {t['faixa']} na faixa de {_v(P_INCERTO, 2)} a {_v(P_BAIXO, 2)} (só informação, não alarma)"
    if t["cortada"] is not None:
        base += f"; {_texto_do_corte(t['cortada'])}"
    return base


def _texto_do_corte(x):
    """`a auditoria do app parou às 21:03:06 (900 linhas escritas, o limite): a conta dali em diante está incompleta`."""
    n = CORTADA.match(x.texto).group(1)  # type: ignore[union-attr]  # `cortada` só devolve linhas que casam
    return f"a auditoria do app parou às {x.hora[:8]} ({n} linhas escritas, o limite dela): a conta dali em diante está incompleta"


def tabela(bloco):
    """As linhas da tabela de uma projeção: cada leitura com p < 0,98 (até `LISTA_MAX`), com a classe, a tela que o `aa-screen:` dizia e se diverge dela. As de 0,90 a 0,98 também
    entram: são informação, e dizem `faixa` para não se confundirem com as que alarmam."""
    lidas = baixas(leituras(bloco))
    saida = []
    for r in lidas[:LISTA_MAX]:
        esperada = CLASSE_DA_TELA.get(r["classe"])
        tela = TELA_NOME.get(r["tela_aa"], "sem aa-screen")
        diverge = " (DIVERGE do aa-screen)" if esperada and r["tela_aa"] and esperada != r["tela_aa"] else ""
        extra = f"; {r['puladas']} leituras não escritas" if r["puladas"] else ""
        faixa = "  (faixa 0,90 a 0,98)" if r["classe"] != "UNSURE" else ""
        saida.append(f"{r['linha'].hora[:8]}  {r['classe']:<6s} p={_v(r['p'])}  tela do AA: {tela}{diverge}{extra}{faixa}")
    if len(lidas) > LISTA_MAX:
        saida.append(f"(mais {len(lidas) - LISTA_MAX} leituras não listadas)")
    return saida


def problemas(blocos):
    """O alarme de cada projeção cuja taxa de "sem certeza" (p < 0,9) passa do limite."""
    saida = []
    for bloco in blocos:
        t = taxa(bloco)
        if t["alarme"]:
            saida.append(f"projeção de {bloco[0].hora} ({bloco[0].arquivo}): o classificador de imagem ficou sem certeza demais — {texto_da_taxa(t)}")
    return saida


def avisos(blocos):
    """Uma linha por projeção cuja auditoria do app foi cortada pelo limite de linhas (`avisos_da_leitura`): o que o leitor conta dali em diante está incompleto, com ou sem alarme."""
    return [f"projeção de {b[0].hora} ({b[0].arquivo}): leituras do classificador — {_texto_do_corte(x)}" for b in blocos for x in [cortada(b)] if x is not None]


def secao(blocos):
    """As linhas da seção "LEITURAS DO CLASSIFICADOR": por projeção que tem alguma leitura abaixo de `P_BAIXO`, a taxa e a tabela. Vazio sem nenhuma."""
    saida = []
    for bloco in blocos:
        if not baixas(leituras(bloco)):
            continue
        saida.append(f"projeção de {bloco[0].hora} ({bloco[0].arquivo}): {texto_da_taxa(taxa(bloco))}")
        saida += ["  " + linha for linha in tabela(bloco)]
    return saida
