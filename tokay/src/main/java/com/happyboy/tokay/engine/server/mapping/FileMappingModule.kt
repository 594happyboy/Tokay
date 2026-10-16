package com.happyboy.tokay.engine.server.mapping

import java.io.InputStream

interface FileMappingModule {
    fun canHandle(path: String): Boolean
    fun getFile(path: String): FileMappingResult?
}

data class FileMappingResult(
    val mimeType: String,
    val inputStream: InputStream
)