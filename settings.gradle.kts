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

rootProject.name = "finanzas-app"

include(":app")
include(":core:model")
include(":core:common")
include(":core:database")
include(":core:data")
include(":core:security")
include(":core:designsystem")
include(":engine:nlp")
include(":feature:dashboard")
include(":feature:transactions")
include(":feature:assistant")
include(":feature:budgets")
include(":feature:profile")
include(":feature:accounts")
include(":feature:settings")
