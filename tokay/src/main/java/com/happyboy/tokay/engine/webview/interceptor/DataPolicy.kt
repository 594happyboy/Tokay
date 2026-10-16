package com.happyboy.tokay.engine.webview.interceptor

sealed class DataPolicy {
    data object Session : DataPolicy()
    data object OneShot : DataPolicy()
    data class Ttl(val ttlMillis: Long) : DataPolicy()
}

