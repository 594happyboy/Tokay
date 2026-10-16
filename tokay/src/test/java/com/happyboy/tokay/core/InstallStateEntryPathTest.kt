package com.happyboy.tokay.core

import com.happyboy.tokay.domain.models.InstallState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态机的进入路径只有 Idle/Retrying/Failed → Checking（见 `InstallState` 的文档）。
 *
 * 这条护栏是踩出来的：随包模式当初以「没有清单阶段」为由跳过 Checking 直接发 Extracting，守卫把
 * 后续的 Extracting/Success 全丢了——内核其实已经装好，`isSuccess()` 却一直是 false，视图于是永远
 * 用系统 WebView。构建与单测全绿，只有真机验收抓得到。**任何来源都不得跳过 Checking。**
 */
class InstallStateEntryPathTest {

    @Test
    fun `从 Idle 直接进 Success 会被守卫丢掉`() {
        val manager = InstallStateManager()
        manager.updateState(InstallState.Success(version = "v"))
        assertFalse("Success 只能从 Checking 那一路走过来", manager.isSuccess())
    }

    @Test
    fun `先发 Checking 才能走到 Success`() {
        val manager = InstallStateManager()
        manager.updateState(InstallState.Checking)
        manager.updateState(InstallState.Success(version = "v"))
        assertTrue(manager.isSuccess())
    }
}
