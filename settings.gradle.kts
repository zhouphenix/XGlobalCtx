pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "XGlobalCtx"

include(":core")
include(":android")
include(":view")
include(":compose")
include(":demo")
