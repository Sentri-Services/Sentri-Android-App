plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.apollo) apply false
}
listOf("Dev", "Demo", "Prod", "All").forEach { scope ->
    tasks.register<Exec>("release$scope") {
        group = "release"
        description = "Increment $scope versions, commit/tag and push to trigger the existing OSS release flow"
        commandLine("python3", "scripts/release.py", scope.lowercase(), "--publish")
    }
}
