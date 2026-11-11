package com.foxcloud.resolvedores

/**
 * Lectura de campos sueltos en respuestas JSON pequenas.
 * No usa regex ni modelos serializables a proposito: los sitios cambian
 * el orden y los campos opcionales todo el tiempo.
 */
object Campos {
    fun valor(texto: String, clave: String): String? {
        val limpio = normal(texto)
        val marca = "\"" + clave + "\":\""
        val inicio = limpio.indexOf(marca)
        if (inicio < 0) return null
        val valor = limpio.substring(inicio + marca.length).substringBefore('"')
        return valor.ifBlank { null }
    }

    /** Primer enlace de video (.m3u8 o .mp4) dentro de un texto. */
    fun archivo(texto: String): String? =
        Regex("""https?://[^"'\\\s]+?\.(?:m3u8|mp4)[^"'\\\s]*""")
            .find(normal(texto))?.value

    /** Quita espacios y barras escapadas (\/), que es como llegan muchos scripts. */
    private fun normal(texto: String): String =
        texto.replace("\\/", "/").replace(" ", "").replace("\n", "").replace("\r", "").replace("\t", "")
}
