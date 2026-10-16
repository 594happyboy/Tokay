package com.happyboy.tokay.engine.server.mapping

class MappingManager {
    private val modules = mutableListOf<FileMappingModule>()

    fun addModule(module: FileMappingModule) {
        modules.add(module)
    }

    fun getFile(path: String): FileMappingResult? {
        for (module in modules) {
            if (module.canHandle(path)) {
                return module.getFile(path)
            }
        }
        return null
    }
}