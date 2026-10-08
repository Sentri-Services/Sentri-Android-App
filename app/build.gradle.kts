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

android {
    namespace = "com.sentri.nativeapp"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.sentri.nativeapp"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // WebView intercepts the existing HTTPS callback; no external URI handler is exposed.
        manifestPlaceholders["appAuthRedirectScheme"] = "sentri-unused"
        buildConfigField("String", "AUTH_ENDPOINT", literal(config("auth.endpoint", "https://auth.sentri.cn/oidc")))
        buildConfigField("String", "AUTH_CLIENT_ID", literal(config("auth.clientId", "hakfcyqmu5daasjwv2p6i")))
        buildConfigField("String", "AUTH_REDIRECT_URI", literal(config("auth.redirectUri", "https://mobile_sentri_android/index.html")))
    }
    flavorDimensions += "environment"
    productFlavors {
        listOf("dev", "demo", "prod").forEach { environment ->
            create(environment) {
                dimension = "environment"
                applicationIdSuffix = ".$environment"
                val host = if (environment == "prod") "sentri.cn" else "$environment.sentri.cn"
                buildConfigField("String", "ENVIRONMENT", literal(environment))
                buildConfigField("String", "GRAPHQL_URL", literal(config("$environment.graphqlUrl", "https://$host/hasura/v1/graphql")))
                resValue("string", "app_name", "Sentri Native ($environment)")
            }
        }
    }
    buildTypes {
        release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
// JBR 21 is installed locally; bytecode targets 17 without requiring a downloaded JDK.
kotlin { jvmToolchain(21); compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }

apollo {
    service("sentri") {
        packageName.set("com.sentri.nativeapp.graphql")
        mapScalarToKotlinString("BiosafetyUserId")
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
}
