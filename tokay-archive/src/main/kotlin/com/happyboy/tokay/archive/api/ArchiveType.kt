package com.happyboy.tokay.archive.api

/** 压缩包类型：ZIP（含 Zip64 等扩展）、SEVEN_Z（7z，通常 LZMA2）、UNKNOWN（无法识别）。 */
enum class ArchiveType { ZIP, SEVEN_Z, UNKNOWN }
