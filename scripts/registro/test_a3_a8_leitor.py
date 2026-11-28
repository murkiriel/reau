"""Testes do ler_registro.py para as linhas do A3 e do A8 do plano dos pilotos Xiaomi e realme (26/11/2026): o que o piloto diz ter feito no celular
(`keepalive: rider says …`), o que o botão do ajuste do fabricante abriu e a fala `voice: APP_BACK said` depois da retomada.

    python -B scripts\\registro\\test_a3_a8_leitor.py
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
RETOMADA = "resumed after the system ended the process (attempt 1 of 3)"


def lido(*meio):
    texto = PROCESSO.format(hora="10:00:00.000") + LIGADA.format(hora="10:00:01.000") + "".join(meio) + DESLIGADA.format(hora="11:00:00.000")
    d = Path(tempfile.mkdtemp())
    escreve(d, "ride-20261126-100000.log", texto)
    return ler_registro.linha_do_tempo(ler_registro.ler([d]))


class LinhaDoPiloto(unittest.TestCase):
    def test_o_que_o_piloto_diz_ter_feito_entra_na_linha_do_tempo_com_a_palavra_dele(self):
        t = lido(linha("10:00:02.000", "settings", "keepalive: rider says autostart=yes background=no lock=yes battery=?"))
        self.assertIn(
            "10:00:02.000  o piloto diz ter feito (sem conferência): inicialização automática=sim, atividade em segundo plano=não, cadeado nos recentes=sim, bateria sem restrições=?",
            t,
        )

    def test_a_linha_da_mudanca_de_um_item_vale_tambem(self):
        t = lido(linha("10:20:00.000", "ui", "keepalive: rider says autostart=no background=? lock=? battery=yes"))
        self.assertTrue(any("inicialização automática=não, atividade em segundo plano=?" in x and "bateria sem restrições=sim" in x for x in t), t)

    def test_nada_foi_tocado_sai_com_pontos_de_interrogacao_e_nao_com_nao(self):
        t = lido(linha("10:00:02.000", "settings", "keepalive: rider says autostart=? background=? lock=? battery=?"))
        linha_dita = next(x for x in t if "o piloto diz ter feito" in x)
        self.assertNotIn("=não", linha_dita)
        self.assertEqual(4, linha_dita.count("=?"))

    def test_so_a_linha_do_app_conta_com_a_etiqueta_certa(self):
        t = lido(linha("10:00:02.000", "aa", "keepalive: rider says autostart=yes background=no lock=yes battery=?"))
        self.assertFalse(any("o piloto diz ter feito" in x for x in t), t)


class BotaoDoFabricante(unittest.TestCase):
    def test_a_alternativa_que_abriu_sai_na_linha_do_tempo(self):
        t = lido(linha("10:30:00.000", "ui", "keepalive: the Autostart screen did not open (alternative 1 of 3): SecurityException"),
                 linha("10:30:00.100", "ui", "keepalive: opened the maker's permissions page of the app (alternative 2 of 3)"))
        self.assertIn("10:30:00.000  ajuste do fabricante: the Autostart screen did not open (alternative 1 of 3): SecurityException", t)
        self.assertIn("10:30:00.100  ajuste do fabricante: opened the maker's permissions page of the app (alternative 2 of 3)", t)

    def test_o_aviso_unico_respondido_sai_tambem(self):
        t = lido(linha("10:31:00.000", "ui", "keepalive: the one-time notice was answered (understood)"))
        self.assertIn("10:31:00.000  ajuste do fabricante: the one-time notice was answered (understood)", t)


class FalaDoRetorno(unittest.TestCase):
    def test_a_fala_vem_depois_da_linha_da_retomada(self):
        t = lido(linha("10:00:02.500", "projection", RETOMADA), linha("10:00:05.000", "audio", "voice: APP_BACK said"))
        retomou = next(i for i, x in enumerate(t) if "projeção retomada depois de o sistema fechar o REau" in x)
        falou = next(i for i, x in enumerate(t) if "fala no fone" in x)
        self.assertLess(retomou, falou)
        self.assertIn("10:00:05.000  fala no fone: o app voltou sozinho, «O REau voltou» (APP_BACK)", t)

    def test_outra_frase_dita_sai_pelo_nome_da_frase(self):
        t = lido(linha("10:10:00.000", "audio", "voice: PHONE_HOT said"))
        self.assertIn("10:10:00.000  fala no fone: PHONE_HOT", t)

    def test_a_frase_que_nao_foi_dita_nao_vira_fala(self):
        t = lido(linha("10:10:00.000", "audio", "voice: APP_BACK not said, the speech engine is not ready"))
        self.assertFalse(any("fala no fone" in x for x in t), t)

    def test_a_linha_de_fala_com_a_etiqueta_de_outro_lugar_nao_conta(self):
        t = lido(linha("10:10:00.000", "aa", "voice: APP_BACK said"))
        self.assertFalse(any("fala no fone" in x for x in t), t)


class LinhaDoBaldeEDaDepuracao(unittest.TestCase):
    """S1 (26/11/2026): a linha `app standby:` diz também a depuração USB e as opções de desenvolvedor, e a linha do tempo as mostra."""

    def test_a_depuracao_usb_e_as_opcoes_de_desenvolvedor_entram_na_linha_do_tempo(self):
        t = lido(linha("10:00:02.000", "device", "app standby: bucket=working_set background-restricted=no adb=on devopts=off"))
        self.assertIn(
            "10:00:02.000  o Android pôs o app no balde de uso working_set, restrição de segundo plano: não; depuração USB ligada, opções de desenvolvedor desligadas", t)

    def test_o_que_nao_foi_lido_diz_sem_leitura(self):
        t = lido(linha("10:00:02.000", "device", "app standby: bucket=unknown background-restricted=? adb=? devopts=?"))
        self.assertIn(
            "10:00:02.000  o Android pôs o app no balde de uso sem leitura, restrição de segundo plano: sem leitura; depuração USB sem leitura, opções de desenvolvedor sem leitura", t)

    def test_a_linha_de_antes_da_depuracao_ainda_vale_sem_as_duas_frases(self):
        t = lido(linha("10:00:02.000", "device", "app standby: bucket=restricted background-restricted=yes"))
        achada = next(x for x in t if "balde de uso" in x)
        self.assertIn("balde de uso restricted, restrição de segundo plano: sim", achada)
        self.assertNotIn("depuração", achada)

    def test_a_mudanca_entra_uma_vez_cada(self):
        t = lido(linha("10:00:02.000", "device", "app standby: bucket=active background-restricted=no adb=off devopts=on"),
                 linha("10:30:00.000", "device", "app standby: bucket=active background-restricted=no adb=on devopts=on"))
        achadas = [x for x in t if "balde de uso" in x]
        self.assertEqual(2, len(achadas))
        self.assertIn("depuração USB desligada", achadas[0])
        self.assertIn("depuração USB ligada", achadas[1])

    def test_so_a_linha_do_app_com_a_etiqueta_device_conta(self):
        t = lido(linha("10:00:02.000", "aa", "app standby: bucket=active background-restricted=no adb=on devopts=on"))
        self.assertFalse(any("balde de uso" in x for x in t), t)


if __name__ == "__main__":
    unittest.main()
