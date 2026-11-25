"""A corrente, a carga e a CPU do REau, lidas das linhas `device …` do registro (a build de 15/11/2026 em diante):
`device battery=64% … current=-310mA charge=2840mAh cpu=35% mem=…`. Serve para separar o que o REau gasta (codificador, Wi-Fi, GPS) do que é da tela e do Android Auto.
Puro: recebe as linhas de uma projeção (qualquer objeto com `hora`, `segundos`, `etiqueta`, `texto`); não lê arquivo.

Cada campo é `-` quando o aparelho não deu o valor (nunca um número falso). Registro de antes dos campos não os tem: o leitor não diz nada, nem aviso.

Regras das contas, por projeção:
  - **corrente média**: só as olhadas com o carregador fora (`charging=no`); com o carregador a corrente é a da carga, não a do uso. Separada em «transmitindo» (de um
    `→ STREAMING` até `session fail`, `video loop stop` ou `disconnect()`, ou até o fim da projeção) e «sem transmitir».
  - **sinal**: cada fabricante usa uma convenção. Se a maioria das olhadas sem carregador tem a corrente negativa, o aparelho dá o sinal negativo ao descarregar e o leitor
    inverte (o consumo sai positivo) e diz isso; do contrário o sinal fica como veio.
  - **carga consumida**: do primeiro ao último `charge=` com valor (mAh); negativa quando a carga subiu (o carregador esteve ligado).
  - **CPU**: média e máxima do `cpu=` (em % de UM núcleo: passa de 100 com mais de um núcleo ocupado).
"""
import re

CAMPO = {nome: re.compile(rf"(?<![\w-]){nome}=(-?\d+){unidade}") for nome, unidade in (("current", "mA"), ("charge", "mAh"), ("cpu", "%"))}
TEM_CAMPO = re.compile(r"(?<![\w-])(?:current|charge|cpu)=")
CARREGANDO = re.compile(r"(?<![\w-])charging=(\w+|\?)")
COMECA = "→ STREAMING"
PARA = ("session fail", "video loop stop", "disconnect()")


def _numero(nome, texto):
    m = CAMPO[nome].search(texto)
    return int(m.group(1)) if m else None


def olhadas(bloco):
    """[{hora, tem, corrente, carga, cpu, carregando, transmitindo}] das linhas `device …` de uma projeção, pela ordem. `tem`: a linha traz os campos novos (mesmo que `-`);
    `carregando`: 'yes', 'no' ou None (`?` ou sem o campo); `transmitindo`: depois de um `→ STREAMING` e antes do que o encerra."""
    saida, transmitindo = [], False
    for x in bloco:
        t = x.texto
        if t.startswith("device "):
            c = CARREGANDO.search(t)
            saida.append({"hora": x.hora, "tem": bool(TEM_CAMPO.search(t)), "corrente": _numero("current", t), "carga": _numero("charge", t),
                          "cpu": _numero("cpu", t), "carregando": c.group(1) if c and c.group(1) in ("yes", "no") else None, "transmitindo": transmitindo})
        elif COMECA in t:
            transmitindo = True
        elif any(p in t for p in PARA):
            transmitindo = False
    return saida


def _media(valores):
    return sum(valores) / len(valores) if valores else None


def resumo(bloco):
    """Os números de uma projeção, ou None quando nenhuma olhada traz os campos (registro antigo): {invertido, com_painel, sem_painel (cada um (média em mA, olhadas) ou None),
    carga_de, carga_a, consumida (mAh), cpu_media, cpu_max, cpu_olhadas, sem_corrente (olhadas com os campos, sem nenhuma corrente), sem_carga}."""
    todas = [o for o in olhadas(bloco) if o["tem"]]
    if not todas:
        return None
    descarga = [o for o in todas if o["carregando"] == "no" and o["corrente"] is not None]
    negativas = sum(1 for o in descarga if o["corrente"] < 0)
    invertido = negativas * 2 > len(descarga)
    sinal = -1 if invertido else 1
    com = [sinal * o["corrente"] for o in descarga if o["transmitindo"]]
    sem = [sinal * o["corrente"] for o in descarga if not o["transmitindo"]]
    cargas = [o["carga"] for o in todas if o["carga"] is not None]
    cpus = [o["cpu"] for o in todas if o["cpu"] is not None]
    return {"invertido": invertido, "com_painel": (_media(com), len(com)) if com else None, "sem_painel": (_media(sem), len(sem)) if sem else None,
            "carga_de": cargas[0] if cargas else None, "carga_a": cargas[-1] if cargas else None, "consumida": cargas[0] - cargas[-1] if cargas else None,
            "cpu_media": _media(cpus), "cpu_max": max(cpus) if cpus else None, "cpu_olhadas": len(cpus),
            "sem_corrente": not descarga, "sem_carga": not cargas}


def _olhadas(n):
    return f"{n} olhada" if n == 1 else f"{n} olhadas"


def _n(v):
    return f"{v:.0f}"


def texto(bloco):
    """As linhas da linha do tempo para uma projeção (vazio num registro antigo): a corrente média com e sem o painel transmitindo, a carga consumida e a CPU."""
    r = resumo(bloco)
    if r is None:
        return []
    saida = []
    if r["com_painel"] or r["sem_painel"]:
        partes = [f"{rotulo} {_n(r[chave][0])} mA ({_olhadas(r[chave][1])})" for rotulo, chave in (("transmitindo", "com_painel"), ("sem transmitir", "sem_painel")) if r[chave]]
        saida.append("corrente média do celular sem o carregador: " + ", ".join(partes))
    else:
        saida.append("corrente média: nenhuma olhada sem o carregador com a corrente lida (`current=-` ou carregador ligado)")
    if r["invertido"]:
        saida.append("sinal da corrente invertido: o aparelho dá a corrente negativa ao descarregar (a maioria das olhadas sem carregador); nestas linhas o consumo sai positivo")
    if r["consumida"] is not None:
        quando = f"consumiu {r['consumida']} mAh" if r["consumida"] >= 0 else f"subiu {-r['consumida']} mAh (o carregador esteve ligado)"
        saida.append(f"carga da bateria: {quando} (de {r['carga_de']} a {r['carga_a']} mAh)")
    else:
        saida.append("carga da bateria: o aparelho não deu o contador (`charge=-`)")
    if r["cpu_media"] is not None:
        saida.append(f"CPU do REau: média {_n(r['cpu_media'])} %, máxima {r['cpu_max']} % de um núcleo ({_olhadas(r['cpu_olhadas'])})")
    else:
        saida.append("CPU do REau: nenhuma olhada com o uso lido (`cpu=-`)")
    return saida
