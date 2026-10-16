package com.happyboy.tokay.installer.acquisition

import android.util.Log
import com.happyboy.tokay.LOG_TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 内核随 APK：从 APK assets 取出 `gecko-kernel-<abiTag>.7z`。没有清单、没有 `.sig`、不算 sha256——
 * 信任根就是 APK 签名本身。
 *
 * assets 里的条目只给流，而解压器要按偏移读，所以先落一份临时副本到中转目录
 * （[AcquisitionEvent.Acquired.temporary] = true，解压完由调用方删除），不让 57 MiB 在 `filesDir`
 * 里长期留两份。
 */
internal class PackagedKernelPackageAcquirer(
    private val assets: KernelAssetAccess
) : KernelPackageAcquirer {

    override fun acquire(
        version: String,
        abiTag: String,
        workDir: File
    ): Flow<AcquisitionEvent> = flow {
        val assetName = PackagedKernelContract.requireAssetName(abiTag, assets.listKernelPackageNames())

        val target = withContext(Dispatchers.IO) {
            if (!workDir.exists() && !workDir.mkdirs()) {
                throw IOException("内核包中转目录建不出来: ${workDir.absolutePath}")
            }
            val file = File(workDir, assetName)
            val written = copyAssetToFile(assets.open(assetName), file)
            Log.d(LOG_TAG, "从 APK assets 取出内核包: $assetName -> ${file.absolutePath}（$written 字节，version=$version）")
            file
        }

        emit(AcquisitionEvent.Acquired(file = target, temporary = true, reusedExisting = false))
    }
}
