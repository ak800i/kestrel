plugins {
    id("kestrel.jvm.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

// :tools:eval runs the agent's task suite on the PC over adb (design spec, section 16.5). It depends on no other Kestrel
// module, because it reaches Kestrel only through adb and the debug hooks, so a change in the app never rebuilds it.
// scripts\eval.ps1 builds its installed copy with :tools:eval:installDist and runs its lib\* with java.exe and mainClass.
application {
    mainClass.set("dev.kestrel.eval.MainKt")
    applicationName = "eval"
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
