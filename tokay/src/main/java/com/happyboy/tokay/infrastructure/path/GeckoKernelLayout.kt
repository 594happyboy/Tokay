package com.happyboy.tokay.infrastructure.path

import com.happyboy.tokay.utils.DeviceUtil
import java.io.File

/**
 * 解压后的内核目录布局，以及"这份目录算不算装好了"的**唯一**判定。
 *
 * 判定必须只有一处：解压器的复用短路、安装编排器的复用短路与收尾校验、启动健康检查，
 * 问的其实是同一个问题。历史上前两处用的是"标志文件在 + 目录非空"——那是**恒真**的
 * （标志正是解压器自己刚写的，且标志本身就让目录"非空"），于是「健康检查判不健康 → 安装短路
 * 判已装好」形成死循环：坏掉的内核在设备上再也修不回来。
 *
 * 目录即 `filesDir/geckoview/versions/<version>/libs`。
 */
internal object GeckoKernelLayout {

    /** 解压器跑到最后一步才写它；它缺席就代表上次解压中断，目录不能复用。 */
    private const val UNZIP_SUCCESS_FLAG = ".unzip_success"

    fun unzipSuccessFlag(libsDir: File): File = File(libsDir, UNZIP_SUCCESS_FLAG)

    /**
     * 解压产物是否完整可用：解压跑完 + 资源档在 + 本机 ABI 的 so 目录在。
     *
     * 只看前两项（标志+非空）不够：归档层对单条目失败是"计数不抛"（磁盘满、Zip Slip、IO 错都只 ++），
     * 缺文件的目录照样会带着标志落地。
     */
    fun isComplete(libsDir: File, abiTag: String = DeviceUtil.getDeviceAbiTag()): Boolean =
        unzipSuccessFlag(libsDir).isFile &&
            File(File(libsDir, "res"), "base.apk").isFile &&
            File(File(libsDir, "jni"), abiTag).isDirectory
}
