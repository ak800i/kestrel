package dev.kestrel.safety

import dev.kestrel.commands.text.Normalizer
import dev.kestrel.screen.model.ScreenSnapshot
import kotlinx.serialization.Serializable

/** The owner's changes to the shipped list of agent-free apps, stored apart from it (design D16 of sub-project 5). */
@Serializable
data class AgentFreeChanges(val added: List<String> = emptyList(), val removed: List<String> = emptyList()) {
    override fun toString(): String = "AgentFreeChanges(added=${added.size}, removed=${removed.size})"
}

/**
 * The apps in which the agent never acts (design D16 of sub-project 5; spec 12.5): the shipped list of research r62
 * (Serbian banks, payment providers and card readers) and r62b (password managers, authenticators and Samsung's secure
 * apps), changed by the owner's [added] and [removed] names, so that a later version's new shipped names still count.
 * Names are matched whole, never by prefix, because Serbian banks share vendor prefixes with other countries' banks.
 * Kestrel itself is always agent-free. Google Play services is not on the list: [PlayServices] has its own rule.
 */
class AgentFreeApps(val added: Set<String> = emptySet(), val removed: Set<String> = emptySet()) {
    val effective: Set<String> = SHIPPED - removed + added

    fun isAgentFree(packageName: String?): Boolean = packageName == KESTREL || packageName in effective

    override fun toString(): String = "AgentFreeApps(added=${added.size}, removed=${removed.size})"

    companion object {
        const val KESTREL = "dev.kestrel"
        const val PLAY_SERVICES = "com.google.android.gms"

        /** r62's rows marked "yes", then r62b's, without Google Password Manager's row and with Samsung Wallet once. */
        val SHIPPED: Set<String> = linkedSetOf(
            // Serbian banks and their business apps and card readers (r62).
            "hr.asseco.android.intesa.isbd.bib", "rs.get.intesa.bizmobi", "com.payten.supercase",
            "hr.asseco.android.jimba.mUCI.rs", "hr.asseco.android.jimba.mUCI.sme.rs",
            "rs.Raiffeisen.mobile", "rs.raiffeisen.raipay", "rs.raiffeisen.softpos",
            "eu.newfrontier.iBanking.mobile.SOG.Retail", "eu.newfrontier.iBanking.mobile.SOG.Corporate",
            "eu.newfrontier.iBanking.mobile.AIK.Retail", "com.halcom.mobile.hybrid.aikbrs22", "hr.asseco.android.aikcorp.sme",
            "hr.asseco.android.jimba.KomBank", "com.halcom.mobile.hybrid.kobbrsbg", "rs.nlbkb.klik",
            "rs.erstebank.george", "com.halcom.mobile.hybrid.gibars22",
            "com.addiko.mobile.serbia", "com.addiko.corp.srb",
            "rs.asseco.android.ae.procredit", "rs.yettelbank.app",
            "dem2.droid.rs.halkbanka", "dem2.sme.droid.rs.halkbanka",
            "rs.asseco.android.ae.bps", "com.halcom.mobile.hybrid.sbporsbg", "tizi.transfer.is.easy.tizi",
            "eu.newfrontier.iBanking.mobile.alta", "rs.asseco.android.ae.api",
            // Payment, wallet and fintech apps (r62).
            "com.google.android.apps.walletnfcrel", "com.samsung.android.spay", "com.paypal.android.p2pmobile",
            "com.revolut.revolut", "com.revolut.business", "com.revolut.junior", "com.transferwise.android", "de.number26.android",
            "com.zen.application",
            // Password managers (r62b).
            "com.x8bit.bitwarden", "com.onepassword.android", "com.lastpass.lpandroid", "com.dashlane", "proton.android.pass",
            "com.kunzisoft.keepass.free", "com.kunzisoft.keepass.libre", "keepass2android.keepass2android",
            "keepass2android.keepass2android_nonet", "io.enpass.app", "com.nordpass.android.app.password.manager",
            "com.callpod.android_apps.keeper", "com.siber.roboform",
            // Authenticators (r62b).
            "com.bitwarden.authenticator", "com.lastpass.authenticator", "proton.android.authenticator",
            "com.google.android.apps.authenticator2", "com.azure.authenticator", "com.authy.authy", "com.beemdevelopment.aegis",
            "com.twofasapp", "org.fedorahosted.freeotp", "org.liberty.android.freeotpplus", "io.ente.auth",
            "org.shadowice.flocke.andotp", "com.duosecurity.duomobile", "com.yubico.yubioath", "com.okta.android.auth",
            // Samsung's secure apps (r62b); Samsung Wallet is above.
            "com.samsung.knox.securefolder", "com.samsung.knox.securefolder.setuppage", "com.sec.knox.foldercontainer",
            "com.samsung.android.samsungpass", "com.samsung.android.samsungpassautofill", "com.samsung.android.authfw",
            "com.samsung.knox.keychain", "com.samsung.android.coldwalletservice",
        )

        /** The words of a launcher label that make the setup checklist suggest the app (design D16), folded. */
        val SUGGESTION_WORDS: List<String> = listOf(
            "bank", "banka", "banca", "pay", "wallet", "novcanik", "password", "lozinka", "authenticator", "2fa", "otp", "token",
            "vault", "keepass", "pass",
        )

        /** Whether an app with this launcher [label] is a suggestion for the list: its folded label holds one of the words. */
        fun suggests(label: String): Boolean {
            val folded = Normalizer.normalize(label).replace(" ", "")
            return SUGGESTION_WORDS.any { it in folded }
        }

        fun from(changes: AgentFreeChanges): AgentFreeApps = AgentFreeApps(changes.added.toSet(), changes.removed.toSet())
    }
}

/**
 * Google Password Manager lives inside Google Play services, which cannot be agent-free as a whole without breaking every
 * Play services dialog (design D16 of sub-project 5): every window of it is left out of observations, screenshots and
 * OCR, and when one is the active window or covers more than half of the screen, the agent hands over.
 */
object PlayServices {
    fun handsOver(snapshot: ScreenSnapshot): Boolean {
        val screen = snapshot.windows.fold(0L) { area, window -> maxOf(area, window.bounds.width.toLong() * window.bounds.height) }
        return snapshot.windows.any { window ->
            window.packageName == AgentFreeApps.PLAY_SERVICES &&
                (window.active || window.bounds.width.toLong() * window.bounds.height * 2 > screen)
        }
    }
}
