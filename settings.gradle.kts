pluginManagement {
    repositories {
        // Resolve shared plugin dependencies from Central and keep the Google mirror
        // limited to Google's Android tooling and Jetpack artifacts.
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google") {
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroup("com.google.android.material")
                includeGroup("com.google.testing.platform")
            }
        }
        google()
        gradlePluginPortal()
        maven("https://maven.aliyun.com/repository/gradle-plugin")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google") {
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroup("com.google.android.material")
                includeGroup("com.google.testing.platform")
            }
        }
        google()
        maven("https://maven.aliyun.com/repository/public")
    }
}
rootProject.name = "WhiteGame"
include(":app")
