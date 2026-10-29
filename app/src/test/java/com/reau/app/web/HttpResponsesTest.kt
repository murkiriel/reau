package com.reau.app.web

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * As respostas do servidor web, byte a byte: o que o navegador (ou um invasor) recebe. Um erro é só o status, sem corpo
 * e sem dizer o que existe; a página nunca vai para o cache e nunca vaza o PIN por Referer; e o fechamento depois da
 * resposta não pode virar um reset que apague a resposta.
 */
class HttpResponsesTest {
    private fun run(block: (OutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also { block(it) }.toByteArray()

    /** Cabeça e corpo de uma resposta, separados na primeira linha em branco. */
    private fun split(bytes: ByteArray): Pair<String, ByteArray> {
        val text = String(bytes, Charsets.ISO_8859_1)
        val cut = text.indexOf("\r\n\r\n")
        assertTrue("a resposta não tem fim de cabeça: $text", cut >= 0)
        return text.substring(0, cut) to bytes.copyOfRange(cut + 4, bytes.size)
    }

    private fun headers(head: String): Map<String, String> =
        head.split("\r\n").drop(1).associate { it.substringBefore(": ") to it.substringAfter(": ") }

    @Test
    fun `um erro e so o status, sem corpo, sem cache e fechando a conexao`() {
        val bytes = run { HttpResponses.empty(it, 404) }
        assertEquals(
            "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n",
            String(bytes, Charsets.ISO_8859_1),
        )
    }

    @Test
    fun `cada status que o servidor usa tem a sua frase`() {
        val expected = mapOf(
            200 to "OK", 204 to "No Content", 400 to "Bad Request", 403 to "Forbidden", 404 to "Not Found",
            405 to "Method Not Allowed", 429 to "Too Many Requests", 431 to "Request Header Fields Too Large",
            503 to "Service Unavailable",
        )
        for ((status, reason) in expected) {
            val (head, body) = split(run { HttpResponses.empty(it, status) })
            assertEquals(status.toString(), "HTTP/1.1 $status $reason", head.lineSequence().first())
            assertEquals(0, body.size)
        }
    }

    @Test
    fun `um status desconhecido sai com a frase generica, nunca sem frase`() {
        val (head, _) = split(run { HttpResponses.empty(it, 418) })
        assertEquals("HTTP/1.1 418 Error", head.lineSequence().first())
    }

    @Test
    fun `um erro nunca carrega tipo de conteudo nem texto que diga o que existe`() {
        for (status in listOf(400, 403, 404, 405, 429, 431, 503)) {
            val (head, body) = split(run { HttpResponses.empty(it, status) })
            assertFalse(head, head.contains("Content-Type", ignoreCase = true))
            assertEquals("Content-Length: 0", head.lines().first { it.startsWith("Content-Length") })
            assertEquals(0, body.size)
        }
    }

    @Test
    fun `a pagina sai como HTML sem cache, sem Referer e sem adivinhar o tipo`() {
        val body = "<html>REau</html>".toByteArray()
        val (head, got) = split(run { HttpResponses.page(it, body) })
        assertEquals("HTTP/1.1 200 OK", head.lineSequence().first())
        val h = headers(head)
        assertEquals("text/html; charset=utf-8", h["Content-Type"])
        assertEquals("no-referrer", h["Referrer-Policy"])
        assertEquals("nosniff", h["X-Content-Type-Options"])
        assertEquals("no-store", h["Cache-Control"])
        assertEquals("close", h["Connection"])
        assertArrayEquals(body, got)
    }

    @Test
    fun `a pagina leva uma politica de conteudo que fecha o que ela nao usa`() {
        val head = split(run { HttpResponses.page(it, ByteArray(0), "abc123") }).first
        val policy = headers(head)["Content-Security-Policy"]!!
        assertEquals(HttpResponses.policy("abc123"), policy)
        for (closed in listOf("object-src 'none'", "base-uri 'none'", "form-action 'none'", "frame-ancestors 'none'")) {
            assertTrue("$closed em: $policy", closed in policy)
        }
        // o que a pagina usa continua aberto: o video em blob, o favicon em data:, o socket
        for (open in listOf("blob:", "data:", "ws:")) assertTrue("$open em: $policy", open in policy)
        // e nenhum site de fora entra: nada de * nem de endereco http(s)
        assertTrue("sem curinga: $policy", " * " !in " $policy " && "http" !in policy)
    }

    @Test
    fun `a politica nao tem unsafe-inline, o script e o estilo da pagina so rodam com o nonce da resposta`() {
        val nonce = "Zm9vYmFyMTIzNDU2Nzg"
        val policy = headers(split(run { HttpResponses.page(it, ByteArray(0), nonce) }).first)["Content-Security-Policy"]!!
        assertFalse("sem unsafe-inline: $policy", "unsafe-inline" in policy)
        assertFalse("sem unsafe-eval: $policy", "unsafe-eval" in policy)
        val directives = policy.split("; ").associate { it.substringBefore(' ') to it.substringAfter(' ') }
        assertEquals("'nonce-$nonce'", directives["script-src"])
        assertEquals("'nonce-$nonce'", directives["style-src"])
        // as outras diretivas ficam como estavam
        assertEquals("'self' data: blob: ws: wss:", directives["default-src"])
        assertEquals("'none'", directives["object-src"])
        assertEquals("'none'", directives["base-uri"])
        assertEquals("'none'", directives["form-action"])
        assertEquals("'none'", directives["frame-ancestors"])
    }

    @Test
    fun `cada resposta da pagina tem um nonce novo, de 128 bits, no cabecalho`() {
        val nonces = (1..50).map { n ->
            val policy = headers(split(run { HttpResponses.page(it, ByteArray(0)) }).first)["Content-Security-Policy"]!!
            val nonce = Regex("script-src 'nonce-([^']+)'").find(policy)!!.groupValues[1]
            assertTrue("o nonce $n tem de ser base64 de URL, 128 bits: $nonce", Regex("[A-Za-z0-9_-]{22}").matches(nonce))
            assertTrue("style-src com o mesmo nonce: $policy", "style-src 'nonce-$nonce'" in policy)
            nonce
        }
        assertEquals("nenhum nonce se repete", nonces.size, nonces.toSet().size)
    }

    @Test
    fun `as tags de dentro da pagina levam o mesmo nonce do cabecalho, em todo lugar onde a pagina o marca`() {
        val html = "<html><style nonce=\"${HttpResponses.NONCE_MARK}\">a{}</style>ação — 日本" +
            "<script nonce=\"${HttpResponses.NONCE_MARK}\">var a=1;</script><script nonce=\"${HttpResponses.NONCE_MARK}\">var b=2;</script></html>"
        val (head, body) = split(run { HttpResponses.page(it, html.toByteArray(Charsets.UTF_8), "N0nce_x-Y9") })
        val text = String(body, Charsets.UTF_8)
        assertFalse("nenhuma marca sobra: $text", HttpResponses.NONCE_MARK in text)
        assertEquals(3, Regex("""<(script|style) nonce="N0nce_x-Y9">""").findAll(text).count())
        assertTrue(text.contains("ação — 日本"))
        assertTrue(headers(head)["Content-Security-Policy"]!!.contains("script-src 'nonce-N0nce_x-Y9'"))
        assertEquals(body.size.toString(), headers(head)["Content-Length"])
    }

    @Test
    fun `a pagina sem marca sai como veio, byte a byte`() {
        val bytes = byteArrayOf(0x40, 0x4e, 0x4f, -61, -93, 0x40)   // "@NO" cortado, "ã" e "@": nao e a marca
        assertArrayEquals(bytes, split(run { HttpResponses.page(it, bytes, "x") }).second)
    }

    @Test
    fun `a pagina so usa o que a politica deixa abrir, nenhum script ou estilo de fora`() {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }.first { java.io.File(it, "settings.gradle.kts").isFile }
        val html = java.io.File(root, "app/src/main/assets/web/index.html").readText()
        assertFalse("script de fora", Regex("<script[^>]+src=", RegexOption.IGNORE_CASE).containsMatchIn(html))
        assertFalse("estilo de fora", Regex("<link[^>]+rel=[\"']stylesheet", RegexOption.IGNORE_CASE).containsMatchIn(html))
        assertFalse("import de fora", Regex("""\bimport\s*\(|\bimportScripts\(""").containsMatchIn(html))
        assertFalse("rede de fora", Regex("""fetch\(\s*["']https?:|new WebSocket\(\s*["']wss?://""").containsMatchIn(html))
    }

    @Test
    fun `o tamanho declarado e o dos bytes, nao o dos caracteres`() {
        val body = "ação — 日本".toByteArray(Charsets.UTF_8)
        assertTrue("o teste precisa de texto multibyte", body.size > "ação — 日本".length)
        val (head, got) = split(run { HttpResponses.page(it, body) })
        assertEquals(body.size.toString(), headers(head)["Content-Length"])
        assertArrayEquals(body, got)
        assertEquals("ação — 日本", String(got, Charsets.UTF_8))
    }

    @Test
    fun `uma pagina vazia declara tamanho zero e uma grande entra inteira`() {
        val (headEmpty, bodyEmpty) = split(run { HttpResponses.page(it, ByteArray(0)) })
        assertEquals("0", headers(headEmpty)["Content-Length"])
        assertEquals(0, bodyEmpty.size)

        val big = ByteArray(300_000) { (it % 251).toByte() }
        val (headBig, bodyBig) = split(run { HttpResponses.page(it, big) })
        assertEquals("300000", headers(headBig)["Content-Length"])
        assertArrayEquals(big, bodyBig)
    }

    @Test
    fun `cada linha da cabeca termina em CRLF e a cabeca nao tem linha solta`() {
        val bytes = run { HttpResponses.page(it, "x".toByteArray()) }
        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse("sobrou um LF sem CR", text.replace("\r\n", "").contains('\n'))
        assertTrue(text.endsWith("\r\n\r\nx"))
    }

    @Test
    fun `a resposta e empurrada para a rede antes de a conexao fechar`() {
        var flushes = 0
        val out = object : ByteArrayOutputStream() {
            override fun flush() {
                flushes++
                super.flush()
            }
        }
        HttpResponses.empty(out, 403)
        assertTrue("o erro ficou no buffer", flushes >= 1)
        flushes = 0
        HttpResponses.page(out, ByteArray(1))
        assertTrue("a pagina ficou no buffer", flushes >= 1)
        flushes = 0
        HttpResponses.upgrade(out, "abc")
        assertTrue("o upgrade ficou no buffer", flushes >= 1)
    }

    @Test
    fun `o upgrade devolve o 101 com a chave de aceite e nada mais`() {
        val bytes = run { HttpResponses.upgrade(it, "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=") }
        assertEquals(
            "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n",
            String(bytes, Charsets.ISO_8859_1),
        )
    }

    @Test
    fun `o upgrade com a chave de exemplo da RFC 6455 bate com o codec`() {
        // A chave de exemplo da RFC 6455, secao 1.3.
        val accept = WebSocketCodec.acceptKey("dGhlIHNhbXBsZSBub25jZQ==")
        val (head, body) = split(run { HttpResponses.upgrade(it, accept) })
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", headers(head)["Sec-WebSocket-Accept"])
        assertEquals(0, body.size)
    }

    /** Um soquete que anota a ordem do que se faz com ele, sem rede nenhuma. */
    private class RecordingSocket(private val shutdownFails: Boolean = false) : Socket() {
        val calls = mutableListOf<String>()
        var timeout = -1

        override fun shutdownOutput() {
            calls += "shutdownOutput"
            if (shutdownFails) throw IOException("already reset")
        }

        override fun setSoTimeout(timeout: Int) {
            calls += "soTimeout=$timeout"
            this.timeout = timeout
        }

        override fun close() {
            calls += "close"
            super.close()
        }
    }

    /** Um fluxo que entrega bytes sem fim e conta o que foi lido. */
    private class EndlessInput : InputStream() {
        var read = 0L
        override fun read(): Int {
            read++
            return 1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            read += len
            return len
        }
    }

    @Test
    fun `fecha primeiro a saida, depois escuta um pouco, e so entao fecha o soquete`() {
        val socket = RecordingSocket()
        HttpResponses.closeAfterAnswer(socket, ByteArrayInputStream(ByteArray(0)))
        assertEquals(listOf("shutdownOutput", "soTimeout=200", "close"), socket.calls)
        assertTrue(socket.isClosed)
    }

    @Test
    fun `o que o cliente ainda manda e lido e jogado fora ate um teto, sem prender a conexao`() {
        val socket = RecordingSocket()
        val endless = EndlessInput()
        HttpResponses.closeAfterAnswer(socket, endless)
        assertTrue("leu ${endless.read}, menos do que o teto de 64 KB", endless.read >= 64 * 1024)
        assertTrue("leu ${endless.read}, passou muito do teto de 64 KB", endless.read <= 64 * 1024 + 4096)
        assertEquals("close", socket.calls.last())
    }

    @Test
    fun `um cliente que fecha o seu lado encerra a leitura antes do teto`() {
        val socket = RecordingSocket()
        val input = ByteArrayInputStream(ByteArray(10))
        HttpResponses.closeAfterAnswer(socket, input)
        assertEquals("a leitura deveria ter ido ate o fim do fluxo", 0, input.available())
        assertEquals("close", socket.calls.last())
    }

    @Test
    fun `se a leitura falha o soquete fecha do mesmo jeito`() {
        val socket = RecordingSocket()
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("Connection reset")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("Connection reset")
        }
        HttpResponses.closeAfterAnswer(socket, broken)
        assertTrue(socket.isClosed)
    }

    @Test
    fun `se a saida nao fecha limpo o soquete fecha do mesmo jeito`() {
        val socket = RecordingSocket(shutdownFails = true)
        HttpResponses.closeAfterAnswer(socket, ByteArrayInputStream(ByteArray(0)))
        assertTrue(socket.isClosed)
        assertEquals("close", socket.calls.last())
    }
}
