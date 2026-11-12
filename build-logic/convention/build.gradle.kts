import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(libs.android.gradleApi)
    compileOnly(libs.kotlin.gradlePlugin)
    implementation(libs.asm)
    testImplementation(libs.junit)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "kestrel.android.application"
            implementationClass = "dev.kestrel.build.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "kestrel.android.library"
            implementationClass = "dev.kestrel.build.AndroidLibraryConventionPlugin"
        }
        register("jvmLibrary") {
            id = "kestrel.jvm.library"
            implementationClass = "dev.kestrel.build.JvmLibraryConventionPlugin"
        }
        register("audit") {
            id = "kestrel.audit"
            implementationClass = "dev.kestrel.build.audit.AuditPlugin"
        }
    }
}
