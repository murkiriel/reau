#!/usr/bin/env python3
"""Teste cego da regra de imagem do REau: quadros sorteados de sessões que o REau nunca viu (amostrar_cego.py), rotulados à mão
olhando as folhas de contato, ANTES de ver o que a regra diria. Rótulos: N = Waze/Maps (mapa), M = menu, S = Spotify, T = transição
(tela preta, abertura do Android Auto, app carregando: fora da conta).
Uso: avaliar_cego.py PASTA ROTULOS.json"""
import json, struct, sys, collections
W, ROT = sys.argv[1], json.load(open(sys.argv[2]))
LIM = [15, 20, 25, 30, 35, 40, 45, 50, 55, 60]
amostra = json.load(open(f'{W}/cego/amostra.json'))
def faixa(a, b): return {i: ROT['grupos'][g] for g, (lo, hi) in ROT['faixas'].items() for i in range(lo, hi + 1)}
rotulo = {}
for nome, lista in ROT['rotulos'].items():
    for item in lista:
        lo, hi = (item, item) if isinstance(item, int) else item
        for i in range(lo, hi + 1): rotulo[i] = nome
grupo = {}
for nome, lista in ROT['grupos'].items():
    for item in lista:
        lo, hi = (item, item) if isinstance(item, int) else item
        for i in range(lo, hi + 1): grupo[i] = nome
dados = []
for k, (tag, idx, t) in enumerate(amostra, 1):
    r = rotulo[k]
    if r == 'T': continue
    c = struct.unpack_from('<10H', open(f'{W}/{tag}/lit.bin', 'rb').read(), (idx - 1) * 20)
    dados.append((k, 'NAV' if r == 'N' else 'OTHER', grupo.get(k, r), [x / 2560 for x in c]))
def voto(sh, regra):
    L, M, D = regra; f = sh[LIM.index(L)]
    return 'NAV' if f >= M else ('OTHER' if f <= D else None)
def mostrar(regra, titulo):
    por = collections.defaultdict(lambda: [0, 0, 0])
    for k, cl, g, sh in dados:
        v = voto(sh, regra); p = por[g]
        if v is None: p[2] += 1
        elif v == cl: p[0] += 1
        else: p[1] += 1
    print(f'\n== {titulo}: acesa = luma >= {regra[0]}, mapa >= {regra[1]:.2f}, escuro <= {regra[2]:.2f}')
    print(f'{"tela":24s} {"quadros":>7s} {"certo":>7s} {"errado":>7s} {"sem voto":>9s}')
    for g, p in sorted(por.items()):
        n = sum(p); print(f'{g:24s} {n:7d} {100*p[0]/n:6.1f}% {100*p[1]/n:6.1f}% {100*p[2]/n:8.1f}%')
    tc = sum(p[0] for p in por.values()); te = sum(p[1] for p in por.values()); ts = sum(p[2] for p in por.values()); n = tc + te + ts
    print(f'-> {n} quadros: certo {tc} ({100*tc/n:.1f}%), errado {te}, sem voto {ts}')
    return por
mostrar(tuple(ROT['regra_atual']), 'REGRA ATUAL DO REAU')
mostrar(tuple(ROT['regra_noite']), 'REGRA ACHADA NA CALIBRAÇÃO DA NOITE')
print('\nvalores da parte acesa (luma >= 30 e >= 40) por tela, mínimo / mediana / máximo:')
for g in sorted(set(x[2] for x in dados)):
    for L in (30, 40):
        v = sorted(x[3][LIM.index(L)] for x in dados if x[2] == g)
        print(f'  {g:22s} luma>={L}: {v[0]:.2f} / {v[len(v)//2]:.2f} / {v[-1]:.2f}  ({len(v)} quadros)')
