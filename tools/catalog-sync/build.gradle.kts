plugins {
    id("kestrel.jvm.library")
}

dependencies {
    implementation(project(":core:providers"))
    testImplementation(libs.junit)
}

// Writes catalog/providers.json from the OpenClaw checkout that scripts/sync-catalog.ps1 makes (design spec, section 10.2):
// scripts\gradle.ps1 :tools:catalog-sync:syncCatalog -Pcheckout=<absolute folder> -Pcommit=<40-character hash>
tasks.register<JavaExec>("syncCatalog") {
    group = "kestrel"
    description = "Writes catalog/providers.json from an OpenClaw checkout."
    classpath = project.extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath
    mainClass.set("dev.kestrel.catalogsync.MainKt")
    // Local values, so that the configuration cache stores the arguments and not this script.
    val checkout = project.providers.gradleProperty("checkout")
    val commit = project.providers.gradleProperty("commit")
    val catalogFile = project.rootProject.layout.projectDirectory.file("catalog/providers.json").asFile.absolutePath
    argumentProviders.add(CommandLineArgumentProvider { listOf(checkout.get(), commit.get(), catalogFile) })
}
