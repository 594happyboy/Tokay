package com.happyboy.tokay.infrastructure.state

import android.util.Log
import com.happyboy.tokay.BuildConfig
import com.happyboy.tokay.LOG_TAG
import kotlinx.serialization.json.Json
import java.io.File

internal class GeckoCoreStateStore(
    private val metaDir: File
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val stateFile: File
        get() = File(metaDir, "state.json")

    fun readState(): GeckoCoreState {
        return runCatching {
            if (!stateFile.exists()) {
                return GeckoCoreState()
            }
            val content = stateFile.readText()
            json.decodeFromString(GeckoCoreState.serializer(), content)
        }.onFailure { e ->
            Log.e(LOG_TAG, "读取 GeckoCore state.json 失败，将使用默认空状态", e)
        }.getOrDefault(GeckoCoreState())
    }

    fun writeState(transform: (GeckoCoreState) -> GeckoCoreState): GeckoCoreState {
        if (!metaDir.exists() && !metaDir.mkdirs()) {
            Log.e(LOG_TAG, "无法创建 GeckoCore 元数据目录: ${metaDir.absolutePath}")
            return GeckoCoreState()
        }
        val newState = transform(readState())

        runCatching {
            val tmpFile = File(metaDir, "state.json.tmp")
            val content = json.encodeToString(GeckoCoreState.serializer(), newState)
            tmpFile.writeText(content)
            if (stateFile.exists() && !stateFile.delete()) {
                Log.w(LOG_TAG, "删除旧的 state.json 失败: ${stateFile.absolutePath}")
            }
            if (!tmpFile.renameTo(stateFile)) {
                Log.e(LOG_TAG, "重命名临时 state 文件失败: ${tmpFile.absolutePath}")
            }
        }.onFailure { e ->
            Log.e(LOG_TAG, "写入 GeckoCore state.json 失败", e)
        }

        return newState
    }

    /** 一次成功安装只改两件事：状态属于这一代 Java 层，当前内核是 [coreVersion]。 */
    fun updateOnInstallSuccess(coreVersion: String) {
        writeState { old ->
            old.copy(
                javaApiVersion = BuildConfig.GECKOVIEW_VERSION,
                currentCoreVersion = coreVersion
            )
        }
    }
}
