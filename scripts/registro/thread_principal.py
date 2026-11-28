"""As linhas `[mainthread]` do vigia da thread principal do app (0.4.0): `main thread not answering for 3.2 s, at: android.os.MessageQueue.nativePollOnce < ...` (a thread
principal do REau não respondeu ao toque que o vigia posta a cada segundo; uma linha a 1 s, a 3 s e a 5 s de atraso, com as 8 classes/métodos de cima da pilha) e
`main thread answered again after 3.7 s` (quando ela volta, com a duração toda). Servem para dizer, quando o Android fecha o app por "ANR", se foi a thread principal DO REAU que
travou, por quanto tempo e onde. Puro: recebe as linhas de uma projeção (qualquer objeto com `hora`, `etiqueta`, `texto`); não lê arquivo.

Um travamento é a sequência de linhas `not answering` até a linha `answered again` que o fecha; sem essa linha (o app foi fechado, ou o registro acabou) ele vale até a última linha dita, e o
texto diz que a volta não foi registrada. Passa a PROBLEMAS quando chega a `LIMITE_S` (3 s); os de 1 s a menos de 3 s só entram na linha do tempo.
"""
import re

ETIQUETA = "mainthread"
LIMITE_S = 3.0  # o piso do problema: a thread principal presa por 3 s ou mais (o Android chama de ANR aos 5 s)
ESPERANDO = re.compile(r"^main thread not answering for ([\d.]+) s, at: (.*)$")
VOLTOU = re.compile(r"^main thread answered again after ([\d.]+) s$")


def eventos(texto):
    """As linhas da linha do tempo para uma linha `[mainthread]`; nada para qualquer outra coisa."""
    m = ESPERANDO.match(texto)
    if m:
        return [f"a thread principal do app está presa há {m.group(1)} s e ainda não respondeu; parada em: {_pilha_curta(m.group(2))}"]
    m = VOLTOU.match(texto)
    if m:
        return [f"o app travou a thread principal por {m.group(1)} s"]
    return []


def _pilha_curta(pilha):
    quadros = pilha.split(" < ")
    return " < ".join(quadros[:3]) + (" < ..." if len(quadros) > 3 else "")


def travamentos(bloco):
    """[(hora do primeiro aviso, segundos, voltou, pilha)] de cada travamento da projeção, na ordem."""
    saida = []
    atual = None
    for x in bloco:
        if x.etiqueta != ETIQUETA:
            continue
        espera = ESPERANDO.match(x.texto)
        volta = VOLTOU.match(x.texto)
        if espera:
            segundos = float(espera.group(1))
            if atual is None:
                atual = [x.hora, segundos, False, espera.group(2)]
            else:
                atual[1], atual[3] = max(atual[1], segundos), espera.group(2)
        elif volta:
            if atual is None:
                saida.append((x.hora, float(volta.group(1)), True, ""))
            else:
                saida.append((atual[0], float(volta.group(1)), True, atual[3]))
            atual = None
    if atual is not None:
        saida.append((atual[0], atual[1], False, atual[3]))
    return saida


def problemas(bloco):
    """Os PROBLEMAS: cada travamento da thread principal de `LIMITE_S` ou mais, com a pilha do último aviso."""
    saida = []
    for hora, segundos, voltou, pilha in travamentos(bloco):
        if segundos < LIMITE_S:
            continue
        onde = f"; parada em: {_pilha_curta(pilha)}" if pilha else ""
        if voltou:
            saida.append(f"{hora}: o app travou a thread principal por {segundos:.1f} s{onde}")
        else:
            saida.append(f"{hora}: o app travou a thread principal por pelo menos {segundos:.1f} s e a volta não foi registrada (o app foi fechado ou o registro acabou){onde}")
    return saida
