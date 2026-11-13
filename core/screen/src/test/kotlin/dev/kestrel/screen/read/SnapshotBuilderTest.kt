package dev.kestrel.screen.read

import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenJson
import dev.kestrel.screen.model.WindowKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SnapshotBuilderTest {
    private val builder = SnapshotBuilder(clock = { 1_000L })

    private fun build(vararg windows: FakeWindow) = builder.build(windows.toList())

    @Test
    fun layoutsWithoutTextOrActionsAreLeftOutAndATappableRowIsNamedAfterItsText() {
        val row = FakeNode(
            clickable = true,
            className = "android.widget.LinearLayout",
            children = listOf(
                FakeNode(text = "Ana Petrović", className = "android.widget.TextView"),
                FakeNode(text = "Online", className = "android.widget.TextView"),
            ),
        )

        val elements = build(FakeWindow(FakeNode(className = "android.widget.FrameLayout", children = listOf(row)))).snapshot.elements

        assertEquals(listOf("Ana Petrović Online", "Ana Petrović", "Online"), elements.map { it.label })
        assertEquals(listOf(null, 0, 0), elements.map { it.parent })
        assertNull("the row's own text", elements[0].text)
    }

    @Test
    fun passwordTextNeverAppearsAndTheHintNamesTheField() {
        val field = FakeNode(text = "hunter2", hint = "Password", editable = true, password = true)

        val snapshot = build(FakeWindow(field)).snapshot

        assertNull(snapshot.elements.single().text)
        assertEquals("Password", snapshot.elements.single().label)
        assertFalse(ScreenJson.encode(snapshot).contains("hunter2"))
    }

    @Test
    fun aTextFieldKeepsItsNameAndItsKeyWhileTheOwnerTypes() {
        fun field(text: String?) = FakeNode(text = text, hint = "Message", className = "android.widget.EditText", editable = true)

        // Compose shows a text field's label, placeholder and character counter as texts inside the field.
        fun composeField(text: String?, vararg inside: String) =
            FakeNode(text = text, className = "android.widget.EditText", editable = true, children = inside.map { FakeNode(text = it) })

        fun element(node: FakeNode) = build(FakeWindow(node)).snapshot.elements.first()

        val typed = element(field("Hello Ana"))

        assertEquals("Message", typed.label)
        assertEquals("Hello Ana", typed.text)
        assertEquals(element(field(null)).key, typed.key)
        assertEquals("Name", element(composeField("Ana", "Name")).label)
        assertEquals(element(composeField(null, "Type a message", "0/160")).key, element(composeField("Hello", "Clear", "5/160")).key)
    }

    @Test
    fun anUnlabeledButtonGetsANameFromItsViewId() {
        val button = FakeNode(viewId = "dev.app:id/compose_button", className = "android.widget.ImageButton", clickable = true)

        val element = build(FakeWindow(button)).snapshot.elements.single()

        assertNull(element.label)
        assertEquals("compose", element.suggestedName)
        assertEquals("dev.app|compose_button||ImageButton|0", element.key)
    }

    @Test
    fun similarSiblingsAreNumberedAndRepeatedKeysGetASuffix() {
        fun item() = FakeNode(text = "Item", viewId = "dev.app:id/row", className = "android.widget.TextView")
        val list = FakeNode(scrollable = true, className = "android.widget.ListView", children = listOf(item(), item()))
        val dialog = FakeNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                FakeNode(contentDescription = "Group", children = listOf(FakeNode(text = "OK", className = "android.widget.Button"))),
                FakeNode(contentDescription = "Group", children = listOf(FakeNode(text = "OK", className = "android.widget.Button"))),
            ),
        )

        val keys = build(FakeWindow(FakeNode(children = listOf(list, dialog)))).snapshot.elements.map { it.key }

        assertEquals(
            listOf(
                "dev.app|||ListView|0",
                "dev.app|row|Item|TextView|0",
                "dev.app|row|Item|TextView|1",
                "dev.app||Group|View|0",
                "dev.app||OK|Button|0",
                "dev.app||Group|View|1",
                "dev.app||OK|Button|0~2",
            ),
            keys,
        )
    }

    @Test
    fun overlaysAreLeftOutAndTheTopWindowComesFirst() {
        val snapshot = build(
            FakeWindow(FakeNode(text = "App"), id = 1, layer = 1),
            FakeWindow(FakeNode(text = "Number badge", packageName = "dev.kestrel"), id = 2, layer = 9, isOverlay = true),
            FakeWindow(FakeNode(text = "Clock", packageName = "com.android.systemui"), id = 3, layer = 5, kind = WindowKind.SYSTEM),
        ).snapshot

        assertEquals(listOf(3, 1), snapshot.windows.map { it.id })
        assertEquals(listOf("Clock", "App"), snapshot.elements.map { it.label })
        assertEquals("com.android.systemui", snapshot.windows.first().packageName)
    }

    @Test
    fun invisibleSubtreesAndOffscreenNodesAreLeftOutAndBoundsAreClippedToTheWindow() {
        val root = FakeNode(
            children = listOf(
                FakeNode(text = "Hidden", visible = false, children = listOf(FakeNode(text = "Hidden child"))),
                FakeNode(text = "Below the window", bounds = IntRect(0, 3000, 100, 3100)),
                FakeNode(text = "Partly visible", bounds = IntRect(-50, 2350, 100, 2450)),
            ),
        )

        val elements = build(FakeWindow(root)).snapshot.elements

        assertEquals(listOf("Partly visible"), elements.map { it.label })
        assertEquals(IntRect(0, 2350, 100, 2400), elements.single().bounds)
    }

    @Test
    fun eachKeyLeadsBackToItsNode() {
        val button = FakeNode(text = "Send", clickable = true)

        val result = build(FakeWindow(FakeNode(children = listOf(button))))

        assertSame(button, result.nodes.getValue(result.snapshot.elements.single().key))
        assertEquals(1_000L, result.snapshot.takenAtMillis)
    }
}
