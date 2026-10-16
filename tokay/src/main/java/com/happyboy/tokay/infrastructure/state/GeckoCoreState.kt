package com.happyboy.tokay.infrastructure.state

import kotlinx.serialization.Serializable

/**
 * `meta/state.json` 的跨进程事实，只有两件事：这份状态是哪一代 Java 层写的（[javaApiVersion]），
 * 以及当前激活的内核版本（[currentCoreVersion]）。
 *
 * 刻意**没有**"上一版本"与"已安装版本列表"：内核版本被钉死成 `BuildConfig.GECKOVIEW_VERSION`
 * （`ConfigRepository` 拒绝其余版本的清单，`GeckoCoreHealthChecker` 在代际变化时清空状态），
 * 所以"回滚到另一版"永远不会发生——那套记录只会退化成同一个版本的两条重复条目。
 * 旧版本写下的这两个键会被 `ignoreUnknownKeys` 忽略，不需要迁移。
 */
@Serializable
data class GeckoCoreState(
    val javaApiVersion: String? = null,
    val currentCoreVersion: String? = null
)
