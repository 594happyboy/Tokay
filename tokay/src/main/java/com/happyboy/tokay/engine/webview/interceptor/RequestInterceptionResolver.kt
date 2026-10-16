package com.happyboy.tokay.engine.webview.interceptor

import android.util.Log

/** 把 native 下发的规则编译成匹配器；**没有任何规则命中时请求照常放行**，拦截必须显式配置。 */
internal object RequestInterceptionResolver {
    private const val TAG = "RequestInterception"

    data class CompiledInterception(
        val debug: DebugOptions,
        val rules: List<CompiledRule>
    )

    data class CompiledRule(
        val id: String,
        val isMainFrame: Boolean?,
        val methodsUppercase: Set<String>?,
        val regex: Regex,
        val action: RequestAction
    )

    fun compileOrNull(interception: RequestInterception?): CompiledInterception? {
        val cfg = interception ?: return null
        if (!cfg.enabled) return null
        if (cfg.rules.isEmpty()) return null

        val limits = cfg.limits
        val regexOptions = buildRegexOptions(cfg.regexOptions)

        val compiledRules = mutableListOf<CompiledRule>()
        val sortedRules = cfg.rules
            .asSequence()
            .take(limits.maxRules)
            .filter { it.match.urlRegex.isNotBlank() }
            .filter { it.match.urlRegex.length <= limits.maxRegexLength }
            .sortedWith(compareByDescending<RequestRule> { it.priority })
            .toList()

        for (rule in sortedRules) {
            val regex = try {
                Regex(rule.match.urlRegex, regexOptions)
            } catch (e: Exception) {
                Log.w(TAG, "非法正则，跳过: ruleId=${rule.id}, regex=${rule.match.urlRegex}", e)
                continue
            }

            compiledRules.add(
                CompiledRule(
                    id = rule.id,
                    isMainFrame = rule.match.isMainFrame,
                    methodsUppercase = rule.match.methods?.map { it.uppercase() }?.toSet(),
                    regex = regex,
                    action = rule.action
                )
            )
        }

        return CompiledInterception(debug = cfg.debug, rules = compiledRules)
    }

    fun resolveActionOrNull(
        compiled: CompiledInterception?,
        url: String,
        isMainFrame: Boolean,
        method: String?
    ): RequestAction? {
        val cfg = compiled ?: return null
        val normalizedMethod = method?.uppercase()

        for (rule in cfg.rules) {
            if (rule.isMainFrame != null && rule.isMainFrame != isMainFrame) continue
            if (rule.methodsUppercase != null && normalizedMethod != null) {
                if (!rule.methodsUppercase.contains(normalizedMethod)) continue
            }

            val matched = try {
                rule.regex.containsMatchIn(url)
            } catch (e: Exception) {
                Log.w(TAG, "正则匹配异常，跳过: ruleId=${rule.id}, url=$url", e)
                continue
            }
            if (!matched) continue

            if (cfg.debug.logMatches) {
                Log.d(TAG, "命中规则: ruleId=${rule.id}, url=$url, action=${rule.action}")
            }
            return rule.action
        }

        return null
    }

    private fun buildRegexOptions(options: RegexOptions): Set<RegexOption> {
        val set = mutableSetOf<RegexOption>()
        if (options.ignoreCase) set.add(RegexOption.IGNORE_CASE)
        if (options.multiline) set.add(RegexOption.MULTILINE)
        if (options.dotAll) set.add(RegexOption.DOT_MATCHES_ALL)
        return set
    }
}
