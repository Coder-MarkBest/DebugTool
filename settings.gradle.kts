pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DebugTools"
include(":startup-init-flow")
include(":debugtools-startup-init")
include(":debugtools-core")
include(":debugtools-network")
include(":debugtools-timeline")
include(":debugtools-general")
include(":debugtools-okhttp-capture")
include(":debugtools-perfmon")
include(":debugtools-audiomon")
include(":debugtools-startup")
include(":debugtools-conversation")
include(":debugtools-stability")
include(":app")
