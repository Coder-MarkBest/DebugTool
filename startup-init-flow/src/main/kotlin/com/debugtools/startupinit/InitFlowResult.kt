package com.debugtools.startupinit

data class InitFlowResult(
    val taskResults: List<InitTaskResult>,
    val success: Boolean
)

data class InitTaskResult(
    val name: String,
    val status: InitTaskStatus,
    val error: String? = null
)

enum class InitTaskStatus {
    SUCCESS,
    FAILED,
    SKIPPED
}
