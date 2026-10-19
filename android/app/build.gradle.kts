import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Стабільний ключ підпису Aolithium Live (свій, не Track Catcher).
// CI: змінні середовища AL_* (із секретів GitHub).
// Телефон: ~/.android/aolithium-live-signing.properties (створює меню, пункт 7).
// Без ключа збирається лише непідписаний APK — його не публікуємо.
val phoneSigningFile = File(System.getProperty("user.home"), ".android/aolithium-live-signing.properties")
val phoneSigning = Properties().apply {
    if (phoneSigningFile.isFile) phoneSigningFile.inputStream().use { load(it) }
}

fun signingValue(env: String, prop: String): String? =
    providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() }
        ?: phoneSigning.getProperty(prop)?.takeIf { it.isNotBlank() }

val alKeystoreFile = signingValue("AL_KEYSTORE_PATH", "storeFile")?.let { file(it) }
val alStorePassword = signingValue("AL_STORE_PASSWORD", "storePassword")
val alKeyAlias = signingValue("AL_KEY_ALIAS", "keyAlias")
val alKeyPassword = signingValue("AL_KEY_PASSWORD", "keyPassword")
val alSigningAvailable = alKeystoreFile?.isFile == true &&
    !alStorePassword.isNullOrBlank() && !alKeyAlias.isNullOrBlank() && !alKeyPassword.isNullOrBlank()

// Коміт, з якого зібрано, — у рядку версії застосунку (faric звіряє, що стоїть на телефоні).
val gitSha: String = providers.environmentVariable("GITHUB_SHA").orNull?.take(7)
    ?: runCatching {
        providers.exec {
            commandLine("git", "rev-parse", "--short=7", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
    }.getOrNull()?.takeIf { it.isNotBlank() }
    ?: "local"

android {
    namespace = "ua.faric.aolithium"
    compileSdk = 36

    defaultConfig {
        applicationId = "ua.faric.aolithium"
        // Android 8 — через Huawei Mate 10 Lite (перший телефон faric).
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "0.3.3"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
    }

    signingConfigs {
        create("stable") {
            if (alSigningAvailable) {
                storeFile = alKeystoreFile
                storePassword = alStorePassword
                keyAlias = alKeyAlias
                keyPassword = alKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (alSigningAvailable) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Лінтер запускає лише CI (android.yml, крок «Lint»): шукає виклики API, новіших за Android 8,
    // без перевірки версії (урок Dorama T94: на старому телефоні такий виклик закриває застосунок).
    lint {
        checkReleaseBuilds = false
        abortOnError = true
        checkOnly += setOf("NewApi")
        error += setOf("NewApi")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Жодних бібліотек у застосунку: лише Android. Так менше APK і менше залежностей для збірки на телефоні.
    testImplementation("junit:junit:4.13.2")
}
