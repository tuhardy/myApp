plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Robolectric 需要一个 android-all jar 作为 JVM 内的 Android 运行时。让 Gradle 走已配置的
// 仓库把它解析好、拷到固定目录，再用 robolectric.offline 让测试直接复用，
// 避免 Robolectric 自带下载器绕过仓库配置、在本机网络下长时间挂住。
val robolectricRuntime: Configuration by configurations.creating
val androidAllDir = layout.buildDirectory.dir("robolectric-android-all")
val prepareRobolectricJars = tasks.register<Copy>("prepareRobolectricJars") {
    from(robolectricRuntime)
    into(androidAllDir)
}

android {
    namespace = "com.focusassistant.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.focusassistant.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.3.2"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // Compose 截图测试用 Robolectric 在 JVM 里渲染，不需要连接设备。
            isIncludeAndroidResources = true
            all {
                // Robolectric 自带的下载器绕过 Gradle 仓库配置，在本机网络下会长时间挂住。
                // 改为由 Gradle 解析 android-all jar，再让 Robolectric 离线复用。
                it.systemProperty("robolectric.offline", "true")
                it.systemProperty("robolectric.dependency.dir", androidAllDir.get().asFile.absolutePath)
                // 截图测试依赖上百 MB 的 android-all jar，首次下载很慢，因此默认跳过，
                // 只在显式传 -PwithScreenshots 时执行；日常验证流程不受它影响。
                if (providers.gradleProperty("withScreenshots").isPresent) {
                    it.dependsOn(prepareRobolectricJars)
                } else {
                    it.exclude("**/*ScreenshotTest*")
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // 截图测试：Robolectric 提供 JVM 内的 Android 环境，ui-test-junit4 提供 Compose 测试规则。
    testImplementation("org.robolectric:robolectric:4.14.1")
    // SDK 35 对应的运行时；换 robolectric.properties 里的 sdk 或 robolectric 版本时要同步换这一行，
    // 版本号必须和 Robolectric 内置清单要求的完全一致（4.14.1 的 SDK 35 要 15-robolectric-12650502-i7），
    // 否则离线模式会报 Path is not a file。每个 SDK 一个 150 MB jar，因此只固定一个版本。
    robolectricRuntime("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // ui-test-manifest 只有作为 debug 变体依赖时才会把 ComponentActivity 合并进清单；
    // 放在 testImplementation 里清单不参与合并，Robolectric 会报 Unable to resolve activity。
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
