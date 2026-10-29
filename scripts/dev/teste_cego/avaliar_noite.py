#!/usr/bin/env python3
"""Regra de imagem do REau (parte acesa do quadro) contra sessões gravadas de uma noite de roteiros, com a verdade exata.

Uso: avaliar_noite.py PASTA_DECODIFICADOS PASTA_RELATORIOS CALIBRAR_TAG TESTAR_TAG
Os relatórios são os `runs/seed-*/relatorio.json` do roteirista (ferramenta de fora do repositório; hora de cada toque e o que ele abriu). Regra do REau (PictureLook):
numa cópia 64x40, "acesa" = luma >= L (30); mapa se a parte acesa >= M (0,75), escuro se <= D (0,55), senão incerto (sem voto).
Cada sessão é uma leitura a cada 0,5 s, só dentro das janelas em que a tela estava estável (2 s depois do toque até o próximo toque)."""
import csv, glob, itertools, json, struct, sys, collections

W, S, CAL, TEST = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
LIM = [15, 20, 25, 30, 35, 40, 45, 50, 55, 60]
TOTAL = 64 * 40
ATUAL = (30, 0.75, 0.55)

def verdade(st):
    k = st['kind']
    if k == 'bar' and st.get('same'): return 'NAV', 'mapa (atalho do app aberto)'
    if k in ('open', 'bar'): return ('NAV', st['app']) if st['app'] in ('waze', 'maps') else ('OTHER', st['app'])
    if k == 'menu': return 'OTHER', 'menu'
    return None, None

janelas = []
for rep in glob.glob(f'{S}/seed-*/relatorio.json'):
    r = json.load(open(rep))
    if len(r['t0']) != len(r['steps']): continue
    for i, st in enumerate(r['steps']):
        cl, nome = verdade(st)
        if cl is None: continue
        ini = r['t0'][i] + 2.0
        fim = (r['t0'][i + 1] - 0.3) if i + 1 < len(r['t0']) else r['t0'][i] + st['wait']
        if fim - ini >= 0.5: janelas.append((ini, fim, cl, nome))
janelas.sort()

def rotulo(sod):
    lo, hi = 0, len(janelas) - 1
    while lo <= hi:
        m = (lo + hi) // 2
        a, b, cl, nome = janelas[m]
        if sod < a: hi = m - 1
        elif sod >= b: lo = m + 1
        else: return cl, nome
    return None, None

def leituras(tag):
    start = int(open(f'{W}/{tag}.start').read())
    rows = list(csv.DictReader(open(f'{W}/{tag}.csv')))
    lit = open(f'{W}/{tag}/lit.bin', 'rb').read()
    out, prox = [], 0.0
    for i in range(min(len(lit) // 20, len(rows))):
        t_s = (start + int(rows[i]['pts_ms'])) / 1000.0
        if t_s < prox: continue
        prox = t_s + 0.5
        cl, nome = rotulo((t_s - 3 * 3600) % 86400)
        if cl is None: continue
        c = struct.unpack_from('<10H', lit, i * 20)
        out.append((cl, nome, [x / TOTAL for x in c]))
    return out

def voto(shares, regra):
    L, M, D = regra
    f = shares[LIM.index(L)]
    return 'NAV' if f >= M else ('OTHER' if f <= D else None)

def avaliar(dados, regra, mostrar=False, titulo=''):
    por = collections.defaultdict(lambda: [0, 0, 0])      # certo, errado, sem voto
    for cl, nome, sh in dados:
        v = voto(sh, regra); p = por[nome]
        if v is None: p[2] += 1
        elif v == cl: p[0] += 1
        else: p[1] += 1
    tc = sum(p[0] for p in por.values()); te = sum(p[1] for p in por.values()); ts = sum(p[2] for p in por.values()); tot = tc + te + ts
    macro = sum(p[0] / sum(p) for p in por.values()) / len(por)
    if mostrar:
        print(f'\n== {titulo}   regra: acesa = luma >= {regra[0]}, mapa >= {regra[1]:.2f}, escuro <= {regra[2]:.2f}')
        print(f'{"tela real":30s} {"leituras":>8s} {"certo":>7s} {"errado":>7s} {"sem voto":>9s}')
        for nome, p in sorted(por.items(), key=lambda kv: (dict((n, c) for _, n, c in [(0, n, c) for c, n, _ in dados])[kv[0]], kv[0])):
            n = sum(p); print(f'{nome:30s} {n:8d} {100*p[0]/n:6.1f}% {100*p[1]/n:6.1f}% {100*p[2]/n:8.1f}%')
        print(f'-> {tot} leituras: certo {100*tc/tot:.1f}%  errado {100*te/tot:.1f}%  sem voto {100*ts/tot:.1f}%')
        print(f'-> média por tela (cada tela pesa igual): certo {100*macro:.1f}%')
    return tc, te, ts, macro

cal, tst = leituras(CAL), leituras(TEST)
print(f'leituras estáveis: calibração ({CAL}) {len(cal)}, teste ({TEST}) {len(tst)}')
for nome in sorted(set(n for _, n, _ in cal + tst)):
    print(f'  {nome:30s} calibração {sum(1 for _, n, _ in cal if n == nome):5d}  teste {sum(1 for _, n, _ in tst if n == nome):5d}')
avaliar(cal, ATUAL, True, f'REGRA ATUAL em {CAL}')
avaliar(tst, ATUAL, True, f'REGRA ATUAL em {TEST}')

# busca da melhor regra na calibração: menos erros e voto na maioria das leituras
melhores = []
for L in LIM:
    for M in [x / 100 for x in range(40, 100, 5)]:
        for D in [x / 100 for x in range(5, 100, 5)]:
            if D >= M: continue
            tc, te, ts, macro = avaliar(cal, (L, M, D))
            n = tc + te + ts
            melhores.append((te / n + 0.2 * ts / n, macro, (L, M, D), tc, te, ts))
melhores.sort()
print('\nmelhores regras na calibração (custo = erros + 0,2 x sem voto):')
for custo, macro, regra, tc, te, ts in melhores[:6]:
    print(f'  luma>={regra[0]} mapa>={regra[1]:.2f} escuro<={regra[2]:.2f}  custo {custo:.4f}  média por tela {100*macro:.1f}%  (certo {tc}, errado {te}, sem voto {ts})')
melhor = melhores[0][2]
avaliar(cal, melhor, True, f'MELHOR REGRA em {CAL} (calibração)')
avaliar(tst, melhor, True, f'MELHOR REGRA em {TEST} (teste, nunca visto na busca)')
