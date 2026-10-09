plugins {
    id("com.android.library") version "8.9.2" apply false
    id("com.android.application") version "8.9.2" apply false
    kotlin("jvm") version "2.2.20" apply false
    kotlin("android") version "2.3.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
}

subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}