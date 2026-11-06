plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.dailytodo.mydeepseek"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.dailytodo.mydeepseek"
        minSdk = 26
        targetSdk = 37
        versionCode = 10
        versionName = "1.6.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        noCompress += listOf("webm", "wav", "ttf")
    }
    sourceSets["main"].assets.srcDir("build/generated/petAssets")
}

val preparePetAssets by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    val node = providers.gradleProperty("nodeExecutable").orElse(
        if (file("/usr/local/bin/node").exists()) "/usr/local/bin/node" else "node"
    )
    commandLine(node.get(), "android/prepare-assets.mjs")
    environment("RAYON_NUM_THREADS", "1")
    environment("NODE_OPTIONS", "--max-old-space-size=384")
    inputs.dir(rootProject.file("dshgit/dsh-pet/src"))
    inputs.dir(rootProject.file("dshgit/dsh-pet/runtime/electron-helper"))
    inputs.dir(rootProject.file("dshgit/dsh-pet/assets"))
    inputs.dir(rootProject.file("android/pet"))
    inputs.file(rootProject.file("android/prepare-assets.mjs"))
    inputs.file(rootProject.file("android/upstream-tree.json"))
    outputs.dir(layout.buildDirectory.dir("generated/petAssets"))
}
tasks.named("preBuild").configure { dependsOn(preparePetAssets) }

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
