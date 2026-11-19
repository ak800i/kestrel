plugins {
    id("kestrel.jvm.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver3)
}

tasks.test {
    // CatalogFilesTest, OverridesTest and ProfileHostsTest read the repository's catalog files (design spec, section 17).
    val catalog = rootProject.layout.projectDirectory.dir("catalog")
    inputs.files(fileTree(catalog)).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("catalog")
    systemProperty("kestrel.catalog", catalog.asFile.absolutePath)
}
