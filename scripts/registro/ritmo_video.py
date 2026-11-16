"""O ritmo do vídeo gravado, lido do índice `video/*.csv` (`frame,pts_ms,bytes_in_file,key`; o antigo é `frame,pts_ms,bytes,key`), e conferido contra as linhas `video fps` do registro.

Puro: recebe os quadros que `indice_video.ler` devolve (`(frame, pts_ms, bytes_no_arquivo, chave)`, o tamanho já no arquivo nos dois formatos) e devolve contas e texto; quem abre arquivo é
`gravacoes.py`. O que sai:

- o fps real por janela de `JANELA_FPS_S` s (a mediana, o menor e o maior; só janelas inteiras);
- o intervalo entre quadros-chave (o alvo do app é 1 s): mediana, menor, maior, e quantos passam de `CHAVE_LONGA_MS`;
- os buracos: um intervalo entre dois quadros maior que `BURACO_MS` (estritamente: 2,0 s exatos não é buraco);
- a soma de `bytes_in_file` contra o tamanho do `.h264`, só no formato novo (no antigo a coluna não conta o código de início e o aviso do índice já cobre);
- o fps do índice contra o das linhas `video fps` do registro no mesmo período: uma diferença grande é aviso (`diferenca_fps`).
"""
import bisect
import re
import statistics
import sys
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))
sys.path.insert(0, str(AQUI.parent / "dev" / "teste_cego"))  # indice_video.py: o índice do vídeo em dois formatos
import indice_video  # noqa: E402
import tempo  # noqa: E402

JANELA_FPS_S = 10.0
ALVO_CHAVE_MS = 1000
CHAVE_LONGA_MS = 1500  # um intervalo entre quadros-chave acima disto (1,5 vez o alvo) é contado
BURACO_MS = 2000
BURACOS_LISTADOS = 3
JANELAS_LOG_MIN = 3  # menos janelas `video fps` que isto no período e a comparação não se faz
DIFERENCA_FPS_PCT = 20.0  # a diferença relativa que vira aviso...
DIFERENCA_FPS_MIN = 0.3  # ...desde que passe de tantos fps (a 2 fps, 20 % são 0,4: um quadro a mais ou a menos em 10 s não alarma)
FOLGA_JANELA_S = 1.0  # o nome do arquivo é a hora em que a gravação abriu, o primeiro quadro vem um pouco depois
VIDEO_FPS = re.compile(r"^video fps target=\d+ sent=[\d.]+ \((\d+)frames/([\d.]+)s\)")
PARTE_COMPLETA = re.compile(r"^video recording part (\d+) complete, the next follows:")


def fps_por_janela(quadros, janela_s=JANELA_FPS_S):
    """[fps] de cada janela inteira de [janela_s] s desde o primeiro quadro (a última, incompleta, fica de fora). Vazio com menos de uma janela."""
    if not quadros:
        return []
    base = quadros[0][1]
    por_janela = [0] * int((quadros[-1][1] - base) / 1000.0 // janela_s)
    for q in quadros:
        i = int((q[1] - base) / 1000.0 // janela_s)
        if i < len(por_janela):
            por_janela[i] += 1
    return [n / janela_s for n in por_janela]


def intervalos_de_chave(quadros):
    """[ms] entre cada quadro-chave e o seguinte."""
    chaves = [q[1] for q in quadros if q[3]]
    return [b - a for a, b in zip(chaves, chaves[1:])]


def buracos(quadros, limite_ms=BURACO_MS):
    """[(pts_ms do quadro antes, ms até o seguinte)] dos intervalos maiores que [limite_ms] (estritamente)."""
    return [(a[1], b[1] - a[1]) for a, b in zip(quadros, quadros[1:]) if b[1] - a[1] > limite_ms]


def _dec(v, casas=1):
    return tempo.dec(v, casas)


def resumo(quadros):
    """As linhas de texto do ritmo de um vídeo (sem quadros: nenhuma)."""
    if len(quadros) < 2:
        return []
    saida = []
    fps = fps_por_janela(quadros)
    if fps:
        pior = fps.index(min(fps))
        saida.append(f"ritmo real por janela de {_dec(JANELA_FPS_S, 0)} s ({len(fps)} {'janela' if len(fps) == 1 else 'janelas'}): mediana {_dec(statistics.median(fps))} fps, "
                     f"de {_dec(min(fps))} (a partir de {pior * JANELA_FPS_S:.0f} s) a {_dec(max(fps))}")
    else:
        saida.append(f"ritmo real: menos de {_dec(JANELA_FPS_S, 0)} s de vídeo, sem janela inteira")
    ki = intervalos_de_chave(quadros)
    if ki:
        longos = sum(1 for k in ki if k > CHAVE_LONGA_MS)
        saida.append(f"quadros-chave a cada {_dec(statistics.median(ki) / 1000.0, 2)} s (mediana; de {_dec(min(ki) / 1000.0, 2)} a {_dec(max(ki) / 1000.0, 2)} s; alvo {_dec(ALVO_CHAVE_MS / 1000.0, 0)} s)"
                     + (f"; {longos} {'intervalo' if longos == 1 else 'intervalos'} acima de {_dec(CHAVE_LONGA_MS / 1000.0)} s" if longos else ""))
    else:
        saida.append("quadros-chave: menos de dois, sem intervalo para medir")
    bs = buracos(quadros)
    if bs:
        lista = "; ".join(f"{pts / 1000.0:.1f} s: {_dec(dur / 1000.0)} s sem quadro" for pts, dur in bs[:BURACOS_LISTADOS])
        saida.append(f"{len(bs)} {'buraco' if len(bs) == 1 else 'buracos'} de mais de {_dec(BURACO_MS / 1000.0, 0)} s entre quadros ({lista}{', …' if len(bs) > BURACOS_LISTADOS else ''})")
    else:
        saida.append(f"nenhum buraco de mais de {_dec(BURACO_MS / 1000.0, 0)} s entre quadros")
    return saida


def conferir_soma(formato, soma, tamanho):
    """A linha da soma de `bytes_in_file` contra o `.h264`, só no formato novo; None nos outros (o aviso do índice já os cobre)."""
    if formato != indice_video.NOVO or tamanho is None:
        return None
    if soma == tamanho:
        return f"a soma de bytes_in_file ({soma} B) bate com o .h264"
    return f"a soma de bytes_in_file é {soma} B e o .h264 tem {tamanho} B ({tamanho - soma:+d} B): os quadros podem ficar desalinhados"


def janelas_do_log(linhas):
    """[(instante do fim, segundos, quadros)] das linhas `video fps target=… sent=… (Nframes/T s)` de [linhas]; as sem dia no nome do registro ficam de fora."""
    saida = []
    for x in linhas:
        m = VIDEO_FPS.match(x.texto)
        fim = tempo.instante(x) if m else None
        if m and fim is not None:
            saida.append((fim, float(m.group(2)), int(m.group(1))))
    return saida


def inicio_da_parte(parte, linhas):
    """O instante em que a parte [parte] (2, 3, …) do vídeo abriu: quando a anterior fechou (`video recording part N complete`) em [linhas]; None sem a linha."""
    for x in linhas:
        m = PARTE_COMPLETA.match(x.texto)
        if m and int(m.group(1)) == parte - 1:
            return tempo.instante(x)
    return None


def fps_do_log_e_do_indice(quadros, inicio, janelas):
    """{janelas, fps_log, fps_indice} no período das janelas `video fps` que cabem inteiras no vídeo que abriu em [inicio] (datetime), ou None com menos de `JANELAS_LOG_MIN`.
    Somam-se os quadros e os segundos de todas as janelas (uma janela sozinha erra pela folga do relógio); o índice conta os quadros que caem dentro de cada uma."""
    if len(quadros) < 2 or inicio is None:
        return None
    pts = [q[1] for q in quadros]
    base, fim_do_video = pts[0], (pts[-1] - pts[0]) / 1000.0
    soma_log = soma_segundos = soma_indice = usadas = 0
    for fim, segundos, n in janelas:
        b = (fim - inicio).total_seconds()
        a = b - segundos
        if a < -FOLGA_JANELA_S or b > fim_do_video + FOLGA_JANELA_S:
            continue
        soma_indice += bisect.bisect_left(pts, base + b * 1000.0) - bisect.bisect_left(pts, base + a * 1000.0)
        soma_log += n
        soma_segundos += segundos
        usadas += 1
    if usadas < JANELAS_LOG_MIN or soma_segundos <= 0:
        return None
    return {"janelas": usadas, "fps_log": soma_log / soma_segundos, "fps_indice": soma_indice / soma_segundos}


def diferenca_fps(c):
    """O aviso quando o fps do índice e o das linhas `video fps` do registro diferem muito (`DIFERENCA_FPS_PCT` % e mais de `DIFERENCA_FPS_MIN` fps); None quando batem ou [c] é None."""
    if c is None:
        return None
    maior = max(c["fps_log"], c["fps_indice"])
    dif = abs(c["fps_log"] - c["fps_indice"])
    if maior <= 0 or dif <= DIFERENCA_FPS_MIN or 100.0 * dif / maior <= DIFERENCA_FPS_PCT:
        return None
    return (f"o índice do vídeo dá {_dec(c['fps_indice'], 2)} fps e as {c['janelas']} linhas `video fps` do registro no mesmo período dão {_dec(c['fps_log'], 2)} fps "
            f"({_dec(100.0 * dif / maior, 0)} % de diferença): o que o app diz ter mandado não é o que está gravado")


def linha_da_comparacao(c):
    """A linha de informação da comparação (bate ou não), ou None sem comparação."""
    if c is None:
        return None
    return (f"fps do índice {_dec(c['fps_indice'], 2)} contra {_dec(c['fps_log'], 2)} das {c['janelas']} linhas `video fps` do registro no mesmo período"
            + ("" if diferenca_fps(c) else ": batem"))

