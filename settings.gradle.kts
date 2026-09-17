pluginManagement {
    repositories {
        // dl.google.com answers 404 for several Jetpack artifacts on this network, so the
        // mirror of Google Maven is tried first and google() stays as the fallback.
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/public")
    }
}
rootProject.name = "WhiteGame"
include(":app")
