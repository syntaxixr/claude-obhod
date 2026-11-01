plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.relay.claude"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.relay.claude"
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "1.0.0"

        // Личная сборка: ссылка вшивается при сборке и в git не попадает:
        //   gradle assembleRelease -PrelayLink="socks5://relay:ТОКЕН@1.2.3.4:8443" -PrelaySelfSigned=true
        // Без параметров — обычная сборка: приложение попросит вставить ссылку.
        val link = (project.findProperty("relayLink") as String?).orEmpty().replace("\"", "")
        val selfSigned = (project.findProperty("relaySelfSigned") as String?).toBoolean()
        buildConfigField("String", "DEFAULT_LINK", "\"$link\"")
        buildConfigField("boolean", "DEFAULT_SELF_SIGNED", selfSigned.toString())
        // какое приложение пускать через туннель (официальное приложение Claude)
        val vpnApp = (project.findProperty("relayVpnApp") as String?) ?: "com.anthropic.claude"
        buildConfigField("String", "VPN_APP", "\"$vpnApp\"")
    }

    signingConfigs {
        create("release") {
            // пароль — из RELAY_KEYSTORE_PASS или relayKeystorePass в ~/.gradle/gradle.properties (не в репозитории)
            val ks = file("relay-release.jks")
            val pass = System.getenv("RELAY_KEYSTORE_PASS") ?: (project.findProperty("relayKeystorePass") as String?)
            if (ks.exists() && pass != null) {
                storeFile = ks
                storePassword = pass
                keyAlias = "relay"
                keyPassword = pass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // ключ есть — подписываем; нет — соберётся неподписанный (см. README)
            if (file("relay-release.jks").exists() &&
                (System.getenv("RELAY_KEYSTORE_PASS") != null || project.hasProperty("relayKeystorePass"))) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { viewBinding = false; buildConfig = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
