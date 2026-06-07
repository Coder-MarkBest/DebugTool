package com.debugtools.core.module

internal class ModuleRegistry {
    private val _modules = mutableListOf<DebugModule>()
    val modules: List<DebugModule> get() = _modules

    fun register(module: DebugModule) {
        require(_modules.none { it.moduleId == module.moduleId }) {
            "Module with id '${module.moduleId}' is already registered"
        }
        _modules.add(module)
    }
}
