// Intérprete del chat en Kotlin puro: convierte frases en español colombiano en
// borradores de movimientos o consultas. Sin Android ni red: se prueba en la JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    testImplementation(libs.junit)
}
