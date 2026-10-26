package com.example.blemesh.data.maps

/**
 * Styles MapLibre 100 % hors ligne : aucune URL http, seulement
 *  - asset://   → fichiers embarqués dans l'APK (polices),
 *  - pmtiles://file://  → l'archive de carte importée dans le stockage privé de l'application.
 */
object MapStyles {
    const val FONT = "NotoSans-Regular"
    const val GLYPHS = "asset://glyphs/{fontstack}/{range}.pbf"
    const val VECTOR_TEMPLATE_ASSET = "map/style-vector.json"
    const val URL_PLACEHOLDER = "__PMTILES_URL__"

    fun pmtilesUrl(absolutePath: String): String = "pmtiles://file://$absolutePath"

    /** Style vectoriel (Protomaps, libellés en français) à partir du gabarit embarqué. */
    fun vector(template: String, absolutePath: String): String =
        template.replace(URL_PLACEHOLDER, pmtilesUrl(absolutePath))

    /** Style pour une archive d'images (PNG/JPEG/WebP). */
    fun raster(absolutePath: String): String = """
        {"version":8,"glyphs":"$GLYPHS",
         "sources":{"carte":{"type":"raster","url":"${pmtilesUrl(absolutePath)}","tileSize":256,
                    "attribution":"© OpenStreetMap"}},
         "layers":[{"id":"fond","type":"background","paint":{"background-color":"#F2EFE9"}},
                   {"id":"carte","type":"raster","source":"carte"}]}
    """.trimIndent()

    /** Aucune carte importée : fond uni, les positions restent affichées. */
    fun empty(): String = """
        {"version":8,"glyphs":"$GLYPHS","sources":{},
         "layers":[{"id":"fond","type":"background","paint":{"background-color":"#E6ECF0"}}]}
    """.trimIndent()
}
