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
        // Robolectric 的 android-all jar 上百 MB，从 Maven Central 直连很慢。
        // 只给 org.robolectric 走阿里云镜像，其余依赖仍按原来的仓库解析。
        maven {
            url = uri("https://maven.aliyun.com/repository/public")
            content { includeGroup("org.robolectric") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "FocusAssistant"
include(":app")
