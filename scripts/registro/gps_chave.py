"""O GPS que a chave experimental «velocidade do GPS» liga durante a rota (a build de 15/11/2026 em diante), lido das linhas do registro:
`gps: on for the speed profile (route)` quando o dono «velocidade» do GPS passa a pedi-lo e
`gps: off for the speed profile (route ended | key off | location off | speed profile off | no location permission | projection stopped)` quando solta.
Serve para dizer, por projeção, quanto tempo o GPS ficou ligado por causa da chave (o custo de bateria dela) e como cada pedido acabou.
Puro: recebe as linhas de uma projeção (qualquer objeto com `hora`, `segundos`, `etiqueta`, `texto`); não lê arquivo.

Regras da conta, por projeção:
  - um pedido vai da linha `on` à linha `off` seguinte; sem `off` (o processo morreu, ou o registro acabou) ele vai até a última linha da projeção, e o texto diz isso;
  - uma linha `on` com um pedido já aberto não abre outro; um `off` sem pedido aberto não conta nada;
  - o relógio do registro é a hora do dia: passar da meia-noite é um dia a mais (`tempo.dif`).
"""
import re

import tempo

LIGOU = "gps: on for the speed profile (route)"
SOLTOU = re.compile(r"^gps: off for the speed profile \(([^)]*)\)")
SEM_FIX = "speed profile: GPS key on, no GPS fix yet; route speed used"
MOTIVOS = {
    "route ended": "a rota acabou",
    "key off": "a chave foi desligada",
    "location off": "«Usar a localização» foi desligado",
    "speed profile off": "o perfil por velocidade foi desligado",
    "no location permission": "sem a permissão de localização",
    "projection stopped": "a projeção parou",
}


def motivo(texto):
    """O motivo de um `off`, em português; o que o app não diz ainda passa como veio."""
    return MOTIVOS.get(texto, texto)


def evento(texto):
    """A frase da linha do tempo para uma linha do registro da chave, ou None para qualquer outra."""
    if texto == LIGOU:
        return "GPS ligado para o perfil de velocidade, durante a rota (chave experimental; gasta bateria)"
    m = SOLTOU.match(texto)
    if m:
        return f"GPS solto pelo perfil de velocidade: {motivo(m.group(1))} (chave experimental)"
    if texto == SEM_FIX:
        return "perfil de velocidade: chave do GPS ligada, ainda sem fix do GPS; vale a velocidade da rota (chave experimental)"
    return None


def pedidos(bloco):
    """[(segundos, terminou)] de cada pedido da chave na projeção: quanto durou e se o app disse o fim (`False`: contado até a última linha)."""
    saida, aberto = [], None
    for x in bloco:
        if x.texto == LIGOU:
            if aberto is None:
                aberto = x.segundos
        elif aberto is not None and SOLTOU.match(x.texto):
            saida.append((tempo.dif(x.segundos, aberto), True))
            aberto = None
    if aberto is not None and bloco:
        saida.append((tempo.dif(bloco[-1].segundos, aberto), False))
    return saida


def texto(bloco):
    """As linhas do fim da projeção: o tempo com o GPS ligado pela chave e quantos pedidos; vazio quando a chave não pediu o GPS (nenhuma linha, nem aviso)."""
    todos = pedidos(bloco)
    if not todos:
        return []
    total = sum(s for s, _ in todos)
    vezes = "1 pedido" if len(todos) == 1 else f"{len(todos)} pedidos"
    aberto = " (o último não tem a linha de fim: contado até o fim da projeção)" if not all(fim for _, fim in todos) else ""
    return [f"GPS ligado pela chave «velocidade do GPS»: {tempo.duracao_texto(total)} em {vezes}{aberto}"]
