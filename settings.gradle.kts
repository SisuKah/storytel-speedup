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
        // Classic Xposed API (de.robv.android.xposed:api). This is the API that
        // LSPatch (legacy-module path) and LSPosed both load.
        maven { url = uri("https://api.xposed.info/") }
    }
}

rootProject.name = "StorytelSpeedMod"

// :probe = PHASE 1 empty compatibility module (logs one line, changes nothing).
// :app   = the real module (discovery mode + Media3 speed hooks + config UI).
include(":probe", ":app")
