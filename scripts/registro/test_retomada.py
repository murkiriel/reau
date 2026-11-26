"""Testes do ler_registro.py para a retomada da projeção depois de o sistema fechar o REau (0.4.0, plano 34 item 4): o app escreve
`[projection] resumed after the system ended the process (attempt N of 3)` dentro da projeção nova, e a linha do tempo diz que ela voltou sozinha.

    python -B scripts\\registro\\test_retomada.py
"""
import sys
import tempfile
import unittest
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))
import ler_registro  # noqa: E402
from test_ler_registro import escreve, linha  # noqa: E402

PROCESSO = "{hora}  +     0ms  ==== process start: app — app 0.4.0 (1, abc), realme RMX3, Android 15 (API 35) ====\n"
LIGADA = "{hora}  +  1000ms  ---- projeção ligada: projection ----\n"
DESLIGADA = "{hora}  +  1000ms  ---- projeção desligada: projection ----\n"
TEXTO = "projeção retomada depois de o sistema fechar o REau, tentativa"


def lido(texto):
    d = Path(tempfile.mkdtemp())
    escreve(d, "ride-20261125-100000.log", texto)
    return "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([d])))


def viagem(*retomadas):
    """Uma projeção que o sistema encerra e `len(retomadas)` retomadas em seguida; cada retomada diz a sua linha (ou nenhuma, se for None)."""
    texto = PROCESSO.format(hora="10:00:00.000") + LIGADA.format(hora="10:00:01.000") + linha("10:05:00.000", "device", "battery=80%")
    hora = 6
    for dita in retomadas:
        texto += PROCESSO.format(hora=f"10:{hora:02d}:00.000") + LIGADA.format(hora=f"10:{hora:02d}:02.000")
        if dita is not None:
            texto += linha(f"10:{hora:02d}:02.500", "projection", dita)
        hora += 2
    return texto + DESLIGADA.format(hora="11:00:00.000")


class ProjecaoRetomada(unittest.TestCase):
    def test_a_retomada_aparece_na_linha_do_tempo_com_o_numero_da_tentativa(self):
        t = lido(viagem("resumed after the system ended the process (attempt 1 of 3)"))
        self.assertIn(f"10:06:02.500  {TEXTO} 1 de 3", t)

    def test_cada_tentativa_tem_a_sua_linha_na_ordem(self):
        t = lido(viagem(*[f"resumed after the system ended the process (attempt {n} of 3)" for n in (1, 2, 3)]))
        achadas = [l for l in t.splitlines() if TEXTO in l]
        self.assertEqual([l.rsplit("tentativa ", 1)[1] for l in achadas], ["1 de 3", "2 de 3", "3 de 3"])

    def test_a_projeção_de_antes_continua_acabando_em_o_app_recomeçou(self):
        t = lido(viagem("resumed after the system ended the process (attempt 1 of 3)"))
        self.assertIn("o app recomeçou", t)

    def test_uma_projeção_ligada_pelo_piloto_nao_diz_retomada(self):
        t = lido(viagem(None))
        self.assertNotIn(TEXTO, t)

    def test_a_linha_que_diz_que_nao_retomou_nao_vira_retomada(self):
        t = lido(viagem("not resumed after the system ended the process: the last sign of life was 41 min ago, over 30"))
        self.assertNotIn(TEXTO, t)

    def test_so_a_linha_do_app_conta_e_so_com_a_etiqueta_projection(self):
        outra = linha("10:06:02.500", "aa", "resumed after the system ended the process (attempt 1 of 3)")
        d = Path(tempfile.mkdtemp())
        escreve(d, "ride-20261125-100000.log", PROCESSO.format(hora="10:00:00.000") + LIGADA.format(hora="10:00:01.000") + outra + DESLIGADA.format(hora="11:00:00.000"))
        self.assertNotIn(TEXTO, "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([d]))))


if __name__ == "__main__":
    unittest.main()
