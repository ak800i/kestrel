package dev.kestrel.eval.task

import dev.kestrel.eval.device.Dumps

/** An app op of a package during the run, such as the fixtures' cross-app overlay, put back to the mode that it had before. */
data class AppOp(val packageName: String, val op: String, val mode: String) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        val before = mode(scope)
        val byHand = "Run: adb shell appops set $packageName $op $before"
        scope.restores.push("the app op $op of $packageName", byHand) {
            scope.device.idempotent("appops set $packageName $op $before")
        }
        scope.restores.change("the app op $op of $packageName set to $mode", byHand) {
            scope.device.idempotent("appops set $packageName $op $mode")
        }
        return SetupResult.Ready
    }

    override fun probe(scope: RunScope): Map<String, String> = mapOf("app-op:$op" to mode(scope))

    private fun mode(scope: RunScope): String = Dumps.appOpMode(scope.device.read("appops get $packageName $op"), op)
}

/**
 * A contact of the local account `kestrel-test`, which has no sync adapter, as plan 3C's tests and task-check.ps1 add
 * them for the injection fixtures: a raw contact with a name and the fictional number of design D28 of sub-project 5. Its
 * restore deletes every contact of that account, which holds only the tests' contacts. A lost insert is read back by the
 * account's count; a lost data row fails the run, and the restore deletes the contact.
 */
data class FixtureContact(val name: String) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        val what = "the fixture contacts of the account kestrel-test"
        val byHand = "Contacts: delete the contacts named $name."
        scope.restores.push(what, byHand) {
            if (scope.device.once(DELETE) == null) check(count(scope) == 0) { "fixture contacts are left" }
        }
        val before = count(scope)
        val inserted = scope.restores.change(what, byHand) {
            scope.device.once(
                "content insert --uri content://com.android.contacts/raw_contacts --bind account_type:s:$ACCOUNT --bind account_name:s:$ACCOUNT",
            )
        }
        if (inserted == null && count(scope) != before + 1) throw SetupFailed("The fixture contact did not reach the contacts.")
        val id = Dumps.largestRowId(scope.device.read(QUERY))
        val rows = listOf(
            "--bind raw_contact_id:i:$id --bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:${shellQuoted(name)}",
            "--bind raw_contact_id:i:$id --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind data1:s:+12025550143 --bind data2:i:2",
        )
        for (row in rows) {
            scope.restores.change(what, byHand) { scope.device.once("content insert --uri content://com.android.contacts/data $row") }
                ?: throw SetupFailed("A row of the fixture contact may be missing.")
        }
        return SetupResult.Ready
    }

    override fun probe(scope: RunScope): Map<String, String> = mapOf("contacts:$ACCOUNT" to "${count(scope)}")

    private fun count(scope: RunScope): Int = Dumps.rowCount(scope.device.read(QUERY))

    companion object {
        const val ACCOUNT = "kestrel-test"
        const val QUERY = "content query --uri content://com.android.contacts/raw_contacts --projection _id --where account_name=\\'$ACCOUNT\\'"
        const val DELETE = "content delete --uri 'content://com.android.contacts/raw_contacts?caller_is_syncadapter=true' --where account_name=\\'$ACCOUNT\\'"
    }
}

/**
 * An injection fixture of `:testapp` (`InjectionActivity --es fixture inject-<nn>`), stopped after the run; fixture 7's
 * notification is cancelled again first.
 */
data class InjectionScreen(val number: Int) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        val fixture = InjectionFixtures.name(number)
        val what = "the injection fixture $fixture closed"
        val byHand = "Close Kestrel's fixture app, and clear its notification."
        scope.restores.push(what, byHand) {
            scope.device.idempotent("am force-stop ${Fixtures.PACKAGE}")
            if (number == NOTIFICATION) {
                scope.device.idempotent("am start -W -n ${Fixtures.PACKAGE}/.InjectionActivity --es fixture '$fixture' --ez cancel true")
                scope.device.idempotent("am force-stop ${Fixtures.PACKAGE}")
            }
        }
        scope.restores.change(what, byHand) {
            scope.device.idempotent("am start -W -S -n ${Fixtures.PACKAGE}/.InjectionActivity --es fixture '$fixture'")
        }
        return SetupResult.Ready
    }

    private companion object {
        const val NOTIFICATION = 7
    }
}
