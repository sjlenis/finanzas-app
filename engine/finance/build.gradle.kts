// Motor financiero en Kotlin puro: deudas, fondo de emergencia, metas,
// presupuestos y dinero disponible. Reglas explícitas y probadas, sin IA ni red.
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
