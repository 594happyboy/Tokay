package com.happyboy.tokay.scheduler

import android.util.Log

/** 预热决策与轻量统计；约束：始终 Gecko 优先，系统 WebView 仅在 Gecko 未就绪时兜底。 */
internal class PrewarmIntelligence(
    private val prewarmUtils: PrewarmUtils
) {

    companion object {
        private const val TAG = "PrewarmIntelligence"

        private const val BATTERY_LOW_THRESHOLD = 30
        private const val BATTERY_NORMAL_THRESHOLD = 40
        private const val BATTERY_HIGH_THRESHOLD = 50
    }

    fun shouldPrewarmNow(urgency: PrewarmUrgency): Boolean {
        return prewarmUtils.canPrewarmNow(ignoreCooldown = urgency == PrewarmUrgency.USER_ACTION) &&
            prewarmUtils.hasEnoughMemory() &&
            isValidBatteryCondition()
    }

    fun getPrewarmStrategy(urgency: PrewarmUrgency): PrewarmStrategy {
        val batteryLevel = prewarmUtils.getBatteryLevel()
        val isCharging = prewarmUtils.isCharging()
        val isInForeground = prewarmUtils.isAppInForeground()
        val hasEnoughMemory = prewarmUtils.hasEnoughMemory()

        return when {
            batteryLevel <= BATTERY_LOW_THRESHOLD && !isCharging -> {
                Log.d(TAG, "电量过低且未充电，跳过预热")
                PrewarmStrategy.SKIP
            }

            batteryLevel <= BATTERY_NORMAL_THRESHOLD || !hasEnoughMemory -> {
                Log.d(TAG, "资源受限，采用保守策略")
                if (batteryLevel <= BATTERY_LOW_THRESHOLD + 5) PrewarmStrategy.DELAYED_GECKO else PrewarmStrategy.GECKO_NORMAL
            }

            isCharging && isInForeground && hasEnoughMemory -> {
                Log.d(TAG, "理想条件，采用积极策略")
                if (urgency == PrewarmUrgency.USER_ACTION) PrewarmStrategy.GECKO_AGGRESSIVE else PrewarmStrategy.GECKO_NORMAL
            }

            batteryLevel > BATTERY_HIGH_THRESHOLD && isInForeground -> {
                Log.d(TAG, "正常条件，采用标准策略")
                if (urgency == PrewarmUrgency.USER_ACTION) PrewarmStrategy.GECKO_AGGRESSIVE else PrewarmStrategy.GECKO_NORMAL
            }

            batteryLevel > BATTERY_NORMAL_THRESHOLD -> {
                Log.d(TAG, "基础条件，预热 Gecko")
                PrewarmStrategy.GECKO_NORMAL
            }

            else -> {
                Log.d(TAG, "延迟预热，等待更好时机")
                PrewarmStrategy.DELAYED_GECKO
            }
        }
    }

    private fun isValidBatteryCondition(): Boolean {
        val batteryLevel = prewarmUtils.getBatteryLevel()
        val isCharging = prewarmUtils.isCharging()
        if (batteryLevel < BATTERY_LOW_THRESHOLD && !isCharging) {
            Log.d(TAG, "电量不足且未充电，跳过预热")
            return false
        }
        return true
    }
}
