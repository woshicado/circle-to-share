import java.io.FileInputStream
import java.util.Properties
import java.util.SortedSet
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is read from keystore.properties (local, gitignored) or, if
// that's absent, from environment variables (used by CI). See RELEASING.md.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}

fun signingValue(propKey: String, envKey: String): String? =
    keystoreProperties.getProperty(propKey) ?: System.getenv(envKey)

val releaseStoreFile = signingValue("storeFile", "KEYSTORE_FILE")
val hasReleaseSigning = releaseStoreFile != null

// -Pplay builds the Google Play flavor; without it, the foss build.
val playBuild = providers.gradleProperty("play").isPresent

android {
    namespace = "eu.woshicado.circletoshare"
    compileSdk = 36

    defaultConfig {
        applicationId = "eu.woshicado.circletoshare"
        minSdk = 30
        targetSdk = 36
        versionCode = 16
        versionName = "0.3.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Where the build is distributed decides how tips are taken: Google Play
    // requires its own billing and forbids outside donation links; F-Droid
    // and GitHub builds keep the Ko-fi link and no Google libraries.
    // The foss build is the default and has no flavors, so F-Droid's recipe
    // (`gradle: yes`, i.e. plain assembleRelease) finds its APK in
    // build/outputs/apk/release/ for every new tag. -Pplay swaps src/foss for
    // the play flavor (src/play, androidTestPlay).
    if (playBuild) {
        flavorDimensions += "store"
        productFlavors {
            create("play") { dimension = "store" }
        }
    } else {
        sourceSets["main"].java.srcDir("src/foss/java")
        sourceSets["main"].res.srcDir("src/foss/res")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                // Resolve relative paths against the project root (where
                // release.jks lives), not the app module. Absolute paths
                // (e.g. CI's $RUNNER_TEMP/release.jks) pass through unchanged.
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Without a keystore the release APK stays unsigned
            // (app-release-unsigned.apk), as F-Droid's build expects; it signs
            // the APK itself. CI always sets the real key.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn("No release keystore configured — release APK is unsigned.")
            }
        }
    }

    // AGP otherwise embeds an encrypted dependency list (readable only by
    // Google) in the APK signing block; F-Droid rejects APKs that carry it.
    dependenciesInfo {
        includeInApk = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

/**
 * The open-source notices Google's Play libraries carry for code compiled
 * into them (third_party_licenses.json/.txt in each AAR, which the build
 * does not package), gathered into one asset for the licenses page: each
 * name once, with its texts, and each text once, however many libraries
 * share it (compared with whitespace evened out). Play build only: the foss
 * build has no such libraries.
 */
abstract class GooglePlayNoticesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val aars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun gather() {
        val texts = LinkedHashMap<String, Int>() // evened-out text -> index
        val shown = ArrayList<String>()
        // Name -> its texts (some names, e.g. Kotlin, come with several).
        val notices = sortedMapOf<String, SortedSet<Int>>(compareBy({ it.lowercase() }, { it }))
        aars.files.sortedBy { it.name }.forEach { aar ->
            ZipFile(aar).use { zip ->
                val index = zip.getEntry("third_party_licenses.json") ?: return@use
                val bytes = zip.getInputStream(zip.getEntry("third_party_licenses.txt")).use { it.readBytes() }
                @Suppress("UNCHECKED_CAST")
                val entries = groovy.json.JsonSlurper().parse(zip.getInputStream(index)) as Map<String, Map<String, Int>>
                entries.forEach { (name, at) ->
                    val text = String(bytes, at.getValue("start"), at.getValue("length"), Charsets.UTF_8).trim()
                    val i = texts.getOrPut(text.replace(Regex("\\s+"), " ")) { shown.add(text); shown.size - 1 }
                    notices.getOrPut(name) { sortedSetOf() }.add(i)
                }
            }
        }
        val json = groovy.json.JsonOutput.toJson(
            mapOf("texts" to shown, "notices" to notices.map { (name, texts) -> listOf(name, texts.toList()) })
        )
        outputDir.get().file("google_play_notices.json").asFile.writeText(json)
    }
}

if (playBuild) {
    androidComponents {
        onVariants { variant ->
            val gather = tasks.register<GooglePlayNoticesTask>("${variant.name}GooglePlayNotices") {
                aars.from(
                    variant.runtimeConfiguration.incoming.artifactView {
                        attributes { attribute(Attribute.of("artifactType", String::class.java), "aar") }
                    }.files
                )
            }
            variant.sources.assets?.addGeneratedSourceDirectory(gather, GooglePlayNoticesTask::outputDir)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // The list on the scrollshot page. Material brings it in anyway, at
    // this version: named because the app's own code uses it.
    implementation("androidx.recyclerview:recyclerview:1.1.0")
    // Pure-Java QR decoding. No native libs, no Play Services, and offline
    implementation("com.google.zxing:core:3.5.3")
    // Play build only (see src/play). After changing the version, run
    // ./gradlew -Pplay connectedPlayDebugAndroidTest: its telemetry must stay off.
    if (playBuild) {
        "playImplementation"("com.android.billingclient:billing:9.1.0")
    }

    // Tests only — nothing below ships in the APK.
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
