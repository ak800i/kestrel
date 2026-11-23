pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx publishes its Android library only as an asset of its GitHub releases. Gradle downloads it from
        // there, and gradle/verification-metadata.xml checks its SHA-256 hash (design spec, section 17).
        exclusiveContent {
            forRepository {
                ivy {
                    name = "sherpa-onnx releases"
                    url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
                    patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeGroup("com.k2fsa.sherpa.onnx") }
        }
    }
}

rootProject.name = "kestrel"

include(":app")
include(":core:agent")
include(":core:commands")
include(":core:datatools")
include(":core:perception")
include(":core:privacy")
include(":core:providers")
include(":core:screen")
include(":core:screen-model")
include(":core:voice")
include(":core:safety")
include(":testapp")
include(":tools:catalog-sync")
include(":tools:eval")
