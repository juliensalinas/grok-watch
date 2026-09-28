// Optional: set GOOGLE_MAVEN_MIRROR (e.g. https://dl-ssl.google.com/dl/android/maven2/) when
// dl.google.com / maven.google.com is not reachable from the build machine.
pluginManagement {
    val googleMirror: String? = System.getenv("GOOGLE_MAVEN_MIRROR")
    repositories {
        if (googleMirror.isNullOrBlank()) google() else maven(googleMirror)
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    val googleMirror: String? = System.getenv("GOOGLE_MAVEN_MIRROR")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (googleMirror.isNullOrBlank()) google() else maven(googleMirror)
        mavenCentral()
    }
}

rootProject.name = "GrokWatch"
include(":app")
