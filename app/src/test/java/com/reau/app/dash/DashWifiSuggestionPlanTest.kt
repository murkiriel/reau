package com.reau.app.dash

import com.reau.app.dash.SuggestionMove.ADD
import com.reau.app.dash.SuggestionMove.CLEAR_ALL
import com.reau.app.dash.SuggestionMove.KEEP
import com.reau.app.dash.SuggestionMove.RESET
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A decisao de mexer ou nao nas sugestoes de Wi-Fi do app. O que importa: com o celular ja associado ao painel por essa sugestao,
 * tirar e recolocar a mesma derruba a associacao (no corrida de 22/11 custou uns 5 s ate a imagem), entao a igual fica quieta.
 */
class DashWifiSuggestionPlanTest {
    private val a = SuggestionKey.of("RE_AAAA_1111", "12345678")
    private val b = SuggestionKey.of("RE_BBBB_2222", "87654321")
    private val c = SuggestionKey.of("RE_CCCC_3333", "")

    private fun plan(desired: SuggestionKey?, current: List<SuggestionKey>?, sdk: Int = 34) = SuggestionPlan.of(desired, current, sdk)

    @Test
    fun `a sugestao igual ja registrada fica como esta, sem adicionar nem remover`() {
        assertEquals(SuggestionPlan(KEEP), plan(a, listOf(a)))
    }

    @Test
    fun `igual, mas sobrando outra - mantem a igual e remove so a outra`() {
        assertEquals(SuggestionPlan(KEEP, listOf(b)), plan(a, listOf(b, a)))
    }

    @Test
    fun `um painel diferente - adiciona o novo e remove o antigo`() {
        assertEquals(SuggestionPlan(ADD, listOf(b)), plan(a, listOf(b)))
    }

    @Test
    fun `lista vazia - so adiciona`() {
        assertEquals(SuggestionPlan(ADD), plan(a, emptyList()))
    }

    @Test
    fun `com a chave desligada ou sem sugestao valida remove tudo, mesmo sem poder ler a lista`() {
        assertEquals(SuggestionPlan(CLEAR_ALL), plan(null, listOf(a, b)))
        assertEquals(SuggestionPlan(CLEAR_ALL), plan(null, emptyList()))
        assertEquals(SuggestionPlan(CLEAR_ALL), plan(null, null, sdk = 29))
    }

    @Test
    fun `varias sobrando - remove todas menos a desejada`() {
        assertEquals(SuggestionPlan(KEEP, listOf(b, c)), plan(a, listOf(b, a, c)))
        assertEquals(SuggestionPlan(ADD, listOf(b, c)), plan(a, listOf(b, c)))
    }

    @Test
    fun `duplicadas da desejada contam como ja registrada e nenhuma e removida`() {
        assertEquals(SuggestionPlan(KEEP), plan(a, listOf(a, a)))
        assertEquals(SuggestionPlan(KEEP, listOf(b, b)), plan(a, listOf(b, a, b, a)))
    }

    @Test
    fun `senha nova no mesmo painel - adiciona (o Android atualiza no lugar) e NAO remove a antiga, que tem a mesma identidade`() {
        val newPassword = SuggestionKey.of(a.ssid, "newpass99")
        assertEquals(SuggestionPlan(ADD), plan(newPassword, listOf(a)))
        assertEquals(SuggestionPlan(ADD, listOf(b)), plan(newPassword, listOf(a, b)))
    }

    @Test
    fun `de rede aberta para senha no mesmo nome muda a seguranca - outra identidade, a antiga sai`() {
        val open = SuggestionKey.of(a.ssid, "")
        assertEquals(SuggestionPlan(ADD, listOf(open)), plan(a, listOf(open)))
        assertEquals(SuggestionPlan(KEEP), plan(open, listOf(open)))
    }

    @Test
    fun `no API 29 nao da para ler o que esta registrado - limpa tudo e adiciona`() {
        assertEquals(SuggestionPlan(RESET), plan(a, null, sdk = 29))
        // Even if a list were given, API 29 has no reliable read: the old behaviour stays.
        assertEquals(SuggestionPlan(RESET), plan(a, listOf(a), sdk = 29))
    }

    @Test
    fun `no API 30 em diante lista ilegivel (leitura falhou) tambem cai no limpar e adicionar`() {
        assertEquals(SuggestionPlan(RESET), plan(a, null, sdk = 30))
        assertEquals(SuggestionPlan(KEEP), plan(a, listOf(a), sdk = 30))
    }

    @Test
    fun `senha em branco e o mesmo que sem senha, como a sugestao e montada`() {
        assertEquals(SuggestionKey("RE_X", ""), SuggestionKey.of("RE_X", "   "))
        assertEquals(SuggestionKey("RE_X", ""), SuggestionKey.of("RE_X", null))
        assertTrue(SuggestionKey.of("RE_X", "").sameIdentity(SuggestionKey.of("RE_X", "")))
        assertFalse(SuggestionKey.of("RE_X", "").sameIdentity(SuggestionKey.of("RE_X", "12345678")))
        assertTrue(SuggestionKey.of("RE_X", "11111111").sameIdentity(SuggestionKey.of("RE_X", "22222222")))
    }
}
