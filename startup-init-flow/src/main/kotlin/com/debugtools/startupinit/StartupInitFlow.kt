package com.debugtools.startupinit

object StartupInitFlow {
    fun builder(): Builder = Builder()

    class Builder {
        private val tasks = mutableListOf<InitTask>()
        private var reporter: InitReporter = NoOpInitReporter

        fun task(
            name: String,
            dependsOn: List<String> = emptyList(),
            block: InitTaskBuilder.() -> Unit
        ): Builder = apply {
            require(name.isNotBlank()) { "Init task name must not be blank" }
            val builder = InitTaskBuilder().apply(block)
            tasks += InitTask(name = name, dependsOn = dependsOn, runner = builder.buildRunner())
        }

        fun reporter(reporter: InitReporter): Builder = apply {
            this.reporter = reporter
        }

        fun build(): InitFlowRunner =
            InitFlowRunner(tasks = tasks.toList(), reporter = reporter)

        suspend fun run(): InitFlowResult = build().run()
    }
}
