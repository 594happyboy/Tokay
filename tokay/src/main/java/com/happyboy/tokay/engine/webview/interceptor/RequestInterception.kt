package com.happyboy.tokay.engine.webview.interceptor

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

private object RequestInterceptionJsonKeys {
    const val ENABLED = "enabled"
    const val REGEX_OPTIONS = "regexOptions"
    const val LIMITS = "limits"
    const val DEBUG = "debug"
    const val RULES = "rules"

    const val REGEX_IGNORE_CASE = "ignoreCase"
    const val REGEX_MULTILINE = "multiline"
    const val REGEX_DOT_ALL = "dotAll"

    const val LIMITS_MAX_RULES = "maxRules"
    const val LIMITS_MAX_REGEX_LENGTH = "maxRegexLength"

    const val DEBUG_LOG_MATCHES = "logMatches"

    const val RULE_ID = "id"
    const val RULE_PRIORITY = "priority"
    const val RULE_MATCH = "match"
    const val RULE_ACTION = "action"

    const val MATCH_URL_REGEX = "urlRegex"
    const val MATCH_IS_MAIN_FRAME = "isMainFrame"
    const val MATCH_METHODS = "methods"
}

private object RequestInterceptionGeckoKeys {
    const val RULE_ACTION_TYPE = "actionType"
    const val RULE_EFFECT = "effect"

    const val EFFECT_CANCEL = "cancel"
    const val EFFECT_REDIRECT_URL = "redirectUrl"

    const val PATH_ASSETS = "assets"
    const val PATH_DYN = "dyn"
}

private object RequestInterceptionActionTypes {
    const val ALLOW = "allow"
    const val BLOCK = "block"
    const val REDIRECT = "redirect"
    const val SERVE_ASSET = "serveAsset"
    const val SERVE_FILE = "serveFile"
    const val SERVE_BYTES = "serveBytes"
}

data class RequestInterception(
    val enabled: Boolean = true,
    val rules: List<RequestRule> = emptyList(),
    val regexOptions: RegexOptions = RegexOptions(),
    val limits: InterceptionLimits = InterceptionLimits(),
    val debug: DebugOptions = DebugOptions()
)

data class RequestRule(
    val id: String,
    val priority: Int = 0,
    val match: RequestMatch,
    val action: RequestAction
)

data class RequestMatch(
    val urlRegex: String,
    val isMainFrame: Boolean? = null,
    val methods: List<String>? = null
)

data class RegexOptions(
    val ignoreCase: Boolean = false,
    val multiline: Boolean = false,
    val dotAll: Boolean = false
)

data class InterceptionLimits(
    val maxRules: Int = 200,
    val maxRegexLength: Int = 2048,
    val maxServeBytesEntrySizeBytes: Int = 5 * 1024 * 1024,
    val maxServeBytesTotalSizeBytes: Int = 20 * 1024 * 1024
)

data class DebugOptions(
    val logMatches: Boolean = false
)

sealed class RequestAction {
    data object Allow : RequestAction()
    data object Block : RequestAction()
    data class Redirect(val targetUrl: String) : RequestAction()
    data class ServeAsset(val assetPath: String) : RequestAction()
    data class ServeFile(val filesDirRelativePath: String) : RequestAction()
    data class ServeBytes(val key: String) : RequestAction()
}

data class ServeBytesEntry(
    val bytes: ByteArray,
    val mimeType: String,
    val encoding: String? = "utf-8",
    val statusCode: Int = 200,
    val headers: Map<String, String> = emptyMap(),
    val oneShot: Boolean = false
)

data class RequestInterceptionEnv(
    val localDomain: String,
    val filesDirName: String
)

/**
 * GeckoView(WebExtension) 下发格式：拦截规则由 native 侧下发，match 输入为 `details.url`（raw），
 * 故 Kotlin 侧对 `hybrid://asset`/`hybrid://file` 正则做可预测替换；action 预编译为
 * `{cancel|redirectUrl}`，background.js 只做最小匹配与执行。
 *
 * 只下发 background.js 真正会读的字段（`enabled`/`regexOptions`/`limits`/`rules`/`debug`）。
 */
fun RequestInterception.toGeckoJson(env: RequestInterceptionEnv): JSONObject {
    val obj = JSONObject()
    obj.put(RequestInterceptionJsonKeys.ENABLED, enabled)

    val regexOpt = JSONObject()
    regexOpt.put(RequestInterceptionJsonKeys.REGEX_IGNORE_CASE, regexOptions.ignoreCase)
    regexOpt.put(RequestInterceptionJsonKeys.REGEX_MULTILINE, regexOptions.multiline)
    regexOpt.put(RequestInterceptionJsonKeys.REGEX_DOT_ALL, regexOptions.dotAll)
    obj.put(RequestInterceptionJsonKeys.REGEX_OPTIONS, regexOpt)

    val limitsObj = JSONObject()
    limitsObj.put(RequestInterceptionJsonKeys.LIMITS_MAX_RULES, limits.maxRules)
    limitsObj.put(RequestInterceptionJsonKeys.LIMITS_MAX_REGEX_LENGTH, limits.maxRegexLength)
    obj.put(RequestInterceptionJsonKeys.LIMITS, limitsObj)

    val debugObj = JSONObject()
    debugObj.put(RequestInterceptionJsonKeys.DEBUG_LOG_MATCHES, debug.logMatches)
    obj.put(RequestInterceptionJsonKeys.DEBUG, debugObj)

    val localDomain = env.localDomain.trimEnd('/')
    val filesDirName = env.filesDirName.trim('/')

    val rulesArray = JSONArray()
    for (rule in rules.take(limits.maxRules)) {
        val source = rule.match.urlRegex
        if (source.isBlank() || source.length > limits.maxRegexLength) continue

        val geckoRegex = rewriteHybridPrefixesForGecko(source, env)

        val ruleObj = JSONObject()
        ruleObj.put(RequestInterceptionJsonKeys.RULE_ID, rule.id)
        ruleObj.put(RequestInterceptionJsonKeys.RULE_PRIORITY, rule.priority)

        val matchObj = JSONObject()
        matchObj.put(RequestInterceptionJsonKeys.MATCH_URL_REGEX, geckoRegex)
        rule.match.isMainFrame?.let { matchObj.put(RequestInterceptionJsonKeys.MATCH_IS_MAIN_FRAME, it) }
        rule.match.methods?.let { methods ->
            val arr = JSONArray()
            methods.forEach { arr.put(it) }
            matchObj.put(RequestInterceptionJsonKeys.MATCH_METHODS, arr)
        }
        ruleObj.put(RequestInterceptionJsonKeys.RULE_MATCH, matchObj)

        val effectObj = JSONObject()
        val actionType = when (val action = rule.action) {
            is RequestAction.Allow -> RequestInterceptionActionTypes.ALLOW
            is RequestAction.Block -> {
                effectObj.put(RequestInterceptionGeckoKeys.EFFECT_CANCEL, true)
                RequestInterceptionActionTypes.BLOCK
            }
            is RequestAction.Redirect -> {
                effectObj.put(
                    RequestInterceptionGeckoKeys.EFFECT_REDIRECT_URL,
                    mapTargetToLocalDomain(action.targetUrl, env)
                )
                RequestInterceptionActionTypes.REDIRECT
            }
            is RequestAction.ServeAsset -> {
                val relative = action.assetPath.trimStart('/')
                effectObj.put(
                    RequestInterceptionGeckoKeys.EFFECT_REDIRECT_URL,
                    "$localDomain/${RequestInterceptionGeckoKeys.PATH_ASSETS}/$relative"
                )
                RequestInterceptionActionTypes.SERVE_ASSET
            }
            is RequestAction.ServeFile -> {
                val relative = action.filesDirRelativePath.trimStart('/')
                effectObj.put(
                    RequestInterceptionGeckoKeys.EFFECT_REDIRECT_URL,
                    "$localDomain/$filesDirName/$relative"
                )
                RequestInterceptionActionTypes.SERVE_FILE
            }
            is RequestAction.ServeBytes -> {
                val encoded = runCatching { URLEncoder.encode(action.key, Charsets.UTF_8.name()) }.getOrElse { action.key }
                effectObj.put(
                    RequestInterceptionGeckoKeys.EFFECT_REDIRECT_URL,
                    "$localDomain/${RequestInterceptionGeckoKeys.PATH_DYN}/$encoded"
                )
                RequestInterceptionActionTypes.SERVE_BYTES
            }
        }
        ruleObj.put(RequestInterceptionGeckoKeys.RULE_ACTION_TYPE, actionType)
        ruleObj.put(RequestInterceptionGeckoKeys.RULE_EFFECT, effectObj)

        rulesArray.put(ruleObj)
    }
    obj.put(RequestInterceptionJsonKeys.RULES, rulesArray)
    return obj
}

private fun mapTargetToLocalDomain(targetUrl: String, env: RequestInterceptionEnv): String {
    val localDomain = env.localDomain.trimEnd('/')
    val filesDirName = env.filesDirName.trim('/')
    val t = targetUrl.trim()
    return when {
        t.startsWith("hybrid://asset/") -> {
            val relative = t.removePrefix("hybrid://asset/").trimStart('/')
            "$localDomain/${RequestInterceptionGeckoKeys.PATH_ASSETS}/$relative"
        }
        t.startsWith("hybrid://file/") -> {
            val relative = t.removePrefix("hybrid://file/").trimStart('/')
            "$localDomain/$filesDirName/$relative"
        }
        else -> t
    }
}

/**
 * 把规则里的 `hybrid://asset|file` 前缀换成本地服务实际使用的 URL 前缀。
 *
 * 替换进去的内容是**正则源码片段**（background.js 会拿整个字符串去 `new RegExp`），所以前缀必须按
 * **JS** 正则转义。不能用 `Regex.escape`：它产出的是 Java 专有的 `\Q…\E` 引用，而 JS 只把 `\Q`/`\E`
 * 当成字面量 Q/E（非 unicode 模式下的 identity escape），结果是宿主按 `hybrid://asset/…` 写的规则
 * 在 Gecko 上永不命中、且没有任何报错。
 */
internal fun rewriteHybridPrefixesForGecko(source: String, env: RequestInterceptionEnv): String {
    val localDomain = env.localDomain.trimEnd('/')
    val filesDirName = env.filesDirName.trim('/')
    val assetsPrefix = escapeForJsRegex("$localDomain/${RequestInterceptionGeckoKeys.PATH_ASSETS}/")
    val filesPrefix = escapeForJsRegex("$localDomain/$filesDirName/")
    return source
        .replace("hybrid://asset/", assetsPrefix)
        .replace("hybrid:\\/\\/asset\\/", assetsPrefix)
        .replace("hybrid://file/", filesPrefix)
        .replace("hybrid:\\/\\/file\\/", filesPrefix)
}

/** 转义 JS 正则元字符；只用于拼接进正则源码的字面量片段。 */
internal fun escapeForJsRegex(literal: String): String =
    literal.replace(Regex("""[.*+?^${'$'}{}()|\[\]\\/]""")) { "\\" + it.value }
