package com.happyboy.tokay.asm.scanner

import org.gradle.api.Project
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class AarScanner {

	/** 已解析的 AAR + 它的模块坐标版本（断言任务要把版本写进错误信息） */
	static class ResolvedAar {
		final File file
		final String version

		ResolvedAar(File file, String version) {
			this.file = file
			this.version = version
		}
	}

	/**
	 * 从指定配置解析（已解析）工件中过滤出给定 group/module 的 AAR 文件。
	 * 调用会触发该配置的解析，因此只能在允许任务期解析的时机调用。
	 */
	static Set<File> resolveAarFilesFromConfiguration(Project project, String configurationName, String group, String module) {
		Set<File> result = new LinkedHashSet<>()
		resolveAarArtifactsFromConfiguration(project, configurationName, group, module).each { ResolvedAar aar ->
			result.add(aar.file)
		}
		return result
	}

	/**
	 * 同 {@link #resolveAarFilesFromConfiguration}，但额外带上每个 AAR 的坐标版本，供构建期断言
	 * 把 "当前 geckoviewVersion" 写进错误信息。
	 */
	static List<ResolvedAar> resolveAarArtifactsFromConfiguration(Project project, String configurationName, String group, String module) {
		List<ResolvedAar> result = new ArrayList<>()
		try {
			def conf = project.configurations.findByName(configurationName)
			if (conf == null) {
				project.logger.lifecycle("[GeckoViewASM] Configuration not found: ${configurationName}")
				return result
			}
			def view = conf.incoming.artifactView { spec ->
				spec.lenient = true
			}
			def artifacts = view.artifacts
			artifacts.artifacts.each { ar ->
				try {
					def cid = ar.id.componentIdentifier
					// 仅匹配外部模块依赖
					if (cid.hasProperty('group') && cid.group == group && cid.module == module) {
						def file = ar.file
						if (file != null && file.name.endsWith('.aar')) {
							def version = cid.hasProperty('version') ? cid.version?.toString() : null
							result.add(new ResolvedAar(file, version))
							project.logger.lifecycle("[GeckoViewASM] Found AAR from ${configurationName}: " + file.absolutePath)
						}
					}
				} catch (Throwable ignore) {}
			}
		} catch (Throwable t) {
			project.logger.warn("[GeckoViewASM] Failed to resolve AARs from configuration ${configurationName}: " + t.message)
		}
		return result
	}

	static Map<String, Set<String>> readAarJniContents(Project project, File aarFile) {
		Map<String, Set<String>> abiToSoNames = [:].withDefault { new LinkedHashSet<String>() }
		if (aarFile == null || !aarFile.exists()) return abiToSoNames
		try {
			new ZipFile(aarFile).withCloseable { ZipFile zf ->
				zf.entries().each { ZipEntry entry ->
					if (entry == null) return
					String name = entry.getName()
					if (!name.startsWith("jni/")) return
					if (entry.isDirectory()) return
					String[] parts = name.split('/')
					if (parts.length != 3) return
					String abi = parts[1]
					String soName = parts[2]
					if (!soName.endsWith('.so')) return
					abiToSoNames[abi] << soName
				}
			}
		} catch (Throwable t) {
			project.logger.warn("[GeckoViewASM] Failed to read jni from AAR: " + aarFile.name + ": " + t.message)
		}
		return abiToSoNames
	}

	static Set<String> readAarAssets(Project project, File aarFile) {
		Set<String> assets = new LinkedHashSet<>()
		if (aarFile == null || !aarFile.exists()) return assets
		try {
			new ZipFile(aarFile).withCloseable { ZipFile zf ->
				zf.entries().each { ZipEntry entry ->
					if (entry == null) return
					String name = entry.getName()
					if (!name.startsWith("assets/")) return
					if (entry.isDirectory()) return
					String rel = name.substring("assets/".length())
					if (rel != null && rel.length() > 0) {
						assets << rel
					}
				}
			}
		} catch (Throwable t) {
			project.logger.warn("[GeckoViewASM] Failed to read assets from AAR: " + aarFile.name + ": " + t.message)
		}
		return assets
	}

	static Map<String, Set<String>> collectAbiMapFromConfiguration(Project project, String configurationName, String group, String module) {
		Map<String, Set<String>> collected = [:].withDefault { new LinkedHashSet<String>() }
		def aars = resolveAarFilesFromConfiguration(project, configurationName, group, module)
		if (aars && !aars.isEmpty()) {
			aars.each { File f ->
				def map = readAarJniContents(project, f)
				map.each { String abi, Set<String> sos -> collected[abi].addAll(sos) }
			}
		} else {
			project.logger.lifecycle("[GeckoViewASM] No AARs found in ${configurationName} for ${group}:${module}")
		}
		return collected
	}

	static Set<String> collectAssetsFromConfiguration(Project project, String configurationName, String group, String module) {
		Set<String> assets = new LinkedHashSet<>()
		def aars = resolveAarFilesFromConfiguration(project, configurationName, group, module)
		if (aars && !aars.isEmpty()) {
			aars.each { File f -> assets.addAll(readAarAssets(project, f)) }
		} else {
			project.logger.lifecycle("[GeckoViewASM] No AARs found in ${configurationName} for ${group}:${module}")
		}
		return assets
	}
}


