// Lector de notificaciones bancarias en Kotlin puro: recibe el texto de una
// notificación y propone un movimiento. Sin Android ni red: se prueba en la JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    testImplementation(libs.junit)
}
