package dev.kestrel.build.audit

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

data class NetworkFinding(val className: String, val methodName: String, val reference: String) {
    override fun toString(): String = "$className.$methodName uses $reference"
}

/** Which JVM and Android APIs count as network access (design spec, section 13.2). */
object NetworkApiRules {
    private val networkTypes = setOf(
        "java/net/Socket", "java/net/ServerSocket", "java/net/DatagramSocket", "java/net/MulticastSocket",
        "java/net/URLConnection", "java/net/HttpURLConnection", "java/net/JarURLConnection",
        "java/nio/channels/SocketChannel", "java/nio/channels/ServerSocketChannel",
        "java/nio/channels/DatagramChannel", "java/nio/channels/AsynchronousSocketChannel",
        "android/webkit/WebView", "android/app/DownloadManager", "android/location/Geocoder",
        // Only the gateway may build an OkHttp client. Other code uses the gateway's Call.Factory.
        "okhttp3/OkHttpClient", "okhttp3/OkHttpClient\$Builder",
    )
    private val networkPackages = listOf("java/net/http/", "javax/net/", "android/net/http/", "org/chromium/net/")
    private val networkMethods = mapOf(
        "java/net/URL" to setOf("openConnection", "openStream", "getContent"),
        "java/net/InetAddress" to setOf("getByName", "getAllByName", "getLocalHost"),
        "android/net/Network" to setOf("openConnection", "bindSocket", "getSocketFactory", "getByName", "getAllByName"),
    )
    private val typeInDescriptor = Regex("L([^;]+);")

    fun isNetworkType(internalName: String): Boolean =
        internalName in networkTypes || networkPackages.any { internalName.startsWith(it) }

    fun isNetworkCall(owner: String, name: String, descriptor: String): Boolean =
        isNetworkType(owner) ||
            networkMethods[owner]?.contains(name) == true ||
            (owner.startsWith("kotlin/io/") && descriptor.contains("Ljava/net/URL;")) ||
            typeInDescriptor.findAll(descriptor).any { isNetworkType(it.groupValues[1]) }
}

/** Finds references to network APIs in compiled JVM classes. */
class NetworkApiScanner {
    fun scan(classBytes: ByteArray): List<NetworkFinding> {
        val findings = mutableListOf<NetworkFinding>()
        val reader = ClassReader(classBytes)
        val className = reader.className
        reader.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                val methodName = name
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                        if (NetworkApiRules.isNetworkCall(owner, name, descriptor)) {
                            findings += NetworkFinding(className, methodName, "$owner.$name")
                        }
                    }

                    override fun visitTypeInsn(opcode: Int, type: String) {
                        if (NetworkApiRules.isNetworkType(type)) findings += NetworkFinding(className, methodName, type)
                    }

                    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                        if (NetworkApiRules.isNetworkType(owner)) findings += NetworkFinding(className, methodName, "$owner.$name")
                    }

                    override fun visitLdcInsn(value: Any?) {
                        if (value is Type && value.sort == Type.OBJECT && NetworkApiRules.isNetworkType(value.internalName)) {
                            findings += NetworkFinding(className, methodName, value.internalName)
                        }
                    }

                    override fun visitInvokeDynamicInsn(
                        name: String,
                        descriptor: String,
                        bootstrapMethodHandle: Handle,
                        vararg bootstrapMethodArguments: Any?,
                    ) {
                        bootstrapMethodArguments.filterIsInstance<Handle>().forEach { handle ->
                            if (NetworkApiRules.isNetworkCall(handle.owner, handle.name, handle.desc)) {
                                findings += NetworkFinding(className, methodName, "${handle.owner}.${handle.name}")
                            }
                        }
                    }
                }
            }
        }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return findings.distinct()
    }
}
