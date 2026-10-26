package com.example.blemesh.data.maps

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * Carte hors ligne importée par l'utilisateur (une archive .pmtiles copiée dans le stockage privé).
 * Aucun accès réseau : le fichier est préparé une fois sur un PC (voir README), puis copié sur le téléphone.
 */
class MapFileStore(private val context: Context) {

    private val dir = File(context.filesDir, "maps")
    val file = File(dir, "carte.pmtiles")

    /** En-tête de la carte installée, ou null s'il n'y en a pas (ou si elle est illisible). */
    fun header(): PmtilesHeader? {
        if (!file.isFile) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val bytes = ByteArray(PmtilesHeader.SIZE)
                raf.readFully(bytes)
                PmtilesHeader.parse(bytes)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Style MapLibre adapté à la carte installée (ou fond uni si aucune carte). */
    fun currentStyle(): String {
        val h = header() ?: return MapStyles.empty()
        return if (h.isVector) {
            val template = context.assets.open(MapStyles.VECTOR_TEMPLATE_ASSET).bufferedReader().use { it.readText() }
            MapStyles.vector(template, file.absolutePath)
        } else {
            MapStyles.raster(file.absolutePath)
        }
    }

    /** Copie le fichier choisi par l'utilisateur, vérifie qu'il s'agit bien d'un PMTiles v3, puis l'installe. */
    suspend fun import(uri: Uri): Result<PmtilesHeader> = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val tmp = File(dir, "import.tmp")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(IllegalStateException("Fichier illisible"))
            input.use { src -> tmp.outputStream().use { dst -> src.copyTo(dst, 1 shl 16) } }
            val header = RandomAccessFile(tmp, "r").use { raf ->
                if (raf.length() < PmtilesHeader.SIZE) null
                else ByteArray(PmtilesHeader.SIZE).also { raf.readFully(it) }.let(PmtilesHeader::parse)
            } ?: run {
                tmp.delete()
                return@withContext Result.failure(
                    IllegalArgumentException("Ce fichier n'est pas une carte PMTiles v3 (vectorielle ou image)")
                )
            }
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                tmp.delete()
                return@withContext Result.failure(IllegalStateException("Installation de la carte impossible"))
            }
            Result.success(header)
        } catch (e: Exception) {
            tmp.delete()
            Result.failure(IllegalStateException("Import impossible : ${e.message ?: "erreur inconnue"}"))
        }
    }

    fun delete() {
        file.delete()
    }
}
