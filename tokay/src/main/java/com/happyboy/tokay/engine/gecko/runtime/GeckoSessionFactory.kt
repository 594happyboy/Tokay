package com.happyboy.tokay.engine.gecko.runtime

import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession

internal object GeckoSessionFactory {
    fun create(runtime: GeckoRuntime): GeckoSession {
        return GeckoSession().apply { open(runtime) }
    }
}
