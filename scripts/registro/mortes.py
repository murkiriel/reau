"""O que o registro sabe do processo que o sistema fechou, para o `ler_registro.py` (A5 do plano dos pilotos Xiaomi e realme, 26/11/2026).

A linha `[exit] past exit: …` é escrita pelo processo SEGUINTE e diz só a hora, o motivo e a importância da morte. Os registros de 25/11/2026 mostraram que isso não basta: no
realme as quatro mortes com a projeção ligada vieram entre 298 e 303 s depois de a tela apagar (um relógio de 5 min do sistema), e só a conta do tempo desde a tela apagada
mostra isso. Aqui ficam as contas, todas sobre o registro do processo que morreu (o que vai da linha `==== process start:` dele até a hora da morte):

- o tempo desde a última vez que a tela apagou (as linhas `[device] … screen=on|off` e `[system] screen on|off`);
- o tempo de projeção até a morte;
- o tempo desde a retomada que a 0.4.0 faz (`resumed after the system ended the process`), quando o processo que morreu veio de uma;
- o que o logcat do próprio processo diz do display (`DisplayState - old: 3, new: 1`), quando a coleta o traz: no Xiaomi o display passa do apagado ao modo de espera e só depois
  fica apagado de vez, e a diferença é de 15 s (14:46:59,6 e 14:47:14,1);
- a descrição inteira da saída, e a origem que ela deixa ver num «Forçar parada» (o motivo 10 também é o do piloto nos Ajustes: o texto do gerenciador traz `due to UserDefined:`).

Puro: sem arquivo e sem estado (o logcat é lido por quem chama). Tudo é leitura do texto, não prova: o que é suposição sai dito como suposição.
"""
import re

import tempo

TELA = re.compile(r"\bscreen=(on|off)\b")
# `resumed after the system ended the process (attempt 1 of 3)`: the app's own line when the projection came back by itself (0.4.0); the part after the `;` is a note.
RETOMADA = re.compile(r"^resumed after the system ended the process \(attempt (\d+) of (\d+)(?:; [^)]*)?\)$")
# `past exit: user requested (10) at 2026-11-25 14:48:36.277, process com.reau.app pid 20085, was …`: the date, the time and the pid of the death.
SAIDA_QUANDO = re.compile(r"\) (?:at|×\d+ from \S+ \S+ to) (\d{4})-(\d\d)-(\d\d) (\d\d:\d\d:\d\d(?:\.\d+)?)")
SAIDA_PID = re.compile(r", process \S+ pid (\d+)")
DISPLAY = re.compile(r"DisplayState - old: (\d+), new: (\d+)")
DISPLAY_APAGADO, DISPLAY_ACESO, DISPLAY_ESPERA = 1, 2, 3  # android.view.Display.STATE_OFF, STATE_ON, STATE_DOZE
FORCAR_PARADA = "force stop"
RAZAO_DO_GERENCIADOR = re.compile(r"due to UserDefined:(.*)$", re.IGNORECASE)
RAZAO_ATE = 80  # characters of the manager's reason told in the line
PADRAO_FOLGA_S = 15.0  # intervals within this of one another are the same clock (realme 25/11: 298,2 to 303,2 s)
TOLERANCIA_MORTE_S = 5.0  # the clock of the death line and of the log differ by this much (the same one `_motivo_da_saida` uses)
ORIGEM_GERENCIADOR = "o texto traz a razão que o próprio sistema deu"
ORIGEM_SEM_RAZAO = "o texto não traz razão dada pelo sistema: pode ser o piloto nos Ajustes"


def segundos_do_dia(texto):
    """`14:48:36.277` em segundos do dia."""
    h, m, s = texto.split(":")
    return int(h) * 3600 + int(m) * 60 + float(s)


def estado_da_tela(x):
    """True com a tela acesa, False apagada, None se a linha não diz: `[system] screen on|off` e o `screen=` das olhadas `[device]`."""
    if x.etiqueta == "system" and x.texto in ("screen on", "screen off"):
        return x.texto == "screen on"
    if x.etiqueta == "device" and x.texto.startswith("device "):
        m = TELA.search(x.texto)
        return m.group(1) == "on" if m else None
    return None


def processo_que_morreu(linhas, i, morte_s):
    """(início, fim) em índices de `linhas` do registro do processo que morreu na hora `morte_s` e cuja morte conta `linhas[i]`: o último `==== process start:` antes do
    que escreve a linha e que começou até 5 s depois da morte. None se ele não está nos arquivos lidos."""
    inicios = [k for k, x in enumerate(linhas[:i + 1]) if x.texto.startswith(tempo.PROCESSO_COMECOU)]
    if not inicios:
        return None
    atual = inicios[-1]
    for k in reversed(inicios[:-1]):
        if tempo.assinado(morte_s, linhas[k].segundos) >= -TOLERANCIA_MORTE_S:
            return k, atual
    return None


def tela_ate_a_morte(trecho, morte_s):
    """O estado da tela nas linhas do processo até a morte: (estado, desde, minimo, ultima).
    `estado` True/False/None (acesa, apagada, sem leitura); `desde` o instante (segundos do dia) em que a última vez apagou, a primeira olhada apagada depois da última acesa;
    `minimo` quando o processo já começou com a tela apagada (o apagar foi antes: `desde` é a primeira olhada); `ultima` o instante da última leitura."""
    estado, desde, minimo, ultima = None, None, False, None
    for x in trecho:
        if tempo.assinado(x.segundos, morte_s) > 0:
            break
        lida = estado_da_tela(x)
        if lida is None:
            continue
        if not lida:
            if estado is None:
                minimo = True
            if estado is not False:
                desde = x.segundos
        else:
            minimo = False
        estado, ultima = lida, x.segundos
    return estado, (desde if estado is False else None), (minimo if estado is False else False), ultima


def projecao_ate_a_morte(trecho, morte_s, ligou, desligou):
    """Os segundos de projeção até a morte: da última marca de ligar, se nenhuma de desligar veio depois. None se não projetava (ou se não há marca de ligar no processo)."""
    ligada = None
    for x in trecho:
        if tempo.assinado(x.segundos, morte_s) > 0:
            break
        if x.texto.startswith(ligou):
            ligada = x.segundos
        elif x.texto.startswith(desligou):
            ligada = None
    return tempo.dif(morte_s, ligada) if ligada is not None else None


def retomada_ate_a_morte(trecho, morte_s):
    """(segundos desde a última retomada do processo até a morte, tentativa, de quantas); None se o processo não veio de uma retomada."""
    achada = None
    for x in trecho:
        if tempo.assinado(x.segundos, morte_s) > 0:
            break
        m = RETOMADA.match(x.texto) if x.etiqueta == "projection" else None
        if m:
            achada = (tempo.dif(morte_s, x.segundos), int(m.group(1)), int(m.group(2)))
    return achada


def origem_da_parada(motivo, descricao):
    """Para um «Forçar parada» (motivo 10 ou 11 com `FORCE STOP` na descrição), de quem parece ser, só pelo texto: (frase, do_piloto_provavel). Vazio para as outras saídas.
    No Xiaomi o gerenciador do sistema escreve `[FORCE STOP] stop <pacote> due to UserDefined:<razão>`; sem essa razão o texto não separa o piloto do sistema (suposição)."""
    if motivo not in (10, 11) or FORCAR_PARADA not in descricao.lower():
        return "", False
    razao = RAZAO_DO_GERENCIADOR.search(descricao)
    if razao:
        return f"{ORIGEM_GERENCIADOR} («{razao.group(1).strip()[:RAZAO_ATE]}»)", False
    return ORIGEM_SEM_RAZAO, True


def display_do_logcat(entradas, pid, mes, dia, morte_s):
    """O que o logcat do processo (`pid`, no `mes`/`dia` da morte) diz do display até a morte, pelas linhas `DisplayState - old: A, new: B` da janela do app:
    (primeira vez que apagou desde a última vez acesa, última vez que ficou apagado de vez); cada um é None se não houve. O modo de espera (3) tira o «de vez»."""
    apagou, de_vez = None, None
    for e in entradas:
        if e.pid != pid or (e.mes, e.dia) != (mes, dia):
            continue
        m = DISPLAY.search(e.texto) if e.tag.startswith("VRI[") else None
        if not m:
            continue
        t = segundos_do_dia(e.hora)
        if tempo.assinado(t, morte_s) > 0:
            continue
        novo = int(m.group(2))
        if novo == DISPLAY_ACESO:
            apagou, de_vez = None, None
        elif novo == DISPLAY_APAGADO:
            apagou = t if apagou is None else apagou
            de_vez = t
        elif novo == DISPLAY_ESPERA:
            de_vez = None
    return apagou, de_vez


def texto_da_tela(morte_s, estado, desde, minimo, ultima):
    """A frase da tela de uma morte."""
    if estado is None:
        return "sem leitura da tela no registro do processo"
    if estado:
        return f"a tela estava acesa na última olhada ({tempo.segundos_texto(tempo.dif(morte_s, ultima))} antes da morte)"
    espera = tempo.dif(morte_s, desde)
    if minimo:
        return (f"a tela já estava apagada quando o processo começou (primeira olhada às {tempo.hora_ms(desde)}): pelo menos {tempo.segundos_texto(espera)} "
                "de tela apagada até a morte")
    return f"a tela apagou {tempo.segundos_texto(espera)} antes da morte (às {tempo.hora_ms(desde)})"


def texto_do_display(morte_s, apagou, de_vez):
    """A frase do display pelo logcat do processo; vazia sem as linhas."""
    if apagou is None:
        return ""
    texto = f"pelo logcat do processo o display apagou {tempo.segundos_texto(tempo.dif(morte_s, apagou))} antes da morte (às {tempo.hora_ms(apagou)})"
    if de_vez is not None and de_vez != apagou:
        texto += f" e ficou apagado de vez {tempo.segundos_texto(tempo.dif(morte_s, de_vez))} antes (às {tempo.hora_ms(de_vez)}, depois do modo de espera)"
    return texto


def detalhes(linhas, i, morte_s, ligou, desligou, descricao, motivo, entradas_do_app=(), pid=None, data=None):
    """O que se sabe do processo que morreu: (texto da linha, intervalo de tela apagada em s ou None, do_piloto_provavel).
    `linhas[i]` é a linha `[exit]`; `ligou` e `desligou` as marcas de projeção do leitor (strings ou tuplas, para `startswith`); `entradas_do_app` as linhas `-v threadtime`
    dos logcats do app, com `pid` e `data` (mes, dia) da morte, para o display."""
    partes = []
    intervalo, do_piloto = None, False
    limites = processo_que_morreu(linhas, i, morte_s)
    if limites is None:
        partes.append("o registro do processo que morreu não está nos arquivos lidos: sem o tempo de tela, de projeção e de retomada")
    else:
        trecho = linhas[limites[0]:limites[1]]
        estado, desde, minimo, ultima = tela_ate_a_morte(trecho, morte_s)
        partes.append(texto_da_tela(morte_s, estado, desde, minimo, ultima))
        if desde is not None and not minimo:
            intervalo = tempo.dif(morte_s, desde)
        projetando = projecao_ate_a_morte(trecho, morte_s, ligou, desligou)
        partes.append(f"projetava havia {tempo.duracao_texto(projetando)}" if projetando is not None else "sem projeção ligada na hora da morte")
        retomada = retomada_ate_a_morte(trecho, morte_s)
        if retomada:
            partes.append(f"o processo veio da retomada (tentativa {retomada[1]} de {retomada[2]}), {tempo.segundos_texto(retomada[0])} antes da morte")
        if entradas_do_app and pid is not None and data is not None:
            do_display = texto_do_display(morte_s, *display_do_logcat(entradas_do_app, pid, data[0], data[1], morte_s))
            if do_display:
                partes.append(do_display)
    origem, do_piloto = origem_da_parada(motivo, descricao)
    partes.append(f"descrição inteira da saída: «{descricao}»" if descricao else "a saída não traz descrição")
    if origem:
        partes.append(f"origem pelo texto (suposição): {origem}")
    return "; ".join(partes), intervalo, do_piloto


def padrao(intervalos):
    """A linha do padrão quando dois ou mais intervalos de tela apagada coincidem (todos dentro de `PADRAO_FOLGA_S`); vazia se não. `intervalos` é a lista de (segundos, hora da morte);
    vale o maior grupo (o mais antigo entre iguais)."""
    ordem = sorted(intervalos)
    melhor = []
    for k, (inicio, _) in enumerate(ordem):
        grupo = [p for p in ordem[k:] if p[0] - inicio <= PADRAO_FOLGA_S]
        if len(grupo) > len(melhor):
            melhor = grupo
    if len(melhor) < 2:
        return ""
    menor, maior = f"{melhor[0][0]:.0f}", f"{melhor[-1][0]:.0f}"
    faixa = menor if menor == maior else f"{menor} a {maior}"
    return (f"as mortes vieram {faixa} s depois de a tela apagar (padrão): {len(melhor)} de {len(ordem)} mortes com a tela apagada ({', '.join(h for _, h in sorted(melhor, key=lambda p: p[1]))}); "
            "a tela vem das olhadas do registro, então cada número pode ter até 5 s a mais")
