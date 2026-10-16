package com.happyboy.tokay.engine.server

import java.net.URLConnection

object MimeTypeHelper {
    
    private val mimeTypeMap = mapOf(
        ".js" to "application/javascript",
        ".mjs" to "application/javascript",
        ".jsx" to "application/javascript",
        
        ".css" to "text/css",
        
        ".html" to "text/html",
        ".htm" to "text/html",
        
        ".json" to "application/json",
        
        ".png" to "image/png",
        ".jpg" to "image/jpeg",
        ".jpeg" to "image/jpeg",
        ".gif" to "image/gif",
        ".svg" to "image/svg+xml",
        ".webp" to "image/webp",
        ".ico" to "image/x-icon",
        
        ".woff" to "font/woff",
        ".woff2" to "font/woff2",
        ".ttf" to "font/ttf",
        ".otf" to "font/otf",
        ".eot" to "application/vnd.ms-fontobject",
        
        ".pdf" to "application/pdf",
        ".docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        ".xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        ".xls" to "application/vnd.ms-excel",
        
        ".txt" to "text/plain",
        ".xml" to "application/xml",
        ".csv" to "text/csv",
        
        ".zip" to "application/zip",
        ".7z" to "application/x-7z-compressed",
        ".rar" to "application/vnd.rar",
        
        ".mp3" to "audio/mpeg",
        ".wav" to "audio/wav",
        ".ogg" to "audio/ogg",
        
        ".mp4" to "video/mp4",
        ".webm" to "video/webm",
        ".avi" to "video/x-msvideo"
    )
    
    fun getMimeType(fileName: String): String {
        val extension = getFileExtension(fileName).lowercase()
        return mimeTypeMap[extension] ?: URLConnection.guessContentTypeFromName(fileName) ?: "application/octet-stream"
    }
    
    private fun getFileExtension(fileName: String): String {
        val lastDotIndex = fileName.lastIndexOf('.')
        return if (lastDotIndex != -1) {
            fileName.substring(lastDotIndex)
        } else {
            ""
        }
    }
} 