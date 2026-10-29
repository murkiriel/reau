#!/usr/bin/env python3
"""Sorteia quadros (a cada 0,5 s, ao acaso e fixo pela semente) de sessões sem rótulo e monta folhas de contato para rotular à mão.
Uso: amostrar_cego.py PASTA TAG:N [TAG:N ...]    (N = quantos quadros de cada sessão; as folhas saem em PASTA/cego/)
O rótulo NÃO mostra o que a regra diria. Saída: PASTA/cego/amostra.json (a lista de quadros) e PASTA/cego/folha_K.png."""
import csv, json, os, random, subprocess, sys
from PIL import Image, ImageDraw

W = sys.argv[1]
pedidos = [a.split(':') for a in sys.argv[2:]]
rnd = random.Random(15)
amostra = []                       # (tag, indice do quadro a partir de 1, hora do relógio do celular em s)
for tag, n in pedidos:
    rows = list(csv.DictReader(open(f'{W}/{tag}.csv')))
    start = int(open(f'{W}/{tag}.start').read())
    cand, prox = [], 0.0
    for i, r in enumerate(rows):
        t = (start + int(r['pts_ms'])) / 1000.0
        if t >= prox: cand.append((i + 1, t)); prox = t + 0.5
    for i, t in sorted(rnd.sample(cand, min(int(n), len(cand)))): amostra.append((tag, i, t))
os.makedirs(f'{W}/cego', exist_ok=True)
por_tag = {}
for tag, i, t in amostra: por_tag.setdefault(tag, []).append(i)
for tag, idx in por_tag.items():
    os.makedirs(f'{W}/cego/{tag}', exist_ok=True)
    subprocess.run(['./dec5', f'{W}/{tag}.h264', f'{W}/{tag}.csv', f'{W}/cego/{tag}'] + [str(i) for i in idx], check=True, capture_output=True)
json.dump(amostra, open(f'{W}/cego/amostra.json', 'w'))
TW, TH, COLS, LIN = 360, 216, 4, 4
for k in range(0, len(amostra), COLS * LIN):
    parte = amostra[k:k + COLS * LIN]
    folha = Image.new('RGB', (COLS * TW, ((len(parte) + COLS - 1) // COLS) * TH), (40, 40, 40))
    for j, (tag, i, t) in enumerate(parte):
        im = Image.open(f'{W}/cego/{tag}/f{i:05d}.ppm').convert('RGB').resize((TW - 4, TH - 4))
        d = ImageDraw.Draw(im); d.rectangle((0, 0, 44, 18), fill=(0, 0, 0)); d.text((3, 3), str(k + j + 1), fill=(255, 220, 0))
        folha.paste(im, ((j % COLS) * TW + 2, (j // COLS) * TH + 2))
    folha.save(f'{W}/cego/folha_{k // (COLS * LIN) + 1}.png')
print(len(amostra), 'quadros em', (len(amostra) + COLS * LIN - 1) // (COLS * LIN), 'folhas')
