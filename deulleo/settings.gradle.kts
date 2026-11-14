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
        // Pleos Connect SDK (Vehicle, NaviHelper, SpeechToText, TextToSpeech, FusedLocation)
        maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
    }
}

rootProject.name = "deulleo"

include(":core")
// 순수 Kotlin 모듈만 빌드하려면: ./gradlew -Pcore.only :core:test
if (!providers.gradleProperty("core.only").isPresent) {
    include(":app")
}
