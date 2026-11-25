"""O que o registro de pacotes (as linhas `[detail] dash tx …` e `dash rx …` dos `ride-*.log`, ligado na build debug) diz do painel, por conexão (por projeção):

- o resumo do batimento: o pacote que o app manda ao painel a cada segundo (`dash tx 0608:05 0610:43 0603:55 0604:A9 060F:AA 0601:01 054C:10 052D:0000 051B:1A 0521:32`), campo a
  campo: o que cada um quer dizer (bateria do celular, temperatura do clima, GPS, carregador, sinal, volume da música e da chamada, rumo, estado da chamada), o valor de cada um e
  quantas vezes mudou na conexão. Antes só se via à mão.
- o grupo `03` do painel (`dash rx 0303:55 0304:55 … 030B:55555555`: as configurações que o painel diz sozinho ao conectar e não repete), comparado de uma conexão com a anterior, e
  com a escrita que o app fez no painel entre elas (`panel settings:`), para responder «o painel mudou?».

O ritmo do batimento (a mediana e o maior intervalo) fecha a linha dele, os campos que variam trazem o menor e o maior valor e uma chave que o app não conhece entra em hexadecimal. O grupo `03` que muda de
uma conexão para a seguinte também vai aos "AVISOS DA LEITURA" (`avisos03`).

O leitor principal (`ler_registro.py`) descarta as linhas `[detail]`; aqui elas são lidas à parte. Puro na parte de contas (`lido`, `batimento`, `grupo03`, `comparar03`); só `ler` abre arquivo.
O que se mostra são os códigos de configuração e de estado do app, nunca o texto das linhas de rota nem de música (o registro de pacotes já os deixa de fora).
"""
import re
import statistics
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import tempo  # noqa: E402

LINHA = re.compile(r"^(\d\d:\d\d:\d\d\.\d{3})\s+(?:\+\s*\d+ms\s+)?\[detail\] dash (tx|rx) (.*)$")
TLV = re.compile(r"\b([0-9A-F]{4}):([0-9A-F]*)\b")
BATIMENTO_ID = "0604"  # o campo da bateria do celular só vai no batimento
GRUPO_03 = re.compile(r"\b03[0-9A-F]{2}:")
JANELA_03_S = 90.0  # o painel diz o grupo `03` na abertura da conexão

# (chave, nome, como ler): `n` um número com [base] subtraída, `liga` 55/AA, `hex` o byte como está, `rumo` dois bytes (graus)
CAMPOS = [
    ("0604", "bateria do celular", ("n", 0x64, " %")),
    ("060F", "carregador", ("liga", None, "")),
    ("0603", "GPS", ("liga", None, "")),
    ("0601", "sinal do celular", ("sinal", None, "")),
    ("0608", "ícone do clima", ("hex", None, "")),
    ("0610", "temperatura do clima", ("n", 0x28, " °C")),
    ("054C", "volume da música", ("n", 0x10, "/10")),
    ("051B", "volume da chamada", ("n", 0x10, "/10")),
    ("052D", "rumo", ("rumo", None, "")),
    ("0521", "estado da chamada", ("hex", None, "")),
]
LIGA = {"55": "ligado", "AA": "desligado"}
CONTINUOS = {"0604", "0610", "052D"}  # variam sempre: mostrar a faixa e as mudanças, não cada valor
VALORES_MAX = 6


def lido(bruta):
    """(hora, 'tx'|'rx', {chave: hex}) de uma linha `[detail] dash tx|rx …`; None para qualquer outra. A mesma chave repetida no pacote fica com o primeiro valor."""
    m = LINHA.match(bruta)
    if not m:
        return None
    tlvs = {}
    for chave, valor in TLV.findall(m.group(3)):
        tlvs.setdefault(chave, valor)
    return m.group(1), m.group(2), tlvs


def ler(arquivos):
    """[{arquivo, segundos, direcao, tlvs}] dos pacotes que interessam (o batimento `tx` e os `rx` do grupo `03`) nos registros [arquivos] (Path), em ordem."""
    saida = []
    for a in arquivos:
        for bruta in a.read_text(encoding="utf-8", errors="replace").splitlines():
            if "[detail] dash " not in bruta:
                continue
            if "dash tx" in bruta and BATIMENTO_ID + ":" not in bruta:
                continue
            if "dash rx" in bruta and not GRUPO_03.search(bruta):
                continue
            achado = lido(bruta)
            if achado is None:
                continue
            h, mi, se = achado[0].split(":")
            saida.append({"arquivo": a.name, "segundos": int(h) * 3600 + int(mi) * 60 + float(se), "direcao": achado[1], "tlvs": achado[2]})
    return saida


def da_projecao(pacotes, bloco):
    """Os pacotes de [pacotes] que caem na projeção [bloco]: do mesmo registro e entre a primeira e a última linha dela nesse registro."""
    faixas = {}
    for x in bloco:
        a, b = faixas.get(x.arquivo, (x.segundos, x.segundos))
        faixas[x.arquivo] = (min(a, x.segundos), max(b, x.segundos))
    return [p for p in pacotes if p["arquivo"] in faixas and faixas[p["arquivo"]][0] <= p["segundos"] <= faixas[p["arquivo"]][1]]


def _legivel(chave, valor_hex):
    """O valor de um campo como se lê, ou o hexadecimal quando não se sabe."""
    tipo, base, sufixo = next((c[2] for c in CAMPOS if c[0] == chave), ("hex", None, ""))  # uma chave que o app não conhece fica em hexadecimal
    try:
        if tipo == "n":
            return f"{int(valor_hex, 16) - base}{sufixo}"
        if tipo == "liga":
            return LIGA.get(valor_hex.upper(), f"?{valor_hex}")
        if tipo == "sinal":
            return {"01": "com sinal", "00": "sem sinal"}.get(valor_hex, f"?{valor_hex}")
        if tipo == "rumo":
            return f"{int(valor_hex, 16)}°"
    except ValueError:
        pass
    return valor_hex


def _vezes(n):
    return f"{n} {'mudança' if n == 1 else 'mudanças'}"


def frequencia(segundos):
    """O ritmo de uma sequência de instantes (segundos do dia, em ordem): {pacotes, duracao, mediana, maior} em segundos, ou None com menos de dois."""
    if len(segundos) < 2:
        return None
    passos = [tempo.dif(b, a) for a, b in zip(segundos, segundos[1:])]
    return {"pacotes": len(segundos), "duracao": sum(passos), "mediana": statistics.median(passos), "maior": max(passos)}


def texto_da_frequencia(f):
    """`a cada 1,00 s (mediana; o maior intervalo 3,2 s) em 861 s`; '' sem frequência."""
    if f is None:
        return ""
    return f"a cada {tempo.dec(f['mediana'], 2)} s (mediana; o maior intervalo {tempo.dec(f['maior'], 1)} s) em {tempo.dec(f['duracao'], 0)} s"


def batimento(pacotes):
    """O resumo do batimento de uma conexão: {pacotes, frequencia (texto), campos: [(nome, texto)]} ou None sem nenhum batimento. [pacotes]: os `tx` da conexão (com o campo `0604`).
    Os campos que o app não conhece (uma chave nova no pacote) entram depois dos conhecidos, em hexadecimal, com o menor e o maior valor."""
    tx = [p for p in pacotes if p["direcao"] == "tx" and BATIMENTO_ID in p["tlvs"]]
    hb = [p["tlvs"] for p in tx]
    if not hb:
        return None
    desconhecidas = list(dict.fromkeys(k for p in hb for k in p if k not in {c[0] for c in CAMPOS}))
    campos = []
    for chave, nome, _ in CAMPOS + [(k, f"campo {k}", None) for k in desconhecidas]:
        valores = [p[chave] for p in hb if chave in p]
        if not valores:
            continue
        mudancas = sum(1 for a, b in zip(valores, valores[1:]) if a != b)
        contagem = Counter(valores)
        if chave in CONTINUOS and len(contagem) > 1:
            numeros = [int(v, 16) for v in valores]
            menor, maior = _legivel(chave, f"{min(numeros):X}"), _legivel(chave, f"{max(numeros):X}")
            if chave == "052D":  # a faixa de um rumo não quer dizer nada
                texto = f"{len(contagem)} valores, {_vezes(mudancas)}"
            else:
                texto = f"de {_legivel(chave, valores[0])} a {_legivel(chave, valores[-1])} (faixa {menor} a {maior}), {_vezes(mudancas)}"
        elif len(contagem) == 1:
            texto = f"{_legivel(chave, valores[0])} (fixo em {len(valores)})"
        else:
            vistos = ", ".join(f"{_legivel(chave, v)} ×{n}" for v, n in contagem.most_common(VALORES_MAX))
            tipo = next((c[2][0] for c in CAMPOS if c[0] == chave), "desconhecido")
            numeros = [int(v, 16) for v in contagem if tipo in ("n", "desconhecido") and re.fullmatch(r"[0-9A-Fa-f]+", v)]  # o ícone e o estado da chamada são códigos: sem faixa
            faixa = f" (mínimo {_legivel(chave, f'{min(numeros):0{len(valores[0])}X}')}, máximo {_legivel(chave, f'{max(numeros):0{len(valores[0])}X}')})" if numeros else ""
            texto = f"{vistos}{', …' if len(contagem) > VALORES_MAX else ''}{faixa}, {_vezes(mudancas)}"
        campos.append((nome, texto))
    return {"pacotes": len(hb), "frequencia": texto_da_frequencia(frequencia([p["segundos"] for p in tx])), "campos": campos}


def grupo03(pacotes):
    """{chave: hex} do grupo `03` que o painel disse na abertura da conexão (os `rx` até `JANELA_03_S` s depois do primeiro, o primeiro valor de cada chave); vazio sem nenhum."""
    rx = [p for p in pacotes if p["direcao"] == "rx" and any(k.startswith("03") for k in p["tlvs"])]
    if not rx:
        return {}
    primeiro = rx[0]["segundos"]
    saida = {}
    for p in rx:
        if tempo.dif(p["segundos"], primeiro) > JANELA_03_S:
            break
        for chave, valor in p["tlvs"].items():
            if chave.startswith("03"):
                saida.setdefault(chave, valor)
    return saida


def comparar03(conexoes):
    """Compara o grupo `03` de cada conexão com o da anterior. [conexoes]: [(rótulo, grupo03, escritas)] em ordem; `escritas` é o texto das mudanças que o app escreveu no painel durante
    a conexão (`temperature CELSIUS -> FAHRENHEIT`) ou '' se não escreveu. Devolve linhas de texto; as conexões sem o grupo `03` no registro de pacotes não entram na comparação e
    são contadas numa linha só."""
    saida, anterior, sem = [], None, 0
    for rotulo, grupo, escritas in conexoes:
        if not grupo:
            sem += 1
            continue
        texto = " ".join(f"{k}:{v}" for k, v in sorted(grupo.items()))
        if anterior is None:
            saida.append(f"{rotulo}: grupo 03 {texto}")
        else:
            antes_rotulo, antes, antes_escritas = anterior
            mudou = sorted(k for k in set(grupo) | set(antes) if grupo.get(k) != antes.get(k))
            apos = f" (a conexão anterior escreveu no painel: {antes_escritas})" if antes_escritas else ""
            if not mudou:
                saida.append(f"{rotulo}: grupo 03 igual ao da conexão de {antes_rotulo}{apos}" +
                             ("; o grupo 03 não mudou depois da escrita" if antes_escritas else ""))
            else:
                trocas = ", ".join(f"{k} {antes.get(k, '-')}→{grupo.get(k, '-')}" for k in mudou)
                saida.append(f"{rotulo}: grupo 03 MUDOU em relação à conexão de {antes_rotulo}: {trocas}{apos}")
        anterior = (rotulo, grupo, escritas)
    if sem:
        saida.append(f"{sem} {'conexão' if sem == 1 else 'conexões'} sem o grupo 03 no registro de pacotes (`[detail]` desligado, ou a conexão não chegou a ele): fora da comparação")
    return saida


ESCRITA = re.compile(r"^panel settings: writing .*?\(changes: ([^)]*)\)")


def _escritas(bloco):
    return "; ".join(m.group(1) for x in bloco for m in [ESCRITA.match(x.texto)] if m)


def avisos03(conexoes):
    """Os avisos para "AVISOS DA LEITURA": o grupo `03` do painel (unidades e preferências que ele diz ao conectar) mudou de uma conexão para a seguinte. [conexoes]: o mesmo
    [(rótulo, grupo03, escritas)] de `comparar03`; as conexões sem o grupo ficam de fora e a comparação é com a anterior que o tem. Diz o que mudou (`0306 10→20`) e, se o app
    escreveu no painel na conexão anterior, que a mudança pode ser dessa escrita."""
    saida, anterior = [], None
    for rotulo, grupo, escritas in conexoes:
        if not grupo:
            continue
        if anterior is not None:
            antes_rotulo, antes, antes_escritas = anterior
            mudou = sorted(k for k in set(grupo) | set(antes) if grupo.get(k) != antes.get(k))
            if mudou:
                trocas = ", ".join(f"{k} {antes.get(k, '-')}→{grupo.get(k, '-')}" for k in mudou)
                apos = f"; o app escreveu no painel na conexão anterior ({antes_escritas}), a mudança pode ser dessa escrita" if antes_escritas else ""
                saida.append(f"o grupo 03 do painel (unidades e preferências) mudou de uma conexão para a outra: {rotulo} contra {antes_rotulo}: {trocas}{apos}")
        anterior = rotulo, grupo, escritas
    return saida


def secao(arquivos, blocos):
    """As linhas da seção "PACOTES DO PAINEL" (ver [analise])."""
    return analise(arquivos, blocos)[0]


def analise(arquivos, blocos):
    """(linhas, avisos): as linhas da seção "PACOTES DO PAINEL": por conexão o resumo do batimento e a comparação do grupo `03`, e os avisos (o `03` que mudou entre conexões).
    [arquivos]: os registros lidos; [blocos]: as projeções. Vazios se nenhum registro tem pacotes `[detail]`."""
    nomes = {x.arquivo for b in blocos for x in b}
    pacotes = ler([a for a in arquivos if a.name in nomes])
    if not pacotes:
        return [], []
    saida, conexoes = [], []
    for bloco in blocos:
        deles = da_projecao(pacotes, bloco)
        rotulo = f"{bloco[0].hora[:8]} ({bloco[0].arquivo})"
        hb = batimento(deles)
        if hb:
            saida.append(f"{rotulo}: batimento de {hb['pacotes']} pacotes — " + " | ".join([f"{n}: {t}" for n, t in hb["campos"]] + ([f"frequência: {hb['frequencia']}"] if hb["frequencia"] else [])))
        conexoes.append((rotulo, grupo03(deles), _escritas(bloco)))
    saida += comparar03(conexoes)
    return saida, avisos03(conexoes)
