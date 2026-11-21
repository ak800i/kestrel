package dev.kestrel.safety

import dev.kestrel.commands.text.Normalizer
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.ScreenTitle
import dev.kestrel.screen.model.ScreenTree
import dev.kestrel.screen.model.ScrollAxis
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary

/**
 * The context rules of the safety check (design D13 of sub-project 5; spec 12.2): compose screens, phone and contacts
 * apps, share flows, protected screens and the protected trail, credentials and payments. Their packages and words are
 * data here; plan 5B adds One UI's share panel and device-admin screen when it has recorded them.
 */
internal object ContextRules {
    const val SETTINGS = "com.android.settings"
    const val PLAY_STORE = "com.android.vending"
    const val CREDENTIAL_MANAGER = "com.android.credentialmanager"
    val SHARE_SHEETS = setOf("com.android.intentresolver", "android")
    private const val INTENT_RESOLVER = "com.android.intentresolver"

    /** The view IDs of Android's own chooser in the `android` package (review 2 M1). */
    private val CHOOSER_IDS = setOf("resolver_list", "chooser_header", "profile_pager", "chooser_action_row")

    /** The view IDs of an app's permission dialog, which some versions show in a full-height window (review 2 M11). */
    private val PERMISSION_IDS = setOf("permission_allow_button", "permission_deny_button", "grant_dialog")
    private val ROW_LISTS = listOf("RecyclerView", "ListView", "GridView")
    val PERMISSION_CONTROLLERS = setOf("com.google.android.permissioncontroller", "com.android.permissioncontroller")
    val INSTALLERS = setOf("com.android.packageinstaller", "com.google.android.packageinstaller")

    private val PLAY_WORDS = setOf("install", "update", "uninstall", "buy", "purchase", "play protect")
    private val TRAIL_WORDS = setOf(
        "security", "accessibility", "device admin", "unknown apps", "install apps", "special app access", "special access",
        "biometrics", "fingerprint", "face", "screen lock", "lock screen", "passwords",
    )
    private val NOT_MESSAGE_WORDS = setOf("search", "query", "url", "address", "find", "pretraga", "trazi")
    private val CREDENTIAL_WORDS = setOf("code", "otp", "pin", "kod", "sifra", "lozinka", "password", "card number", "cvv", "cvc")
    private val CREDENTIAL_PAGES = setOf("password manager", "passwords")
    private val PAYMENT_WORDS = setOf("checkout", "payment", "placanje", "kasa")
    private val PICKER_WORDS = setOf("search", "pretraga", "trazi", "back", "close", "nazad", "zatvori", "navigate up")
    private val CLEAR_COMPANIONS = setOf("text", "search", "query")
    private val TAB_CLASSES = listOf("TabLayout", "TabWidget", "TabRow", "TabView", "Tab")

    /** A page's heading by the title's rules 1 to 3, never the window's title (design D13). */
    fun heading(snapshot: ScreenSnapshot, window: WindowSummary): String? = ScreenTitle.of(snapshot, window, appLabel = null)

    /** The heading of a Settings page that starts a protected trail, or null. */
    fun trailHeading(snapshot: ScreenSnapshot): String? {
        val window = ScreenTree.frontWindow(snapshot)?.takeIf { it.packageName == SETTINGS } ?: return null
        return heading(snapshot, window)?.takeIf { LabelWords.holdsWord(it, TRAIL_WORDS) }
    }

    /**
     * Whether Android's permission dialog of an app shows in [window]: a permission controller's window smaller than the
     * screen, or one that shows the grant dialog's own view IDs.
     */
    fun isPermissionDialog(snapshot: ScreenSnapshot, window: WindowSummary): Boolean {
        if (window.packageName !in PERMISSION_CONTROLLERS) return false
        if (snapshot.elements.any { it.windowId == window.id && ScreenTree.idName(it.viewId) in PERMISSION_IDS }) return true
        val tallest = snapshot.windows.maxOfOrNull { it.bounds.height } ?: return false
        return window.bounds.height < tallest * 0.9
    }

    /** Whether [window] is a protected screen, on which every action that presses or types is blocked (design D13). */
    fun isProtected(snapshot: ScreenSnapshot, window: WindowSummary, trail: ProtectedTrail?): Boolean {
        val pkg = window.packageName
        if (pkg in PERMISSION_CONTROLLERS || pkg in INSTALLERS) return true
        val inWindow = snapshot.elements.filter { it.windowId == window.id }
        if (pkg == PLAY_STORE) return inWindow.any { LabelWords.holdsWord(ScreenTree.name(it), PLAY_WORDS) }
        if (pkg != SETTINGS) return false
        return trail != null || trailHeading(snapshot) != null ||
            inWindow.any { LabelWords.holdsWord(it.text ?: it.label, setOf("device admin")) }
    }

    /**
     * A field that composes a message in window [windowId]: editable, focused or not empty, and not a password, search,
     * URL or address field (its view ID, hint and names hold none of their words).
     */
    fun composeField(snapshot: ScreenSnapshot, windowId: Int): ScreenElement? = snapshot.elements.firstOrNull { element ->
        element.windowId == windowId && element.editable && !element.password &&
            (element.focused || !element.text.isNullOrBlank()) && !notMessageField(element)
    }

    /**
     * A chat's message field in window [windowId], focused or not, empty or not: so a chat whose field is empty is still a
     * chat, never a dialog (review 2-2 New 1).
     */
    fun messageField(snapshot: ScreenSnapshot, windowId: Int): ScreenElement? = snapshot.elements.firstOrNull { element ->
        element.windowId == windowId && element.editable && !element.password && !notMessageField(element)
    }

    /**
     * Whether window [windowId] shows [name] as whole words in a text outside every list of rows, so that a message in a
     * chat's list never names a recipient, and "Ana" is never found in "Anastasia" (review 2-2 New 1).
     */
    fun showsName(snapshot: ScreenSnapshot, windowId: Int, name: String): Boolean {
        val wanted = Normalizer.words(name).takeIf { it.isNotEmpty() } ?: return false
        return snapshot.elements.indices.any { index ->
            val element = snapshot.elements[index]
            element.windowId == windowId && rowsList(snapshot, index) == null &&
                listOfNotNull(element.text, element.contentDescription).any { text ->
                    val words = Normalizer.words(text)
                    (0..words.size - wanted.size).any { start -> wanted.indices.all { words[start + it] == wanted[it] } }
                }
        }
    }

    /** Whether a field's view ID, hint or names hold a search, URL or address word, so that it composes no message. */
    fun notMessageField(element: ScreenElement): Boolean =
        listOf(ScreenTree.idName(element.viewId), element.hint, element.label, element.contentDescription)
            .any { LabelWords.holdsWord(it?.replace('_', ' '), NOT_MESSAGE_WORDS) }

    /** Whether the unlabelled element [index] lies in the compose field's row or in the bar right above or below it. */
    fun nearCompose(snapshot: ScreenSnapshot, index: Int): Boolean {
        val element = snapshot.elements[index]
        if (ScreenTree.name(element) != null || element.editable) return false
        val field = composeField(snapshot, element.windowId) ?: return false
        val band = field.bounds.height
        return element.bounds.bottom > field.bounds.top - band && element.bounds.top < field.bounds.bottom + band
    }

    fun isPhoneApp(packageName: String?, phonePackages: Set<String>): Boolean =
        packageName != null && (packageName in phonePackages || packageName in EmergencyGuard.CONTACTS_PACKAGES)

    /** Whether element [index] is a credentials field, or lies on a biometric prompt, an autofill picker or a password page. */
    fun isCredential(snapshot: ScreenSnapshot, index: Int, window: WindowSummary?): Boolean {
        val element = snapshot.elements[index]
        if (element.password) return true
        val names = listOf(element.label, element.hint, element.contentDescription, ScreenTree.idName(element.viewId)?.replace('_', ' '))
        if (element.editable && names.any { LabelWords.holdsWord(it, CREDENTIAL_WORDS) }) return true
        val inWindow = snapshot.elements.filter { it.windowId == element.windowId }
        if (inWindow.any { ScreenTree.idName(it.viewId)?.lowercase()?.let { id -> "biometric" in id || "autofill" in id } == true }) return true
        return window != null && LabelWords.holdsWord(heading(snapshot, window), CREDENTIAL_PAGES)
    }

    /** Whether [window] is a checkout or payment screen: its heading or one of its buttons holds a payment word. */
    fun isPayment(snapshot: ScreenSnapshot, window: WindowSummary): Boolean =
        LabelWords.holdsWord(heading(snapshot, window), PAYMENT_WORDS) ||
            snapshot.elements.any { it.windowId == window.id && it.clickable && LabelWords.holdsWord(ScreenTree.name(it), PAYMENT_WORDS) }

    /**
     * A share picker's own control outside its list of rows (design D13, review B3-M1): in the toolbar band or a tab bar,
     * named search, back or close, or a tab. Every element inside the list of rows stays `SENDING`.
     */
    fun pickerControl(snapshot: ScreenSnapshot, index: Int, window: WindowSummary): Boolean {
        if (rowsList(snapshot, index) != null) return false
        val element = snapshot.elements[index]
        val band = window.bounds.top + window.bounds.height * ScreenTitle.TOP_BAND
        val lineage = listOf(index) + ScreenTree.ancestors(snapshot, index)
        val tabBar = lineage.any { position -> TAB_CLASSES.any { snapshot.elements[position].className.orEmpty().endsWith(it) } }
        if (!(ScreenTree.inToolbar(snapshot, index) || element.bounds.centerY < band || tabBar)) return false
        val tab = TAB_CLASSES.any { element.className.orEmpty().endsWith(it) }
        return tab || listOf(ScreenTree.name(element), ScreenTree.idName(element.viewId)?.replace('_', ' '))
            .any { LabelWords.holdsWord(it, PICKER_WORDS) }
    }

    /**
     * Whether element [index] is a field's own "Clear" (design D13, review B2-M3): its label is "Clear" alone or with
     * "text", "search", "query" or the field's name, and it lies inside an editable field or in its row.
     */
    fun clearsAField(snapshot: ScreenSnapshot, index: Int): Boolean {
        val element = snapshot.elements[index]
        val words = Normalizer.words(ScreenTree.name(element) ?: return false)
        if (words.firstOrNull() != "clear" || words.size > 3) return false
        val ancestorField = ScreenTree.ancestors(snapshot, index).map { snapshot.elements[it] }.firstOrNull { it.editable }
        val rowField = snapshot.elements.firstOrNull { field ->
            field.editable && field.windowId == element.windowId &&
                element.bounds.bottom > field.bounds.top && element.bounds.top < field.bounds.bottom
        }
        val field = ancestorField ?: rowField ?: return false
        val rest = words.drop(1)
        val fieldName = Normalizer.words(field.hint ?: field.label ?: field.contentDescription ?: "")
        return rest.isEmpty() || rest.all { it in CLEAR_COMPANIONS } || (fieldName.isNotEmpty() && rest == fieldName)
    }

    /** Whether a window above the front app's window covers the point (design D13, review B I3). */
    fun covered(snapshot: ScreenSnapshot, point: Point): Boolean {
        val front = ScreenTree.frontWindow(snapshot) ?: return true
        return snapshot.windows.any { window ->
            window.id != front.id && window.layer > front.layer &&
                point.x >= window.bounds.left && point.x < window.bounds.right && point.y >= window.bounds.top && point.y < window.bounds.bottom
        }
    }

    /** The indices of the front window's elements that hold the point, innermost last. */
    fun atPoint(snapshot: ScreenSnapshot, point: Point): List<Int> {
        val front = ScreenTree.frontWindow(snapshot) ?: return emptyList()
        return snapshot.elements.indices.filter { index ->
            val element = snapshot.elements[index]
            element.windowId == front.id && point.x >= element.bounds.left && point.x < element.bounds.right &&
                point.y >= element.bounds.top && point.y < element.bounds.bottom
        }
    }

    /**
     * Whether [window] is Android's share sheet: the intent resolver's window, or an app window of the `android` package
     * that shows the chooser's own view IDs, so that an ANR dialog or a system alert never starts a share flow.
     */
    fun isShareSheet(snapshot: ScreenSnapshot, window: WindowSummary): Boolean = when (window.packageName) {
        INTENT_RESOLVER -> window.kind != WindowKind.INPUT_METHOD
        "android" -> window.kind == WindowKind.APPLICATION &&
            snapshot.elements.any { it.windowId == window.id && ScreenTree.idName(it.viewId) in CHOOSER_IDS }
        else -> false
    }

    /** Whether a share sheet of Android shows in front. */
    fun shareSheetInFront(snapshot: ScreenSnapshot): Boolean = snapshot.windows.any { it.active && isShareSheet(snapshot, it) }

    /** Whether [element] is a list of rows, as a picker shows them, whether it scrolls or not (review 2 I1). */
    fun isRowsList(element: ScreenElement): Boolean {
        // A tab strip or a carousel is no list of rows (review 2-2 New 2).
        if (element.scrollAxis == ScrollAxis.HORIZONTAL) return false
        if (ScreenTree.isVerticalList(element) || element.scrollAxis == ScrollAxis.VERTICAL) return true
        val name = element.className.orEmpty()
        return !name.endsWith("HorizontalScrollView") && ROW_LISTS.any { name.endsWith(it) }
    }

    /** The nearest ancestor of element [index] that is a list of rows, or null. */
    fun rowsList(snapshot: ScreenSnapshot, index: Int): Int? =
        ScreenTree.ancestors(snapshot, index).firstOrNull { isRowsList(snapshot.elements[it]) }

    /** The row of a list of rows that holds element [index]: the list's direct child on its line of ancestors, or null. */
    fun row(snapshot: ScreenSnapshot, index: Int): Int? {
        val list = rowsList(snapshot, index) ?: return null
        var current = index
        while (true) {
            val parent = snapshot.elements[current].parent ?: return null
            if (parent == list) return current
            current = parent
        }
    }

    /**
     * A row's name as the screen shows it: the first text inside the row in reading order, else the row's own name, else
     * the first name inside it; null for a row without any (review 2 I1). A row named by all its texts at once ("Ana See you
     * 12:30") is named by its first text ("Ana").
     */
    fun rowName(snapshot: ScreenSnapshot, row: Int): String? {
        val inside = snapshot.elements.indices.filter { it == row || row in ScreenTree.ancestors(snapshot, it) }
            .sortedWith(compareBy({ snapshot.elements[it].bounds.top }, { snapshot.elements[it].bounds.left }))
        inside.firstNotNullOfOrNull { snapshot.elements[it].text?.trim()?.takeIf(String::isNotEmpty) }?.let { return it }
        ScreenTree.name(snapshot.elements[row])?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return inside.firstNotNullOfOrNull { ScreenTree.name(snapshot.elements[it])?.trim()?.takeIf(String::isNotEmpty) }
    }

    /** The rows of [windowId] that hold a checked element: a picker's selection as the screen shows it. */
    fun checkedRows(snapshot: ScreenSnapshot, windowId: Int): List<Int> = snapshot.elements.indices.filter {
        val element = snapshot.elements[it]
        element.windowId == windowId && element.checkable && element.checked
    }.mapNotNull { row(snapshot, it) }.distinct()
}
