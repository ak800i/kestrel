package dev.kestrel.build.audit

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File

/** The task's split of findings into violations and the exceptions of `audit/rules.txt`, which hold for one module only. */
class ScanNetworkApisTaskTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val gateway = "dev/kestrel/privacy/network/Gateway"
    private val other = "dev/kestrel/privacy/Other"

    /** A class whose one method creates a java.net.Socket. The scanner only reads it, so it need not verify. */
    private fun classUsingASocket(internalName: String): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "open", "()Ljava/lang/Object;", null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, "java/net/Socket")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    /** Runs the task over [classes] as the module [modulePath]: the task's failure, if any, and its report. */
    private fun scan(modulePath: String, vararg classes: String): Pair<GradleException?, String> {
        val classDirectory = temporary.newFolder()
        for (name in classes) {
            File(classDirectory, "$name.class").apply { parentFile.mkdirs() }.writeBytes(classUsingASocket(name))
        }
        val rules = temporary.newFile().apply {
            writeText("allow-network-class :core:privacy dev/kestrel/privacy/network/ The gateway\n")
        }
        val report = File(temporary.newFolder(), "network-apis.txt")
        val project = ProjectBuilder.builder().withProjectDir(temporary.newFolder()).build()
        val task = project.tasks.register("scan", ScanNetworkApisTask::class.java) {
            extraClassDirectories.from(classDirectory)
            rulesFile.set(rules)
            this.modulePath.set(modulePath)
            this.report.set(report)
        }.get()
        val failure = try {
            task.scan()
            null
        } catch (e: GradleException) {
            e
        }
        return failure to report.readText()
    }

    @Test
    fun exceptedClassesAreListedApartAndOnlyTheOthersFailTheBuild() {
        val (failure, report) = scan(":core:privacy", gateway, other)

        assertEquals("$other.open uses java/net/Socket", failure?.message?.substringAfter("\n"))
        assertEquals(
            "Violations:\n$other.open uses java/net/Socket\n\nAllowed by audit/rules.txt:\n$gateway.open uses java/net/Socket\n",
            report,
        )
    }

    @Test
    fun theGatewaysPackageIsExemptOnlyInItsOwnModule() {
        assertEquals(
            null to "No network API use outside the exceptions.\n\nAllowed by audit/rules.txt:\n$gateway.open uses java/net/Socket\n",
            scan(":core:privacy", gateway),
        )

        val (failure, report) = scan(":core:providers", gateway)

        assertEquals("$gateway.open uses java/net/Socket", failure?.message?.substringAfter("\n"))
        assertEquals("Violations:\n$gateway.open uses java/net/Socket\n", report)
    }
}