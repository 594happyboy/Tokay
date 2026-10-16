package com.happyboy.tokay.asm.cleaner

import org.gradle.api.Project

/**
 * 在 mergeNativeLibs 任务后按 ABI 映射删除 GeckoView 的 .so 文件，减少 APK 体积；变体级生效。
 */
class GeckoSoExcluder {


	static void removeSelectedSoOnMergeNativeLibs(Project project, String variantName, Map<String, Set<String>> abiMap) {
		if (abiMap == null || abiMap.isEmpty()) {
			project.logger.lifecycle("[GeckoViewASM] [so-cleaner] abiMap is empty for variant ${variantName}, skip")
			return
		}
		def taskName = "merge${variantName.capitalize()}NativeLibs"
		def taskProvider = project.tasks.findByName(taskName) ? project.tasks.named(taskName) : null
		if (taskProvider == null) {
			project.logger.lifecycle("[GeckoViewASM] Task not found: ${taskName}, skip so cleanup")
			return
		}
		taskProvider.configure { t ->
			doLast {
				try {
					File outDir = null
					try { if (hasProperty('outputDir')) outDir = outputDir.get().asFile } catch (Throwable ignored) {}
					if (outDir == null) {
						def files = outputs?.files
						if (files != null) {
							def dirCandidate = files.find { File f -> f.isDirectory() }
							if (dirCandidate != null) outDir = dirCandidate
						}
					}
					project.logger.lifecycle("[GeckoViewASM] [so-cleaner] outDir for ${taskName}: ${outDir}")
					if (outDir == null || !outDir.exists()) return
					abiMap.each { String abi, Set<String> soNames ->
						soNames.each { String soName ->
							File target = new File(outDir, "lib/${abi}/${soName}")
							if (target.exists()) {
								target.delete()
								project.logger.lifecycle("[GeckoViewASM] [so-cleaner] 已删除: ${target}")
							}
						}
					}
				} catch (Throwable th) {
					project.logger.warn("[GeckoViewASM] [so-cleaner] 清理 so 时发生异常: ${th.message}")
				}
			}
		}
	}
}