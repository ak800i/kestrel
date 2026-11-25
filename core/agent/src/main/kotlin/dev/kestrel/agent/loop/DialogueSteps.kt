package dev.kestrel.agent.loop

import dev.kestrel.agent.OwnerLines
import dev.kestrel.agent.memory.Fact
import dev.kestrel.agent.memory.FactKind
import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.port.AnswerKind
import dev.kestrel.agent.tools.AgentCall
import dev.kestrel.commands.text.Normalizer

/**
 * The tools that talk with the owner (design D2, D17 and D22 of sub-project 5): `ask_user` and `say`, and the memory tools
 * `remember` and `forget`, which always ask him first (spec 11.4). They talk through the run's [OwnerTalk]. Plan 7A moved
 * them out of `TaskRun`.
 */
internal class DialogueSteps(private val run: TaskRun) {
    suspend fun askUser(call: AgentCall.AskUser): Done {
        val text = when (val answer = run.talk.ask(run.spoken(call.question), AnswerKind.FREE)) {
            is Answer.Text -> answer.text
            is Answer.Number -> answer.n.toString()
            Answer.Yes -> "yes"
            Answer.No, Answer.Silence, Answer.Interrupted -> null
        }
        if (text == null) return Done(call.tool, Outcome.NoAnswer)
        run.safety = run.safety.withOwnerWords(text)
        // His answer is his own words, never cut (design 7.3 of sub-project 7).
        return Done(call.tool, Outcome.Answered, extra = Redacted.kestrel(OwnerLines.said(Redacted.of(run.redactor, text).text)))
    }

    suspend fun say(call: AgentCall.Say): Done {
        run.talk.say(run.spoken(call.text))
        return Done(call.tool, text = "Result: said.", summary = "Step ${run.replies}: said something to the owner.", last = "said", progress = false)
    }

    // A store that fails changes nothing (D22).
    suspend fun remember(call: AgentCall.Remember): Done {
        val stored = run.talk.yes("Remember that ${run.spoken(call.key)} is ${run.spoken(call.value)}?")
        val saved = stored && run.recalled(false) {
            run.ports.memory.saveFact(Fact(call.key.trim(), call.value.trim(), FactKind.FACT, run.ports.clock.now().toInstant().toEpochMilli()))
            true
        }
        val words = when {
            saved -> "stored"
            stored -> "Kestrel could not store it"
            else -> "the owner said no"
        }
        return Done(call.tool, text = "Result: $words.", summary = "Step ${run.replies}: remember → $words.", last = "remember → $words", progress = saved)
    }

    suspend fun forget(call: AgentCall.Forget): Done {
        val fact = run.recalled(emptyList()) { run.ports.memory.facts() }.firstOrNull { Normalizer.normalize(it.key) == Normalizer.normalize(call.key) }
        val words = when {
            fact == null -> "no such fact"
            run.talk.yes("Forget ${run.spoken(fact.key)}?") -> if (run.recalled(false) { run.ports.memory.deleteFact(fact.key) }) "deleted" else "no such fact"
            else -> "the owner said no"
        }
        return Done(call.tool, text = "Result: $words.", summary = "Step ${run.replies}: forget → $words.", last = "forget → $words", progress = words == "deleted")
    }

    override fun toString(): String = "DialogueSteps"
}
