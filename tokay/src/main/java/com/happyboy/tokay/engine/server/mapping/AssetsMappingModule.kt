package com.happyboy.tokay.engine.server.mapping

import android.content.res.AssetManager
import com.happyboy.tokay.engine.server.MimeTypeHelper

class AssetsMappingModule(
    private val assetManager: AssetManager,
    private val baseUrl: String = "/assets/"
) : FileMappingModule {

    override fun canHandle(path: String): Boolean {
        return path.startsWith(baseUrl)
    }

    override fun getFile(path: String): FileMappingResult? {
        val assetPath = path.removePrefix(baseUrl)
        return try {
            val inputStream = assetManager.open(assetPath)
            val mimeType = MimeTypeHelper.getMimeType(assetPath)
            FileMappingResult(mimeType, inputStream)
        } catch (e: Exception) {
            null
        }
    }
}