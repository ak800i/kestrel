package dev.kestrel.screen.read

import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenJson
import dev.kestrel.screen.model.WindowKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
    fun aSliderIsActionableWithoutANameAndAProgressBarIsNot() {
        val slider = FakeNode(className = "android.widget.SeekBar", slider = true)
        // A determinate progress bar has a range too, but no action that moves it.
        val progress = FakeNode(className = "android.widget.ProgressBar", stateDescription = "40%")
        val root = FakeNode(className = "android.widget.LinearLayout", children = listOf(slider, progress))

        val elements = build(FakeWindow(root)).snapshot.elements

        assertEquals(listOf("android.widget.SeekBar", "android.widget.ProgressBar"), elements.map { it.className })
        assertEquals(listOf(true, false), elements.map { it.slider })
        assertEquals(listOf(true, false), elements.map { it.isActionable })
        assertFalse(elements.first().copy(enabled = false).isActionable)
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
    fun aSupplementalDescriptionNamesAFieldBeforeItsHintButDoesNotReplaceAText() {
        // WebView and Chrome 157 give a form field's name as its supplemental description, and keep only the field's
        // placeholder or help text, such as a character counter, in the hint.
        fun field(hint: String) =
            FakeNode(text = "", hint = hint, supplementalDescription = "Email", className = "android.widget.EditText", editable = true)
        val select = FakeNode(text = "Serbia", supplementalDescription = "Country", className = "android.widget.Spinner", clickable = true)

        fun key(field: FakeNode) = build(FakeWindow(field)).snapshot.elements.single().key

        val elements = build(FakeWindow(FakeNode(children = listOf(field("email@example.com"), select)))).snapshot.elements

        assertEquals(listOf("Email", "Serbia"), elements.map { it.label })
        assertEquals(listOf("Email", "Country"), elements.map { it.supplementalDescription })
        assertEquals("dev.app||Email|EditText|0", key(field("0/280")))
        assertEquals(key(field("0/280")), key(field("5/280")))
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

    /**
     * The place check reads the windows that the events named as moving: here a dialog that slides over an app, under
     * the navigation bar's buttons. The first, the middle and the last actionable element of the whole snapshot would all
     * lie in windows that stand still.
     */
    @Test
    fun thePlaceCheckReadsTheElementsOfTheWindowsThatMayMove() {
        fun button(text: String, top: Int) = FakeNode(text = text, clickable = true, bounds = IntRect(0, top, 300, top + 100))
        val navigation = FakeWindow(FakeNode(children = listOf(button("Back", 2300), button("Home", 2300), button("Recents", 2300))), id = 1, kind = WindowKind.SYSTEM, layer = 3)
        val dialogButton = button("Delete", 1200)
        val dialog = FakeWindow(FakeNode(children = listOf(dialogButton)), id = 2, layer = 2)
        val app = FakeWindow(FakeNode(children = (0 until 5).map { button("Row $it", 300 + 120 * it) }), id = 3, layer = 1)
        val result = build(navigation, dialog, app)
        val live = LiveSnapshot(result.snapshot, result.nodes, result.anchors)
        // The dialog's button has moved 16 px since the snapshot; everything else stands still.
        val fresh = { node: NodeData -> if (node === dialogButton) node.bounds.copy(top = 1184, bottom = 1284) else node.bounds }

        assertFalse(live.inPlace(setOf(2), fresh))
        assertTrue(live.inPlace(setOf(1, 3), fresh))
        // An event of a window that the snapshot does not have counts as in place.
        assertTrue(live.inPlace(setOf(9), fresh))
    }

    @Test
    fun aWindowWithoutActionableElementsIsCheckedByItsRootsFirstVisibleChildren() {
        val banner = FakeNode(className = "android.widget.ImageView", bounds = IntRect(0, 100, 1080, 400))
        val hidden = FakeNode(visible = false, bounds = IntRect(0, 0, 10, 10))
        val toast = FakeWindow(FakeNode(children = listOf(hidden, banner)), id = 5)
        val result = build(toast)
        val live = LiveSnapshot(result.snapshot, result.nodes, result.anchors)

        assertEquals(listOf(banner), result.anchors.getValue(5).map { it.node })
        assertTrue(live.inPlace(setOf(5)) { it.bounds })
        assertFalse(live.inPlace(setOf(5)) { if (it === banner) IntRect(0, 140, 1080, 440) else it.bounds })
        assertFalse("A node that is gone has moved", live.inPlace(setOf(5)) { null })
    }
}