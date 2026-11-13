package dev.kestrel.build.audit

import org.w3c.dom.Element
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
private const val DUMP_PERMISSION = "android.permission.DUMP"
private val COMPONENT_TAGS = listOf("activity", "activity-alias", "service", "receiver", "provider")

/**
 * Returns the test hooks that a merged manifest declares: the app's own components that only callers with
 * `android.permission.DUMP` may use (design spec, section 16.1). A release build must declare none. Libraries'
 * components are not test hooks, even when they require DUMP, such as AndroidX's ProfileInstallReceiver, which
 * lets adb install a startup profile.
 */
fun findTestHooks(manifest: String, applicationId: String): List<String> {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    }
    val document = factory.newDocumentBuilder().parse(manifest.byteInputStream())
    return COMPONENT_TAGS
        .flatMap { tag ->
            val nodes = document.getElementsByTagName(tag)
            (0 until nodes.length).map { nodes.item(it) as Element }
        }
        .filter { it.getAttributeNS(ANDROID_NAMESPACE, "permission") == DUMP_PERMISSION }
        .map { it.getAttributeNS(ANDROID_NAMESPACE, "name") }
        .filter { it.startsWith("$applicationId.") }
}
