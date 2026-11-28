"""Compara dois APKs sem olhar a assinatura: prova que o mesmo código gera os mesmos bytes.

A assinatura v2/v3 do APK vive no "bloco de assinatura" (entre as entradas do zip e o diretório central) e muda de um build para o outro
mesmo com o mesmo código e a mesma chave (o apksigner assina com RSASSA-PSS, que usa um sal aleatório). Tudo o mais tem de bater byte a byte:
as entradas, a ordem, as datas, os extras, o alinhamento e o diretório central. O script tira o bloco de assinatura (e acerta o campo do
fim do zip que diz onde começa o diretório central), tira o SHA-256 do que sobra e compara. Se forem diferentes, diz quais entradas diferem.

Serve para o CI (o APK montado duas vezes) e para quem quer conferir um APK publicado contra o código público: monte o APK da tag e compare os
dois (o publicado leva a assinatura da chave da release, o seu a de debug; o resto é o mesmo).

Uso:
    python3 scripts/apk_compare.py A.apk B.apk
Sai com 0 se iguais (sem a assinatura), 1 se diferentes, 2 se faltou arquivo ou se algum não é um APK.
"""
from __future__ import annotations

import hashlib
import io
import os
import struct
import sys
import zipfile

EOCD_SIG = b"PK\x05\x06"
SIG_BLOCK_MAGIC = b"APK Sig Block 42"
# O fim do zip: 22 bytes mais o comentário (até 65 535); procura-se a assinatura de trás para a frente.
EOCD_MIN = 22
MAX_COMMENT = 0xFFFF


class NotAnApk(Exception):
    pass


def _eocd(data: bytes) -> int:
    start = max(0, len(data) - EOCD_MIN - MAX_COMMENT)
    pos = data.rfind(EOCD_SIG, start)
    if pos < 0 or pos + EOCD_MIN > len(data):
        raise NotAnApk("não é um zip: falta o fim do diretório central")
    return pos


def split(data: bytes) -> tuple[bytes, bytes, bytes]:
    """(o que vem antes do bloco de assinatura, o bloco, o diretório central e o fim do zip). Sem bloco, o do meio vem vazio."""
    eocd = _eocd(data)
    cd_offset = struct.unpack_from("<I", data, eocd + 16)[0]
    if cd_offset > eocd:
        raise NotAnApk("o diretório central começa depois do fim do zip")
    head, tail = data[:cd_offset], data[cd_offset:]
    if len(head) >= 24 and head[-16:] == SIG_BLOCK_MAGIC:
        size = struct.unpack_from("<Q", head, len(head) - 24)[0]  # os bytes depois do primeiro campo de tamanho
        block_start = len(head) - size - 8
        if block_start < 0 or struct.unpack_from("<Q", head, block_start)[0] != size:
            raise NotAnApk("o bloco de assinatura está torto: os dois tamanhos não batem")
        return head[:block_start], head[block_start:], tail
    return head, b"", tail


def without_signature(data: bytes) -> bytes:
    """O APK sem o bloco de assinatura, com o fim do zip acertado para o diretório central de onde ele passou a estar."""
    entries, _block, tail = split(data)
    eocd = _eocd(tail)
    fixed = bytearray(tail)
    struct.pack_into("<I", fixed, eocd + 16, len(entries))
    return entries + bytes(fixed)


def sha256_without_signature(data: bytes) -> str:
    return hashlib.sha256(without_signature(data)).hexdigest()


def _meta(info: zipfile.ZipInfo) -> tuple:
    return (info.date_time, info.compress_type, info.CRC, info.file_size, info.compress_size, info.extra, info.flag_bits,
            info.external_attr, info.create_system, info.header_offset)


def differences(a: bytes, b: bytes) -> list[str]:
    """As diferenças entre dois APKs (sem a assinatura), uma por linha, para quem for ver por quê."""
    out: list[str] = []
    za, zb = zipfile.ZipFile(io.BytesIO(a)), zipfile.ZipFile(io.BytesIO(b))
    names_a = [i.filename for i in za.infolist()]
    names_b = [i.filename for i in zb.infolist()]
    for name in sorted(set(names_a) - set(names_b)):
        out.append(f"só no primeiro: {name}")
    for name in sorted(set(names_b) - set(names_a)):
        out.append(f"só no segundo: {name}")
    common = [n for n in names_a if n in set(names_b)]
    if [n for n in names_a if n in set(common)] != [n for n in names_b if n in set(common)]:
        out.append("a ordem das entradas é outra")
    for name in common:
        ia, ib = za.getinfo(name), zb.getinfo(name)
        if za.read(name) != zb.read(name):
            out.append(f"conteúdo diferente: {name}")
        elif _meta(ia) != _meta(ib):
            fields = ("data", "método", "CRC", "tamanho", "tamanho comprimido", "extra", "flags", "atributos", "sistema", "posição")
            what = [f for f, x, y in zip(fields, _meta(ia), _meta(ib)) if x != y]
            out.append(f"{', '.join(what)} diferente: {name}")
    if not out and without_signature(a) != without_signature(b):
        out.append("as entradas batem, mas o diretório central ou o fim do zip não")
    return out


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__.split("\n\n")[-1].strip(), file=sys.stderr)
        return 2
    try:
        blobs = []
        for path in argv:
            with open(path, "rb") as f:
                blobs.append(f.read())
        hashes = [sha256_without_signature(b) for b in blobs]
        for path, digest, blob in zip(argv, hashes, blobs):
            signed = "hash sem o bloco de assinatura, que o arquivo tinha" if split(blob)[1] else "o arquivo não tinha bloco de assinatura"
            print(f"{digest}  {os.path.basename(path)}  ({signed})")
        if hashes[0] == hashes[1]:
            print("iguais: o resto do APK bate byte a byte (só a assinatura pode mudar).")
            return 0
        print("DIFERENTES:")
        for line in differences(*blobs) or ["os bytes diferem fora das entradas"]:
            print(f"  {line}")
        return 1
    except (OSError, NotAnApk, zipfile.BadZipFile) as erro:
        print(f"erro: {erro}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
