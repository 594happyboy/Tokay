package com.happyboy.tokay.asm.cleaner

import org.gradle.api.GradleException
import org.gradle.api.Project

/**
 * 删除 GeckoView AAR 带进 APK 的 assets（`omni.ja`）：它随 OTA 内核包下发，留在 APK 里只涨体积。
 * 待清理清单**只由调用方传入**，本类不假设任何本地目录存在——「目录不存在」与「没东西要清」是两件事。
 */
class AssetsCleaner {

	static void removeSelectedAssetsForVariant(Project project, String variantName, Set<String> relativeAssetPaths) {
		// 清单为空是**配置错误**（GeckoView 的 assets 一定存在）：静默跳过会把本该剔除的
		// assets 留在 APK 里，而构建仍然是绿的。
		if (relativeAssetPaths == null || relativeAssetPaths.isEmpty()) {
			throw new GradleException(
					"[GeckoViewASM] [assets-cleaner] 待清理的 assets 清单为空。\n" +
							"  这通常意味着 GeckoView AAR 的 assets 没有被扫描到（坐标变了？见 Constants.GECKO_GROUP/GECKO_MODULE）。\n" +
							"  请修复扫描，不要让它静默跳过——否则内核 assets 会悄悄留在 APK 里。")
		}

		project.logger.lifecycle("[GeckoViewASM] [assets-cleaner] 执行 assets 清理")

		def taskProvider = project.tasks.named("merge${variantName.capitalize()}Assets")
		taskProvider.configure { t ->
			doLast {
				try {
					def assetsDir = outputDir.get().asFile
					if (!assetsDir.exists()) return
					relativeAssetPaths.each { String relPath ->
						File target = new File(assetsDir, relPath)
						if (target.exists()) {
							if (target.isDirectory()) target.deleteDir() else target.delete()
							project.logger.lifecycle("[GeckoViewASM] [assets-cleaner] 已删除: ${target}")
						}
					}
				} catch (Throwable th) {
					// 删除动作本身失败必须响亮：留一半 assets 的 APK 比构建失败难查得多。
					throw new GradleException(
							"[GeckoViewASM] [assets-cleaner] 清理 assets 失败: ${th.message}", th)
				}
			}
		}
	}
}
