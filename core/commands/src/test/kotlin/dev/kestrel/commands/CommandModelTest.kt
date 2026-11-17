package dev.kestrel.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CommandModelTest {
    @Test
    fun theOwnersWordsNeverReachToString() {
        val secret = "Ana Petrović 0641234567"
        val printed = listOf(
            Target.Label(secret),
            Command.Tap(Target.Label(secret)),
            Command.Bare(secret, null),
            Command.CommandsFor(Target.Label(secret)),
            TextScope.Phrase(secret),
            TextScope.FromTo(secret, secret),
            TextScope.Between(secret, secret),
            Place.Before(secret),
            Place.After(secret),
            Place.Between(secret, secret),
            TextCommand.Type(secret),
            TextCommand.Insert(secret, Place.Before(secret)),
            TextCommand.Replace(secret, secret),
            TextCommand.ReplaceBetween(secret, secret, secret),
            TextCommand.ChangeCase(TextCase.UPPER, secret),
            TextCommand.Delete(TextScope.Phrase(secret)),
            TextCommand.Search(secret),
            PhoneCommand.OpenApp(secret, work = false),
            CallTarget.Number(secret),
            CallTarget.Name(secret, secret),
            PhoneCommand.Call(CallTarget.Name(secret, "mobile")),
            CommandResult(Outcome.DONE, say = secret, show = secret, passOn = secret, acted = Command.Bare(secret, null)),
        ).map { it.toString() }

        for (text in printed) {
            assertFalse(text, text.contains("Ana") || text.contains("064") || text.contains("mobile"))
        }
    }

    @Test
    fun bareOfReadsAWholeSpokenNumber() {
        assertEquals(Command.Bare("21", 21), Command.Bare.of("21"))
        assertEquals(Command.Bare("Number twenty one.", 21), Command.Bare.of("Number twenty one."))
        assertEquals(Command.Bare("Send", null), Command.Bare.of("Send"))
        // A lone "to" is a number only right after a verb or "number", or as an answer.
        assertEquals(Command.Bare("to", null), Command.Bare.of("to"))
        assertEquals(Command.Bare("Number to", 2), Command.Bare.of("Number to"))
    }
}
