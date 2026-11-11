package com.foxcloud.resolvedores

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * Byse (la pestana "Fm" de Mundo Donghua) no sirve el video directamente:
 * /api/videos/<codigo> devuelve un sobre cifrado en AES-256-GCM con varias
 * partes de clave y un numero de version que dice cuales dos hay que unir.
 * Aqui se junta la clave, se descifra el sobre y salen las pistas reales
 * (normalmente un master.m3u8 en 1080p).
 *
 * La API solo responde con el Referer del episodio del sitio que lo embebe,
 * asi que el referer que llega desde la extension es obligatorio.
 */
class ByseFox(private val dominio: String = "https://bysekoze.com") : ExtractorApi() {
    override val name = "Fox Byse"
    override val mainUrl = dominio
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val host = url.substringAfter("://").substringBefore('/')
        val codigo = url.substringAfter("/e/", "").substringBefore("/").substringBefore("?").trim()
        if (host.isBlank() || codigo.isBlank()) return

        val cabeceras = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
            "Accept" to "application/json, text/plain, */*"
        )
        val remitente = referer ?: "https://$host/"

        // 1. Camino bueno: el sobre cifrado de la API.
        val crudo = runCatching {
            app.get("https://$host/api/videos/$codigo", headers = cabeceras, referer = remitente).text
        }.getOrNull()

        if (crudo != null && sobreCifrado(crudo, url, callback)) return

        // 2. Camino viejo, por si algun dominio de la familia no usa el sobre:
        //    /embed/details a veces trae "sources" y otras solo el marco.
        val detalles = runCatching {
            app.get("https://$host/api/videos/$codigo/embed/details", headers = cabeceras, referer = remitente).text
        }.getOrNull() ?: ""

        var archivo = Campos.valor(detalles, "file") ?: Campos.valor(detalles, "src")
        if (archivo == null) {
            val marco = Campos.valor(detalles, "embed_frame_url")
            if (marco != null) {
                val destino = if (marco.startsWith("http")) marco else "https://$host/" + marco.trimStart('/')
                val html = runCatching { app.get(destino, headers = cabeceras, referer = remitente).text }.getOrNull() ?: ""
                archivo = Campos.archivo(html)
            }
        }
        if (archivo == null) {
            val html = runCatching { app.get(url, headers = cabeceras, referer = remitente).text }.getOrNull() ?: ""
            archivo = Campos.archivo(html)
        }
        if (archivo == null || !archivo.startsWith("http")) return

        callback(
            newExtractorLink(
                name,
                name,
                archivo,
                if (archivo.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )
    }

    /** Descifra el sobre de /api/videos/<codigo> y emite cada fuente. */
    private suspend fun sobreCifrado(
        crudo: String,
        embed: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playback = runCatching { JSONObject(crudo).getJSONObject("playback") }.getOrNull() ?: return false
        val partes = runCatching {
            val lista = playback.getJSONArray("key_parts")
            (0 until lista.length()).map { lista.optString(it, "") }.filter { it.isNotEmpty() }
        }.getOrNull() ?: return false
        if (partes.isEmpty()) return false

        val version = playback.optInt("version", 1)
        val trozos = partes.mapNotNull { deBase64(it) }
        if (trozos.isEmpty()) return false

        val iv = deBase64(playback.optString("iv")) ?: return false
        val cifrado = deBase64(playback.optString("payload")) ?: return false

        // La version dice, con indices de espejo, que dos trozos forman la
        // clave: el suyo y el 31 menos ella. Como el numero de trozos cambia
        // (30, 32...) y el orden importa, se prueban las parejas razonables
        // hasta que alguna abra el sobre.
        val espejo = 31 - version
        val claves = linkedSetOf<ByteArray>()
        if (version in 1..trozos.size && espejo in 1..trozos.size) {
            claves.add(trozos[version - 1] + trozos[espejo - 1])
        }
        // Los trozos de clave cortos (16 bytes) son los que la forman de verdad.
        val cortos = trozos.filter { it.size == 16 }
        if (cortos.size >= 2) {
            claves.add(cortos[0] + cortos[1])
            claves.add(cortos[1] + cortos[0])
        }
        claves.add(trozos.fold(ByteArray(0)) { uno, otro -> uno + otro })

        val claro = claves.firstNotNullOfOrNull { clave ->
            if (clave.size !in listOf(16, 24, 32)) return@firstNotNullOfOrNull null
            runCatching {
                val descifrador = Cipher.getInstance("AES/GCM/NoPadding")
                descifrador.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(clave, "AES"),
                    GCMParameterSpec(128, iv)
                )
                descifrador.doFinal(cifrado)
            }.getOrNull()
        } ?: return false

        val fuentes = runCatching {
            JSONObject(String(claro, Charsets.UTF_8)).getJSONArray("sources")
        }.getOrNull() ?: return false

        var alguno = false
        for (indice in 0 until fuentes.length()) {
            val fuente = fuentes.optJSONObject(indice) ?: continue
            val url = fuente.optString("url")
            if (url.isEmpty()) continue
            val etiqueta = fuente.optString("label").ifEmpty { "Byse" }
            val esHls = fuente.optString("mime_type").contains("mpegurl") || url.contains(".m3u8")
            alguno = true
            callback(
                newExtractorLink(
                    name,
                    "Byse $etiqueta",
                    url,
                    if (esHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = embed
                    this.quality = etiqueta.filter { it.isDigit() }.toIntOrNull() ?: Qualities.Unknown.value
                }
            )
        }
        return alguno
    }

    private fun deBase64(texto: String): ByteArray? {
        if (texto.isEmpty()) return null
        return runCatching { Base64.decode(texto, Base64.URL_SAFE or Base64.NO_WRAP) }
            .getOrElse { runCatching { Base64.decode(texto, Base64.DEFAULT) }.getOrNull() }
    }
}
