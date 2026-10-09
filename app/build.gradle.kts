plugins {
    jacoco
    id("org.jetbrains.kotlin.kapt")
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.stillroom"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "app.stillroom"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging {
        resources {
            // Bouncy Castle is an instrumentation-test dependency. Its jars repeat these metadata files.
            excludes += setOf(
                "META-INF/versions/*/OSGI-INF/MANIFEST.MF",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
            )
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    val stillroomSigning = stillroomReleaseSigning()
    signingConfigs {
        if (stillroomSigning != null) {
            create("stillroom") {
                storeFile = stillroomSigning.storeFile
                storePassword = stillroomSigning.storePassword
                keyAlias = stillroomSigning.keyAlias
                keyPassword = stillroomSigning.keyPassword
            }
        }
    }
    buildTypes {
        // Debug and release share CN=Stillroom. The Android debug key is not used when the
        // official keystore is present, so adb install -r can replace a GitHub APK.
        if (stillroomSigning != null) {
            named("debug") { signingConfig = signingConfigs.getByName("stillroom") }
            named("release") { signingConfig = signingConfigs.getByName("stillroom") }
        }
    }
}

private class StillroomSigning(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

private fun stillroomReleaseSigning(): StillroomSigning? {
    val values = linkedMapOf<String, String>()
    val envFile = File(System.getProperty("user.home"), ".stillroom-release.env")
    if (envFile.isFile) {
        envFile.readLines().forEach { line ->
            val text = line.trim()
            if (text.isEmpty() || text.startsWith("#") || !text.contains("=")) return@forEach
            val (name, raw) = text.split("=", limit = 2)
            values[name] = raw.trim().trim('"')
        }
    }
    listOf("STILLROOM_KEYSTORE", "STILLROOM_KEY_ALIAS", "STILLROOM_STORE_PASS", "STILLROOM_KEY_PASS").forEach { name ->
        System.getenv(name)?.takeIf { it.isNotEmpty() }?.let { values[name] = it }
    }
    val storeFile = values["STILLROOM_KEYSTORE"]?.let(::File) ?: return null
    val storePassword = values["STILLROOM_STORE_PASS"] ?: return null
    val keyAlias = values["STILLROOM_KEY_ALIAS"] ?: return null
    val keyPassword = values["STILLROOM_KEY_PASS"] ?: return null
    if (!storeFile.isFile) return null
    return StillroomSigning(storeFile, storePassword, keyAlias, keyPassword)
}
kotlin { jvmToolchain(21) }

kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    testImplementation("androidx.work:work-testing:2.10.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("org.jsoup:jsoup:1.21.2")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")
    implementation("androidx.camera:camera-mlkit-vision:1.5.3")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("androidx.room:room-runtime:2.7.2")
    kapt("androidx.room:room-compiler:2.7.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    implementation(project(":fractions"))
    debugImplementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.compose.material:material-icons-extended")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.bouncycastle:bcpkix-jdk18on:1.79")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}


jacoco { toolVersion = "0.8.12" }
tasks.withType<Test>().configureEach {
    outputs.upToDateWhen { System.getenv("STILLROOM_STAGE9_FIXTURES") == null && System.getenv("STILLROOM_STAGE10_FIXTURES") == null && System.getenv("STILLROOM_STAGE11_FIXTURES") == null }
    extensions.configure<JacocoTaskExtension> { isIncludeNoLocationClasses = true; excludes = listOf("jdk.internal.*") }
}
val syncClasses = fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
    include("app/stillroom/data/AccountDatabase*.class", "app/stillroom/data/CachedGrocyRepository*.class", "app/stillroom/data/MutationTransport*.class")
}
tasks.register<JacocoReport>("syncCoverageReport") {
    dependsOn("testDebugUnitTest")
    executionData(layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"))
    classDirectories.setFrom(syncClasses)
    sourceDirectories.setFrom(files("src/main/java"))
    reports { xml.required.set(true); html.required.set(true) }
}
tasks.register<JacocoCoverageVerification>("syncCoverageVerification") {
    dependsOn("syncCoverageReport")
    executionData(layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"))
    classDirectories.setFrom(syncClasses)
    violationRules { rule { limit { counter = "LINE"; minimum = "0.80".toBigDecimal() } } }
}

val stockClasses = fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
    include("app/stillroom/domain/Stock*.class", "app/stillroom/domain/ManageStock*.class", "app/stillroom/data/GrocyStockRepository*.class")
}
tasks.register<JacocoReport>("stockCoverageReport") {
    dependsOn("testDebugUnitTest")
    executionData(layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"))
    classDirectories.setFrom(stockClasses)
    sourceDirectories.setFrom(files("src/main/java"))
    reports { xml.required.set(true); html.required.set(true) }
}
tasks.register<JacocoCoverageVerification>("stockCoverageVerification") {
    dependsOn("stockCoverageReport")
    executionData(layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"))
    classDirectories.setFrom(stockClasses)
    violationRules { rule { limit { counter = "LINE"; minimum = "0.80".toBigDecimal() } } }
}

val shoppingClasses = fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
    include("app/stillroom/domain/Shopping*.class", "app/stillroom/domain/ManageShopping*.class", "app/stillroom/data/GrocyShoppingRepository*.class", "app/stillroom/data/ShoppingSync*.class")
}
tasks.register<JacocoReport>("shoppingCoverageReport") {
    dependsOn("testDebugUnitTest")
    executionData(layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"))
    classDirectories.setFrom(shoppingClasses)
    sourceDirectories.setFrom(files("src/main/java"))
    reports { xml.required.set(true); html.required.set(true) }
}
tasks.register<JacocoCoverageVerification>("shoppingCoverageVerification") {
    dependsOn("shoppingCoverageReport")
    executionData(layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"))
    classDirectories.setFrom(shoppingClasses)
    violationRules { rule { limit { counter = "LINE"; minimum = "0.80".toBigDecimal() } } }
}

val householdClasses = fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
    include("app/stillroom/domain/Household*.class", "app/stillroom/domain/ManageHousehold*.class", "app/stillroom/data/GrocyHouseholdRepository*.class")
}
tasks.register<JacocoReport>("householdCoverageReport") {
    dependsOn("testDebugUnitTest")
    executionData(layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"))
    classDirectories.setFrom(householdClasses)
    sourceDirectories.setFrom(files("src/main/java"))
    reports { xml.required.set(true); html.required.set(true) }
}
tasks.register<JacocoCoverageVerification>("householdCoverageVerification") {
    dependsOn("householdCoverageReport")
    executionData(layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"))
    classDirectories.setFrom(householdClasses)
    violationRules { rule { limit { counter = "LINE"; minimum = "0.80".toBigDecimal() } } }
}

val scannerClasses = fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
    include("app/stillroom/domain/Scan*.class", "app/stillroom/domain/ManageScanner*.class", "app/stillroom/data/GrocyScanRepository*.class", "app/stillroom/data/OpenFoodFactsLookup*.class")
}
tasks.register<JacocoReport>("scannerCoverageReport") {
    dependsOn("testDebugUnitTest")
    executionData(layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"))
    classDirectories.setFrom(scannerClasses)
    sourceDirectories.setFrom(files("src/main/java"))
    reports { xml.required.set(true); html.required.set(true) }
}
tasks.register<JacocoCoverageVerification>("scannerCoverageVerification") {
    dependsOn("scannerCoverageReport")
    executionData(layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"))
    classDirectories.setFrom(scannerClasses)
    violationRules { rule { limit { counter = "LINE"; minimum = "0.80".toBigDecimal() } } }
}

val recipesClasses = fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
    include("app/stillroom/domain/Recipe*.class", "app/stillroom/domain/ManageRecipes*.class", "app/stillroom/data/GrocyRecipeRepository*.class", "app/stillroom/data/RecipeMediaKt*.class", "app/stillroom/data/RecipeUrlReader*.class", "app/stillroom/data/RecipeFiles*.class")
}
tasks.register<JacocoReport>("recipesCoverageReport") {
    dependsOn("testDebugUnitTest")
    executionData(layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"))
    classDirectories.setFrom(recipesClasses)
    sourceDirectories.setFrom(files("src/main/java"))
    reports { xml.required.set(true); html.required.set(true) }
}
tasks.register<JacocoCoverageVerification>("recipesCoverageVerification") {
    dependsOn("recipesCoverageReport")
    executionData(layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"))
    classDirectories.setFrom(recipesClasses)
    violationRules { rule { limit { counter = "LINE"; minimum = "0.80".toBigDecimal() } } }
}

val catalogClasses = fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
    include("app/stillroom/domain/Catalog*.class", "app/stillroom/domain/CustomFields*.class", "app/stillroom/domain/ManageCatalog*.class", "app/stillroom/domain/Today*.class", "app/stillroom/domain/CachedToday*.class", "app/stillroom/domain/QuietHours*.class", "app/stillroom/data/GrocyCatalogRepository*.class", "app/stillroom/data/CachedTodayRepository*.class", "app/stillroom/background/HouseholdWork*.class", "app/stillroom/background/HouseholdWorker*.class", "app/stillroom/background/ReminderPreferences*.class")
}
tasks.register<JacocoReport>("catalogCoverageReport") {
    dependsOn("testDebugUnitTest")
    executionData(layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"))
    classDirectories.setFrom(catalogClasses)
    sourceDirectories.setFrom(files("src/main/java"))
    reports { xml.required.set(true); html.required.set(true) }
}
tasks.register<JacocoCoverageVerification>("catalogCoverageVerification") {
    dependsOn("catalogCoverageReport")
    executionData(layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"))
    classDirectories.setFrom(catalogClasses)
    violationRules { rule { limit { counter = "LINE"; minimum = "0.80".toBigDecimal() } } }
}
