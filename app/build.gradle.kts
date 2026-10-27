import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.apollo)
}

val localConfig = Properties().apply {
    rootProject.file("sentri.local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun config(key: String, default: String) = localConfig.getProperty(key, default)
fun literal(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
val appVersions = Properties().apply { rootProject.file("versions.properties").inputStream().use(::load) }
val signingProperties = Properties().apply {
    rootProject.file("sentri.signing.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
val localSigningProperties = Properties().apply {
    rootProject.file("sentri.local.signing.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
val localKeyStorePath = localSigningProperties.getProperty("storeFile")
val localStoreSecret = localSigningProperties.getProperty("storePassword")
val localKeySecret = localSigningProperties.getProperty("keyPassword")
val localKeyName = localSigningProperties.getProperty("keyAlias")
val hasLocalSigning = listOf(localKeyStorePath, localStoreSecret, localKeySecret, localKeyName)
    .all { !it.isNullOrBlank() }
if (localSigningProperties.isNotEmpty()) {
    check(hasLocalSigning) { "sentri.local.signing.properties must define storeFile, storePassword, keyPassword and keyAlias" }
    check(rootProject.file(localKeyStorePath!!).isFile) { "Local Android signing keystore is missing" }
}
fun signingValue(key: String, variable: String) = providers.environmentVariable(variable).orNull
    ?: signingProperties.getProperty(key)
val publishDebug = providers.gradleProperty("enableApkUpdate").orElse("false").get().toBooleanStrict()
val requiresSigning = publishDebug || gradle.startParameter.taskNames.any {
    it.substringAfterLast(':').contains("Release", ignoreCase = true) &&
        !it.substringAfterLast(':').startsWith("release")
}
val keyStorePath = signingValue("storeFile", "SENTRI_KEYSTORE_FILE")
val storeSecret = signingValue("storePassword", "SENTRI_STORE_PASSWORD")
val keySecret = signingValue("keyPassword", "SENTRI_KEY_PASSWORD")
val keyName = signingValue("keyAlias", "SENTRI_KEY_ALIAS") ?: "sentri"
val enterprisePath = signingValue("enterpriseCertificate", "SENTRI_ENTERPRISE_CERTIFICATE")
val hasSigning = listOf(keyStorePath, storeSecret, keySecret).all { !it.isNullOrBlank() }
fun validateLegacySigning() {
    check(hasSigning) { "Publish builds require the existing signing key via SENTRI_* or ignored sentri.signing.properties" }
    check(rootProject.file(keyStorePath!!).isFile) { "Signing keystore is missing" }
    check(!enterprisePath.isNullOrBlank() && rootProject.file(enterprisePath).isFile) { "Existing ENTERPRISE.CER is required for publish builds" }
}
if (requiresSigning) validateLegacySigning()
val requireLegacySigning = tasks.register("requireLegacySigning") {
    doLast { validateLegacySigning() }
}
// Aggregate tasks such as assemble also reach Release variants; never emit unsigned releases.
tasks.configureEach {
    if (name.startsWith("pre") && name.endsWith("ReleaseBuild")) dependsOn(requireLegacySigning)
}
val certificateResources = layout.buildDirectory.dir("generated/enterprise-resources")
val prepareEnterpriseCertificate = tasks.register("prepareEnterpriseCertificate") {
    inputs.property("source", enterprisePath ?: "")
    enterprisePath?.let { inputs.file(rootProject.file(it)) }
    outputs.dir(certificateResources)
    doLast {
        val output = certificateResources.get().asFile
        output.deleteRecursively()
        if (enterprisePath != null) {
            val destination = output.resolve("META-INF/ENTERPRISE.CER")
            destination.parentFile.mkdirs()
            rootProject.file(enterprisePath).copyTo(destination)
        } else output.mkdirs()
    }
}

android {
    namespace = "com.sentri.nativeapp"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.sentri.jingan"
        minSdk = 24
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // WebView intercepts the existing HTTPS callback; no external URI handler is exposed.
        manifestPlaceholders["appAuthRedirectScheme"] = "sentri-unused"
        buildConfigField("String", "AUTH_ENDPOINT", literal(config("auth.endpoint", "https://auth.sentri.cn/oidc")))
        buildConfigField("String", "AUTH_CLIENT_ID", literal(config("auth.clientId", "hakfcyqmu5daasjwv2p6i")))
        buildConfigField("String", "AUTH_REDIRECT_URI", literal(config("auth.redirectUri", "https://mobile_sentri_android/index.html")))
    }
    flavorDimensions += "environment"
    signingConfigs {
        if (hasSigning) create("legacy") {
            storeFile = rootProject.file(keyStorePath!!)
            storePassword = storeSecret
            keyAlias = keyName
            keyPassword = keySecret
        }
        if (hasLocalSigning) create("localDevice") {
            storeFile = rootProject.file(localKeyStorePath!!)
            storePassword = localStoreSecret
            keyAlias = localKeyName
            keyPassword = localKeySecret
        }
    }
    sourceSets.getByName("main").resources.srcDir(certificateResources)
    productFlavors {
        listOf("dev", "demo", "prod").forEach { environment ->
            create(environment) {
                dimension = "environment"
                applicationId = if (environment == "prod") "com.sentri.jingan" else "com.sentri.jingan.$environment"
                versionCode = appVersions.getProperty("$environment.versionCode").also {
                    check(it.matches(Regex("[1-9][0-9]*"))) { "Invalid $environment.versionCode" }
                }.toInt()
                versionName = appVersions.getProperty("$environment.versionName").also {
                    check(it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "Invalid $environment.versionName" }
                }
                val host = if (environment == "prod") "sentri.cn" else "$environment.sentri.cn"
                buildConfigField("String", "ENVIRONMENT", literal(environment))
                buildConfigField("String", "GRAPHQL_URL", literal(config("$environment.graphqlUrl", "https://$host/hasura/v1/graphql")))
                buildConfigField("String", "APK_UPDATE_BASE_URL", literal("https://farm-apk.sentri.cn/apks/$environment"))
                resValue("string", "app_name", "Sentri Native ($environment)")
            }
        }
    }
    buildTypes {
        debug {
            // Published Debug is the existing OSS channel; local Debug remains isolated.
            if (!publishDebug) applicationIdSuffix = ".local"
            buildConfigField("boolean", "ENABLE_APK_UPDATE", publishDebug.toString())
            if (publishDebug && hasSigning) signingConfig = signingConfigs.getByName("legacy")
            else if (!publishDebug && hasLocalSigning) signingConfig = signingConfigs.getByName("localDevice")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "ENABLE_APK_UPDATE", "true")
            if (hasSigning) signingConfig = signingConfigs.getByName("legacy")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
tasks.named("preBuild") { dependsOn(prepareEnterpriseCertificate) }
// JBR 21 is installed locally; bytecode targets 17 without requiring a downloaded JDK.
kotlin { jvmToolchain(21); compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }

apollo {
    service("sentri") {
        packageName.set("com.sentri.nativeapp.graphql")
        mapScalarToKotlinString("BiosafetyUserId")
        mapScalarToKotlinString("BiosafetyPigId")
        mapScalarToKotlinString("BiosafetyPrescriptionId")
        mapScalar("BiosafetyDatetime", "kotlin.String", "com.sentri.nativeapp.network.TimestampAdapter")
        mapScalar("BiosafetyFloatString", "java.math.BigDecimal", "com.sentri.nativeapp.network.DecimalStringAdapter")
        mapScalarToKotlinString("ProductionPigId")
        mapScalarToKotlinString("ProductionBatchId")
        mapScalar("ProductionDatetime", "kotlin.String", "com.sentri.nativeapp.network.TimestampAdapter")
        mapScalar("timestamptz", "kotlin.String", "com.sentri.nativeapp.network.TimestampAdapter")
        mapScalar("float8", "java.math.BigDecimal", "com.sentri.nativeapp.network.DecimalAdapter")
        mapScalar("ProductionFloatString", "java.math.BigDecimal", "com.sentri.nativeapp.network.DecimalStringAdapter")
        mapScalar("jsonb", "com.sentri.nativeapp.network.JsonValue", "com.sentri.nativeapp.network.JsonValueAdapter")
        // Preserve unknown server codes in full pig reads rather than collapsing them to UNKNOWN__.
        sealedClassesForEnumsMatching.set(listOf(
            "farm_enum_pig_breed_type_enum", "farm_enum_pig_lifecycle_status_enum", "farm_enum_pig_origin_enum",
            "farm_enum_pig_role_enum", "farm_enum_pig_sex_enum", "farm_enum_triage_level_enum",
            "farm_enum_boar_semen_collection_status_enum", "farm_enum_batch_production_status_enum",
            "farm_enum_batch_status_enum", "farm_enum_batch_task_type_enum", "farm_enum_task_status_enum",
            "farm_enum_prescription_status_enum", "farm_enum_therapy_task_status_enum",
        ))
        generateOptionalOperationVariables.set(false)
        introspection {
            endpointUrl.set(config("dev.graphqlUrl", "https://dev.sentri.cn/hasura/v1/graphql"))
            schemaFile.set(file("src/main/graphql/schema.graphqls"))
            val token = providers.environmentVariable("SENTRI_SCHEMA_TOKEN")
            if (token.isPresent) headers.put("Authorization", "Bearer ${token.get()}")
        }
    }
}
dependencies {
    // The same USB HID reader SDK as the read-only legacy Android host.
    implementation(files("libs/reader.jar"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation(libs.apollo.runtime)
    implementation(libs.okhttp)
    implementation(libs.core.ktx)
    implementation(libs.work.runtime.ktx)
    implementation(libs.appauth)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.okhttp.tls)
}
