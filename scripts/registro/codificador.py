"""As linhas `[stream]` do teste do codificador de vídeo (0.4.0, 26/11/2026): qual codificador fez o vídeo, a taxa, o SPS e a forma do fluxo.

O app escreve, por sessão e igual para os dois codificadores (o do celular e o de software do Android, a opção experimental «Codificador de vídeo»):

- `encoder in use: asked=SOFTWARE name=c2.android.avc.encoder kind=sw rate=CBR -> CBR; software encoder asked: c2.android.avc.encoder first`
  (o que o piloto pediu, o codificador que abriu, hardware `hw` ou software `sw`, a taxa pedida e a enviada; depois do `;` a nota: o de software que não existe no celular cai no do celular);
- `encoder rate: CBR -> VBR (CBR not offered), codec says VBR` (o modo de taxa que o codec devolveu);
- `encoder sps: profile=Baseline(66) level=4.1 constraints=0x80; original=67 42 80 29 ...; sent=67 42 00 29 ...` (o SPS como o codificador fez e como vai ao painel);
- `W: software encoder failed: the phone's own from now on` (o de software falhou na saída depois de abrir e o codificador refeito, e todo o resto da sessão, é o do celular; só nessa sessão);
- `video shape: frames=300 keyframes=48 keybytes avg=31200 max=44700 keypackets avg=24.3 max=33 slices=1 peak1s=441kbps/38pkt` no fim da sessão e a cada 5 minutos
  (o quadro-chave médio e o maior, em bytes e em pacotes RTP; `slices=1` ou `slices=3 multi=7/300`: as fatias por quadro; o pior segundo, em kbps e em pacotes).

Depois dessa troca o `encoder in use` (escrito só uma vez, pelo primeiro codificador) continua dizendo «software» e o `video shape` soma quadros dos dois codificadores: o leitor mostra a troca na
linha do tempo e marca a sessão na linha `codificador:` (revisão de 26/11/2026, C2), para ela não contaminar a comparação do teste A.

O que separa os dois braços do teste são os bytes por quadro: o leitor mostra as três primeiras na linha do tempo e junta tudo numa linha `codificador:` por projeção
(a última linha `video shape:`, que traz os totais até ali). Puro: recebe as linhas de uma projeção (qualquer objeto com `hora`, `etiqueta`, `texto`); não lê arquivo.
"""
import re

ETIQUETA = "stream"
EM_USO = re.compile(r"^encoder in use: asked=(\w+) name=(\S+) kind=(hw|sw) rate=(.+?)(?:; (.*))?$")
TAXA = re.compile(r"^encoder rate: (.+), codec says (.+)$")
SPS = re.compile(r"^encoder sps: profile=(\S+) level=(\S+) constraints=(\S+); original=([0-9A-F ]+); sent=([0-9A-F ]+)$")
FORMA = re.compile(
    r"^video shape: frames=(\d+) keyframes=(\d+) keybytes avg=(\d+) max=(\d+) keypackets avg=([\d.]+) max=(\d+) "
    r"slices=(\d+)(?: multi=(\d+)/(\d+))? peak1s=(\d+)kbps/(\d+)pkt$"
)
PEDIDO = {"PHONE": "o do celular", "SOFTWARE": "o de software do Android"}
TROCA = re.compile(r"^software encoder failed: the phone's own from now on$")
SEM_SOFTWARE = "none in the phone's list"


def _dec(valor, casas=1):
    """Número com vírgula decimal, como o resto do leitor (`24,3`)."""
    return f"{float(valor):.{casas}f}".replace(".", ",")


def _taxa(texto):
    """`CBR -> VBR (CBR not offered)` em português: `pedida CBR, enviada VBR (CBR não oferecido)`; `CBR -> none (refused)` (o último degrau abriu sem modo): `enviada nenhum (recusado)`."""
    pedida, _, enviada = texto.partition(" -> ")
    return f"pedida {pedida}, enviada {enviada.replace('not offered', 'não oferecido').replace('none (refused)', 'nenhum (recusado)')}"


def _em_uso(m):
    pedido, nome, tipo, taxa, nota = m.groups()
    kind = "hardware" if tipo == "hw" else "software"
    frase = f"codificador do vídeo: pediu {PEDIDO.get(pedido, pedido)}, abriu {nome} ({kind}); taxa {_taxa(taxa)}"
    if nota and SEM_SOFTWARE in nota:
        frase += "; o celular não lista um codificador de software do Android: ficou o do celular"
    return frase


def eventos(texto):
    """As linhas da linha do tempo para as linhas do codificador; nada para qualquer outra coisa (o `video shape:` só entra em `texto`)."""
    m = EM_USO.match(texto)
    if m:
        return [_em_uso(m)]
    if TROCA.match(texto):
        return ["o codificador de software falhou: daqui em diante o do celular, até o fim da sessão (o `video shape` desta sessão mistura os dois codificadores)"]
    m = TAXA.match(texto)
    if m:
        return [f"taxa do codificador: {_taxa(m.group(1))}, o codec diz {m.group(2)}"]
    m = SPS.match(texto)
    if m:
        igual = m.group(4) == m.group(5)
        return [f"SPS do codificador: {m.group(1)}, nível {m.group(2)}, flags {m.group(3)}; original {m.group(4)}; "
                + ("enviado igual" if igual else f"enviado {m.group(5)}")]
    return []


def forma(m):
    """O texto da última linha `video shape:` de uma projeção: o quadro-chave, as fatias e o pior segundo."""
    quadros, chaves, bytes_medio, bytes_max, pacotes_medio, pacotes_max, fatias, multi, de, kbps, pacotes = m.groups()
    partes = []
    if int(chaves):
        partes.append(f"quadro-chave médio {bytes_medio} B em {_dec(pacotes_medio)} pacotes (máx {bytes_max} B em {pacotes_max})")
    else:
        partes.append("nenhum quadro-chave")
    partes.append("fatias por quadro 1" if int(fatias) <= 1 else f"fatias por quadro até {fatias} ({multi} de {de} quadros com mais de uma)")
    partes.append(f"pior segundo {kbps} kbps em {pacotes} pacotes ({quadros} quadros, {chaves} quadros-chave)")
    return "; ".join(partes)


def texto(bloco):
    """A linha `codificador: …` de uma projeção, juntando o que o app disse dele (o que abriu, a taxa, o SPS e a forma até ali); None sem nenhuma dessas linhas."""
    partes = []
    ultimas = {}
    troca = None  # a hora (HH:MM:SS) em que o de software falhou e o do celular assumiu
    for x in bloco:
        if x.etiqueta != ETIQUETA:
            continue
        if troca is None and TROCA.match(x.texto):
            troca = x.hora[:8]
        for chave, padrao in (("uso", EM_USO), ("taxa", TAXA), ("sps", SPS), ("forma", FORMA)):
            m = padrao.match(x.texto)
            if m:
                ultimas[chave] = m
    caiu = f"caiu para o do celular às {troca}" if troca else ""
    if "uso" in ultimas:
        pedido, nome, tipo, _, nota = ultimas["uso"].groups()
        partes.append(f"{nome} ({'hardware' if tipo == 'hw' else 'software'}; pedido: {PEDIDO.get(pedido, pedido)}"
                      + ("; sem o de software no celular" if nota and SEM_SOFTWARE in nota else "") + (f"; {caiu}" if caiu else "") + ")")
    elif caiu:
        partes.append(f"o de software {caiu}")
    if "taxa" in ultimas:
        partes.append(f"taxa {_taxa(ultimas['taxa'].group(1))}, aceita {ultimas['taxa'].group(2)}")
    if "sps" in ultimas:
        partes.append(f"SPS {ultimas['sps'].group(1)} nível {ultimas['sps'].group(2)}")
    if "forma" in ultimas:
        partes.append(forma(ultimas["forma"]))
    if not partes:
        return None
    aviso = " — o `video shape` desta sessão mistura os dois codificadores e não serve para comparar" if troca else ""
    return "codificador: " + "; ".join(partes) + aviso
