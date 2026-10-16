package com.happyboy.tokay.domain.models

import kotlinx.serialization.Serializable

/**
 * SDK 配置文件数据模型：只描述**内核**下载。
 *
 * 模型字段与仓库里的 `sdkconfig.json` 必须**同时**增删：`ConfigRepository` 用的是
 * `Json { ignoreUnknownKeys = true }`，线上清单多带字段只会被忽略；反过来**只删仓库配置、保留
 * 必填模型字段**，会让解析期抛 `MissingFieldException`。
 */
@Serializable
data class SdkConfig(
    val geckoJavaVersion: String,
    val so: Map<String, SoInfo>
)

/**
 * so 包信息（按 ABI）。
 *
 * `sha256` 可空只因为它是 JSON 模型（"字段不存在"必须能被解析出来再判失败，而不是在解析期抛
 * `MissingFieldException`）；`KernelManifestPolicy.requireSoInfo` 会拒绝 sha256 缺失/为空的条目。
 */
@Serializable
data class SoInfo(
    val url: String,
    val sha256: String? = null
)
