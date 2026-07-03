package com.debugtools.conversation.trace

data class RequestBoundaryRule(
    val startEvents: List<String> = emptyList(),
    val exitEvents: List<String> = emptyList(),
    val fallbackTimeoutMs: Long = 30_000L
)

data class StageRule(
    val id: String,
    val begin: String,
    val end: String,
    val label: String,
    val category: TraceCategory,
    val showInConversation: Boolean,
    val includeInDuration: Boolean,
    val warnIfSlowMs: Long?,
    val required: Boolean,
    val order: Int
)

data class MarkerRule(
    val name: String,
    val label: String,
    val showInConversation: Boolean,
    val includeInDuration: Boolean,
    val category: TraceCategory,
    val order: Int
)

data class VoiceTraceProfile(
    val requestKey: String = "requestId",
    val boundary: RequestBoundaryRule = RequestBoundaryRule(),
    val stageRules: List<StageRule> = emptyList(),
    val markerRules: List<MarkerRule> = emptyList()
) {
    fun isExitEvent(name: String): Boolean = name in boundary.exitEvents
    fun markerFor(name: String): MarkerRule? = markerRules.firstOrNull { it.name == name }
}

class VoiceTraceProfileBuilder {
    var requestKey: String = "requestId"
    private var boundary = RequestBoundaryRule()
    private val stages = mutableListOf<StageRule>()
    private val markers = mutableListOf<MarkerRule>()

    fun requestBoundary(block: RequestBoundaryBuilder.() -> Unit) {
        boundary = RequestBoundaryBuilder().apply(block).build()
    }

    fun stage(id: String, block: StageRuleBuilder.() -> Unit) {
        stages += StageRuleBuilder(id).apply(block).build()
    }

    fun marker(name: String, block: MarkerRuleBuilder.() -> Unit) {
        markers += MarkerRuleBuilder(name).apply(block).build()
    }

    fun build(): VoiceTraceProfile = VoiceTraceProfile(requestKey, boundary, stages.toList(), markers.toList())
}

class RequestBoundaryBuilder {
    var startEvents: List<String> = emptyList()
    var exitEvents: List<String> = emptyList()
    var fallbackTimeoutMs: Long = 30_000L
    fun build() = RequestBoundaryRule(startEvents, exitEvents, fallbackTimeoutMs)
}

class StageRuleBuilder(private val id: String) {
    var begin: String = ""
    var end: String = ""
    var label: String = id
    var category: TraceCategory = TraceCategory.CUSTOM
    var showInConversation: Boolean = true
    var includeInDuration: Boolean = true
    var warnIfSlowMs: Long? = null
    var required: Boolean = false
    var order: Int = 0

    fun build(): StageRule = StageRule(
        id = requireNonBlank(id, "id"),
        begin = requireNonBlank(begin, "begin"),
        end = requireNonBlank(end, "end"),
        label = label,
        category = category,
        showInConversation = showInConversation,
        includeInDuration = includeInDuration,
        warnIfSlowMs = warnIfSlowMs,
        required = required,
        order = order
    )
}

class MarkerRuleBuilder(private val name: String) {
    var label: String = name
    var showInConversation: Boolean = true
    var includeInDuration: Boolean = false
    var category: TraceCategory = TraceCategory.CUSTOM
    var order: Int = 0

    fun build(): MarkerRule = MarkerRule(
        name = requireNonBlank(name, "name"),
        label = label,
        showInConversation = showInConversation,
        includeInDuration = includeInDuration,
        category = category,
        order = order
    )
}

fun voiceTraceProfile(block: VoiceTraceProfileBuilder.() -> Unit): VoiceTraceProfile =
    VoiceTraceProfileBuilder().apply(block).build()

private fun requireNonBlank(value: String, field: String): String {
    require(value.isNotBlank()) { "$field must not be blank" }
    return value
}
