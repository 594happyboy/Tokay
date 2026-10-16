package com.happyboy.tokay.installer.acquisition

import com.happyboy.tokay.domain.models.InstallState
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * 「内核 7z 从哪来」这一步的窄接缝：Remote 实现下载 + 验签 + 校 sha256，Packaged 实现从 APK
 * assets 落一份临时副本。取到本地文件之后的一切（解压、结构校验、落盘 `versions/<v>/libs`、
 * `state.json`、预热）都在 [com.happyboy.tokay.core.InstallOrchestrator] 里，与来源无关。
 */
internal interface KernelPackageAcquirer {

    /**
     * 取到可直接随机访问的内核 7z，进展用 [AcquisitionEvent] 报出。
     *
     * 失败必须**抛异常**、不 emit 失败事件：这次失败要不要再试只有上层 `RetryPolicy` 知道。
     */
    fun acquire(version: String, abiTag: String, workDir: File): Flow<AcquisitionEvent>

    /** 取消正在进行的取包（远程实现会中断下载）。 */
    fun cancel() {}
}

/** 取包过程中的事件。 */
internal sealed interface AcquisitionEvent {

    /** 进行中的状态；由 [com.happyboy.tokay.core.InstallOrchestrator] 写入状态机并往下发。 */
    data class Progress(val state: InstallState) : AcquisitionEvent

    /**
     * 本地包已就绪。
     *
     * @param temporary 临时副本（随包模式从 assets 落盘）：调用方用毕必须删掉，否则 57 MiB 的内核包
     *        会在 `filesDir` 里长期留两份。
     * @param reusedExisting 磁盘上原有的包被直接复用（这次没有下载）：上一次的解压结果一定无效，
     *        调用方要先清掉 `extractDir`，避免新旧文件混在一起通过结构校验。
     */
    data class Acquired(
        val file: File,
        val temporary: Boolean,
        val reusedExisting: Boolean
    ) : AcquisitionEvent
}
