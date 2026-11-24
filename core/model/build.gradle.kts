// Modelo de dominio en Kotlin puro: sin dependencias de Android para poder probarlo en la JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}
