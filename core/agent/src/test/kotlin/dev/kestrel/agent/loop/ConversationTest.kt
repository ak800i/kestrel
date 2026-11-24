package dev.kestrel.agent.loop

import dev.kestrel.agent.DataResultText
import dev.kestrel.agent.observe.Observation
import dev.kestrel.agent.observe.ObservationBuilder
import dev.kestrel.agent.observe.ObservationInput
import dev.kestrel.agent.observe.ScreenText
import dev.kestrel.agent.port.DataField
import dev.kestrel.agent.port.DataLine
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.testing.ScreenBuilder
import dev.kestrel.agent.tools.Tools
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ChatRequests
import dev.kestrel.providers.chat.ImageType
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.ReasoningLevel
import dev.kestrel.providers.chat.ReasoningState
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.ToolChoice
import dev.kestrel.safety.AgentFreeApps
import dev.kestrel.safety.Redactor
import dev.kestrel.safety.Vault
import dev.kestrel.screen.model.ScreenSnapshot
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTest {
    private val vault = Vault()
    private val redactor = Redactor(vault)
    private val builder = ObservationBuilder(redactor, AgentFreeApps())

    private fun chat(title: String, vararg messages: String): ScreenSnapshot = ScreenBuilder.screen("com.viber.voip") {
        text(title, 100, viewId = "toolbar_title")
        messages.forEachIndexed { index, message -> text(message, 400 + index * 130) }
        field("Type a message…", "", 2100)
        button("Send", 2100, left = 900)
    }

    private fun observe(step: Int, snapshot: ScreenSnapshot, previous: Observation? = null): Observation =
        builder.build(ObservationInput(step, snapshot, "Viber", previous = previous))

    private fun conversation(
        first: Observation,
        signedThinking: Boolean = false,
        budget: Int? = null,
        context: String? = "Facts the owner saved: none.",
    ) = Conversation(
        system = "SYSTEM PROMPT",
        tools = Tools.ALL,
        context = context?.let { Redacted.of(redactor, it) },
        task = Redacted.of(redactor, "Send Ana a Viber message"),
        first = first,
        taskId = "task-1-0a1b2c3d",
        signedThinking = signedThinking,
        budget = budget,
    )

    private fun reply(id: String, vararg extra: AssistantPart): ChatMessage.Assistant =
        ChatMessage.Assistant(extra.toList() + AssistantPart.ToolCall(id, Tools.TAP, buildJsonObject { put("target", 3) }))

    private fun tapStep(number: Int, observation: Observation, vararg extra: AssistantPart, screenshot: Part.Image? = null) = Step(
        number = number,
        reply = reply("call-$number", *extra),
        results = listOf(ResultText("call-$number", Tools.TAP, Redacted.kestrel("Result: tapped \"Ana\" (row) → another screen."))),
        observedCall = "call-$number",
        observation = observation,
        summary = Redacted.kestrel("Step $number: tapped \"Ana\" (row) in Viber → another screen."),
        screenshot = screenshot,
    )

    private fun request(conversation: Conversation, step: Int): ChatRequest =
        conversation.request(ToolChoice.REQUIRED, null, step)!!.also { ChatRequests.check(it) }

    private fun texts(request: ChatRequest): List<String> = request.messages.map { message ->
        when (message) {
            is ChatMessage.User -> message.parts.joinToString("|") { if (it is Part.Text) it.text else "IMAGE" }
            is ChatMessage.Assistant -> "ASSISTANT"
            is ChatMessage.ToolResult -> message.text
        }
    }

    @Test
    fun theFirstRequestHasTheTaskTheFirstObservationAndTheFixedParts() {
        val first = observe(1, chat("Chats", "Ana Petrović"))
        val request = conversation(first).request(ToolChoice.REQUIRED, null, step = 1)!!

        assertEquals("SYSTEM PROMPT", request.system)
        assertEquals(Tools.ALL, request.tools)
        assertEquals("Facts the owner saved: none.", request.context)
        assertEquals(listOf("Send Ana a Viber message", first.text), texts(request))
        assertEquals(0, request.cacheAfter)
        assertEquals(ToolChoice.REQUIRED, request.toolChoice)
        assertEquals(Conversation.MAX_OUTPUT_TOKENS, request.maxOutputTokens)
        assertEquals(Conversation.CACHE_KEY, request.cacheKey)
        assertEquals("task-1-0a1b2c3d", request.meta.taskId)
        assertFalse(request.meta.agentStep)
    }

    @Test
    fun laterStepsAreAgentStepsAndKeepTheirReasoningLevel() {
        val first = observe(1, chat("Chats", "Ana Petrović"))
        val conversation = conversation(first)
        conversation.add(tapStep(1, observe(2, chat("Ana Petrović", "Hi"), first)))

        val request = conversation.request(ToolChoice.AUTO, ReasoningLevel.LOW, step = 2)!!

        assertTrue(request.meta.agentStep)
        assertEquals(ToolChoice.AUTO, request.toolChoice)
        assertEquals(ReasoningLevel.LOW, request.reasoning)
    }

    @Test
    fun aThirdObservationShrinksTheOldestOnceAndRelabelsTheSecond() {
        val first = observe(1, chat("Chats", "Ana Petrović"))
        val second = observe(2, chat("Ana Petrović", "Hi"), first)
        val third = observe(3, chat("Ana Petrović", "Hi", "See you"), second)
        val conversation = conversation(first)
        conversation.add(tapStep(1, second))

        val two = request(conversation, 2)
        assertEquals(first.earlier(), texts(two)[1])
        assertEquals("Result: tapped \"Ana\" (row) → another screen.\n" + second.text, texts(two)[3])
        assertEquals(0, two.cacheAfter)

        conversation.add(tapStep(2, third))
        val three = request(conversation, 3)
        assertEquals(first.firstScreen(), texts(three)[1])
        assertEquals("Result: tapped \"Ana\" (row) → another screen.\n" + second.earlier(), texts(three)[3])
        assertEquals("Result: tapped \"Ana\" (row) → another screen.\n" + third.text, texts(three)[5])
        assertEquals(1, three.cacheAfter)
    }

    @Test
    fun theStableRegionNeverChangesOnceWritten() {
        val observations = mutableListOf(observe(1, chat("Chats", "Ana Petrović")))
        val conversation = conversation(observations[0])
        var before: ChatRequest? = null
        for (step in 1..6) {
            observations += observe(step + 1, chat("Ana Petrović", "Message $step"), observations.last())
            conversation.add(tapStep(step, observations.last()))
            val now = request(conversation, step + 1)
            if (before != null) {
                val stable = before.cacheAfter!! + 1
                assertEquals(before.messages.take(stable), now.messages.take(stable))
                assertTrue(now.cacheAfter!! >= before.cacheAfter!!)
            }
            before = now
        }
        assertEquals("Step 4: tapped \"Ana\" (row) in Viber → another screen.", texts(before!!)[9])
    }

    @Test
    fun aScreenshotShrinksWithItsStep() {
        val first = observe(1, chat("Chats", "Ana Petrović"))
        val second = observe(2, chat("Ana Petrović", "Hi"), first)
        val third = observe(3, chat("Ana Petrović", "Hi", "Bye"), second)
        val fourth = observe(4, chat("Ana Petrović", "Hi", "Bye", "Ok"), third)
        val image = Part.Image(ImageType.JPEG, byteArrayOf(1, 2, 3))
        val conversation = conversation(first)
        conversation.add(tapStep(1, second, screenshot = image))
        conversation.add(tapStep(2, third))
        assertEquals("IMAGE", texts(request(conversation, 3))[4])

        conversation.add(tapStep(3, fourth))
        val request = request(conversation, 4)
        assertEquals("Step 1: tapped \"Ana\" (row) in Viber → another screen.", texts(request)[3])
        assertEquals("(The screenshot of step 1 was here.)", texts(request)[4])
    }

    @Test
    fun theReasoningStateStaysWithItsAssistantMessageAfterTheShrink() {
        val first = observe(1, chat("Chats", "Ana Petrović"))
        val second = observe(2, chat("Ana Petrović", "Hi"), first)
        val third = observe(3, chat("Ana Petrović", "Hi", "Bye"), second)
        val fourth = observe(4, chat("Ana Petrović", "Hi", "Bye", "Ok"), third)
        val responses = AssistantPart.Reasoning(ReasoningState(RequestStyle.RESPONSES, "openai", "gpt-6-luna", JsonPrimitive("enc")))
        val conversation = conversation(first, signedThinking = true)
        conversation.add(tapStep(1, second, responses))
        conversation.add(tapStep(2, third))
        conversation.add(tapStep(3, fourth))

        val request = request(conversation, 4)
        assertTrue(texts(request)[3].startsWith("Step 1:"))
        assertEquals(responses, (request.messages[2] as ChatMessage.Assistant).parts.first())
    }

    @Test
    fun claudesSignedThinkingLeavesTheStableRegionAtTheShrinkAndGrowOnlyStopsEveryRewrite() {
        fun thinking(n: Int) = AssistantPart.Reasoning(ReasoningState(RequestStyle.ANTHROPIC, "anthropic", "claude-sonnet-5-5", JsonPrimitive("sig-$n")))
        val observations = mutableListOf(observe(1, chat("Chats", "Ana Petrović")))
        val conversation = conversation(observations[0], signedThinking = true)
        for (step in 1..3) {
            observations += observe(step + 1, chat("Ana Petrović", "Message $step"), observations.last())
            conversation.add(tapStep(step, observations.last(), thinking(step)))
        }
        val request = request(conversation, 4)
        val assistants = request.messages.filterIsInstance<ChatMessage.Assistant>()
        assertEquals(listOf(false, true, true), assistants.map { reply -> reply.parts.any { it is AssistantPart.Reasoning } })

        conversation.growOnly()
        observations += observe(5, chat("Ana Petrović", "Message 4"), observations.last())
        conversation.add(tapStep(4, observations.last(), thinking(4)))
        val grown = request(conversation, 5)
        assertEquals(listOf(true, true, true, true), grown.messages.filterIsInstance<ChatMessage.Assistant>().map { reply -> reply.parts.any { it is AssistantPart.Reasoning } })
        assertEquals("Result: tapped \"Ana\" (row) → another screen.\n" + observations[2].text, texts(grown)[5])
        assertTrue(texts(grown).any { it.endsWith(observations[3].text) })
        assertFalse(texts(grown).any { it.contains(observations[3].earlier()) })
        assertTrue(conversation.growsOnly)
    }

    @Test
    fun growOnlyResendsTheRefusedStepWithoutTheRewriteThatCausedIt() {
        fun thinking(n: Int) = AssistantPart.Reasoning(ReasoningState(RequestStyle.ANTHROPIC, "anthropic", "claude-sonnet-5-5", JsonPrimitive("sig-$n")))
        val observations = mutableListOf(observe(1, chat("Chats", "Ana Petrović")))
        val conversation = conversation(observations[0], signedThinking = true)
        for (step in 1..2) {
            observations += observe(step + 1, chat("Ana Petrović", "Message $step"), observations.last())
            conversation.add(tapStep(step, observations.last(), thinking(step)))
        }
        val accepted = request(conversation, 3)
        observations += observe(4, chat("Ana Petrović", "Message 3"), observations.last())
        conversation.add(tapStep(3, observations.last(), thinking(3)))
        val refused = request(conversation, 4)

        conversation.growOnly()
        val retry = request(conversation, 4)

        assertFalse(refused.messages == retry.messages)
        assertEquals(accepted.messages, retry.messages.take(accepted.messages.size))
        assertEquals(accepted.messages.size + 2, retry.messages.size)
        assertEquals(listOf(true, true, true), retry.messages.filterIsInstance<ChatMessage.Assistant>().map { reply -> reply.parts.any { it is AssistantPart.Reasoning } })

        observations += observe(5, chat("Ana Petrović", "Message 4"), observations.last())
        conversation.add(tapStep(4, observations.last(), thinking(4)))
        val next = request(conversation, 5)
        assertEquals(retry.messages, next.messages.take(retry.messages.size))
    }

    @Test
    fun aRewriteThatAProviderAcceptedStaysAfterGrowOnly() {
        val observations = mutableListOf(observe(1, chat("Chats", "Ana Petrović")))
        val conversation = conversation(observations[0], signedThinking = true)
        for (step in 1..3) {
            observations += observe(step + 1, chat("Ana Petrović", "Message $step"), observations.last())
            conversation.add(tapStep(step, observations.last()))
        }
        val accepted = request(conversation, 4)
        conversation.add(Step(4, ChatMessage.Assistant(listOf(AssistantPart.Text("Shall I?"))), emptyList(),
            reprompt = Redacted.kestrel("Reply with exactly one tool call.")))
        val refused = request(conversation, 5)

        conversation.growOnly()

        assertEquals(refused.messages, request(conversation, 5).messages)
        assertEquals(accepted.messages, refused.messages.take(accepted.messages.size))
    }

    @Test
    fun aLocalOllamaRequestLeavesRoomForTheWholeReply() {
        val observations = mutableListOf(observe(1, chat("Chats", "Ana Petrović")))
        val unlimited = conversation(observations[0], context = null)
        for (step in 1..2) {
            observations += observe(step + 1, chat("Ana Petrović", "Message $step"), observations.last())
            unlimited.add(tapStep(step, observations.last()))
        }
        val tokens = tokensOf(unlimited.request(ToolChoice.REQUIRED, null, 3)!!)

        fun budgeted(budget: Int) = conversation(observations[0], budget = budget, context = null).also { conversation ->
            for (step in 1..2) conversation.add(tapStep(step, observations[step]))
        }.request(ToolChoice.REQUIRED, null, 3)

        assertNull(budgeted((tokens / 0.9).toInt() + 1))
        val fits = budgeted(((tokens + Conversation.MAX_OUTPUT_TOKENS) / 0.9).toInt() + 1)
        assertNotNull(fits)
        assertEquals(tokens, tokensOf(fits!!))
    }

    @Test
    fun everyCallOfAStepKeepsItsResult() {
        val first = observe(1, chat("Chats", "Ana Petrović"))
        val second = observe(2, chat("Ana Petrović", "Hi"), first)
        val two = ChatMessage.Assistant(listOf(
            AssistantPart.ToolCall("a", Tools.TAP, buildJsonObject { put("target", 3) }),
            AssistantPart.ToolCall("b", Tools.BACK, buildJsonObject { put("reason", "x") }),
        ))
        val conversation = conversation(first)
        conversation.add(Step(
            number = 1,
            reply = two,
            results = listOf(
                ResultText("a", Tools.TAP, Redacted.kestrel("Result: tapped.")),
                ResultText("b", Tools.BACK, Redacted.kestrel("Not run: Kestrel runs one action per step."), failed = true),
            ),
            observedCall = "a",
            observation = second,
            summary = Redacted.kestrel("Step 1: tapped."),
        ))

        val request = request(conversation, 2)
        assertEquals(listOf("a", "b"), request.messages.filterIsInstance<ChatMessage.ToolResult>().map { it.callId })
        assertThrows(IllegalArgumentException::class.java) {
            Step(1, two, listOf(ResultText("a", Tools.TAP, Redacted.kestrel("Result: tapped."))))
        }
    }

    @Test
    fun aReplyWithoutACallGetsItsRepromptAfterIt() {
        val first = observe(1, chat("Chats", "Ana Petrović"))
        val conversation = conversation(first)
        conversation.add(Step(1, ChatMessage.Assistant(listOf(AssistantPart.Text("Shall I?"))), emptyList(),
            reprompt = Redacted.kestrel("Reply with exactly one tool call.")))

        val request = request(conversation, 2)
        assertEquals(listOf("Send Ana a Viber message", first.text, "ASSISTANT", "Reply with exactly one tool call."), texts(request))
    }

    @Test
    fun anObservationAfterAPauseGoesInAUserMessageAndShrinksToo() {
        val first = observe(1, chat("Chats", "Ana Petrović"))
        val second = observe(2, chat("Ana Petrović", "Hi"), first)
        val third = observe(3, chat("Ana Petrović", "Hi", "Bye"), second)
        val conversation = conversation(first)
        conversation.addObservation(1, Redacted.kestrel("The task was paused; this is the screen now."), second)
        assertEquals("The task was paused; this is the screen now.\n" + second.text, texts(request(conversation, 1))[2])

        conversation.add(tapStep(1, third))
        conversation.add(tapStep(2, observe(4, chat("Ana Petrović", "Hi", "Bye", "Ok"), third)))
        assertEquals(second.firstScreen().replaceFirst("First screen", "Screen after the pause"), texts(request(conversation, 3))[2])
    }

    @Test
    fun aLocalOllamaModelLeavesOutWholeOldStepsWithinItsBudget() {
        val observations = mutableListOf(observe(1, chat("Chats", "Ana Petrović")))
        val unlimited = conversation(observations[0], context = null)
        val limited = conversation(observations[0], budget = 1, context = null)
        for (step in 1..8) {
            observations += observe(step + 1, chat("Ana Petrović", "Message $step"), observations.last())
            unlimited.add(tapStep(step, observations.last()))
        }
        val full = unlimited.request(ToolChoice.REQUIRED, null, 9)!!
        val stableSteps = (full.cacheAfter!! - 1) / 2
        assertEquals(6, stableSteps)

        // A budget that holds the fixed part, the shrunk first screen, the tail, one of the six shrunk steps and the line
        // that counts the others.
        val line = ScreenText.tokens("(5 earlier steps left out.)")
        val tokens = tokensOf(full) - 5 * shrunkStepTokens() + line
        val budgeted = conversation(observations[0], budget = ((tokens + Conversation.MAX_OUTPUT_TOKENS) / 0.9).toInt() + 1, context = null)
        for (step in 1..8) budgeted.add(tapStep(step, observations[step]))
        val request = budgeted.request(ToolChoice.REQUIRED, null, 9)!!
        ChatRequests.check(request)
        assertEquals("(5 earlier steps left out.)", texts(request)[2])
        assertEquals("Step 6: tapped \"Ana\" (row) in Viber → another screen.", texts(request)[4])
        assertNull(limited.request(ToolChoice.REQUIRED, null, 1))
        assertEquals(32_768, Conversation.budget(localOllama = true, window = null))
        assertEquals(8_192, Conversation.budget(localOllama = true, window = 8_192))
        assertNull(Conversation.budget(localOllama = false, window = 8_192))
    }

    private fun shrunkStepTokens(): Int =
        ScreenText.tokens(Tools.TAP + buildJsonObject { put("target", 3) }.toString()) +
            ScreenText.tokens("Step 1: tapped \"Ana\" (row) in Viber → another screen.")

    private fun tokensOf(request: ChatRequest): Int {
        val text = ScreenText::tokens
        var total = text(request.system) + request.tools.sumOf { text(it.name + it.description + it.parameters.toString()) }
        for (message in request.messages) total += when (message) {
            is ChatMessage.User -> message.parts.sumOf { text((it as Part.Text).text) }
            is ChatMessage.Assistant -> message.toolCalls.sumOf { text(it.name + it.arguments.toString()) }
            is ChatMessage.ToolResult -> text(message.text)
        }
        return total
    }

    @Test
    fun aCodeBesideItsKeywordReachesTheRequestOnlyAsAPlaceholder() {
        val screen = ScreenBuilder.screen("com.google.android.apps.messaging") {
            text("Your verification code", 400)
            text("483920", 530)
        }
        val conversation = conversation(observe(1, screen))

        val all = texts(request(conversation, 1)).joinToString("\n")
        assertTrue(all.contains("[OTP_1]"))
        assertFalse(all.contains("483920"))
    }

    @Test
    fun aCodeInAnSmsBodyWithALineBreakReachesTheRequestOnlyAsAPlaceholder() {
        val first = observe(1, chat("Chats", "Ana Petrović"))
        val conversation = conversation(first)
        val sms = DataResult.Ok(listOf(DataLine("SMS 1 · 10:20", listOf(
            DataField("from", "Banka"),
            DataField("body", "Poštovani,\nkod za potvrdu je 483920"),
        ))))
        val call = ChatMessage.Assistant(listOf(AssistantPart.ToolCall("s", Tools.LIST_SMS, buildJsonObject { put("limit", 1) })))
        conversation.add(Step(
            number = 1,
            reply = call,
            results = listOf(ResultText("s", Tools.LIST_SMS, Redacted.kestrel("Result: list_sms → ok (1 items)."))),
            observedCall = "s",
            data = DataResultText.of(sms, redactor),
            summary = Redacted.kestrel("Step 1: list_sms → 1 items."),
        ))

        val result = texts(request(conversation, 2))[3]
        assertTrue(result.contains("body: \"Poštovani,\\nkod za potvrdu je [OTP_1]\""))
        assertFalse(result.contains("483920"))
    }

    @Test
    fun aLongPressOnACodeNamesItByItsPlaceholderInTheResultAndTheSummary() {
        val screen = ScreenBuilder.screen("com.google.android.apps.messaging") {
            text("Your verification code", 400)
            button("483920", 530)
        }
        val first = observe(1, screen)
        val target = first.table.entries.single { it.label == "483920" }
        val result = StepResult(1, Tools.LONG_PRESS, Outcome.TargetChanged, target = target, appLabel = "Messages")
        val conversation = conversation(first)
        val observations = mutableListOf(first)
        for (step in 1..3) {
            observations += observe(step + 1, screen, observations.last())
            conversation.add(Step(
                number = step,
                reply = ChatMessage.Assistant(listOf(AssistantPart.ToolCall("p$step", Tools.LONG_PRESS, buildJsonObject { put("target", target.number) }))),
                results = listOf(ResultText("p$step", Tools.LONG_PRESS, Redacted.kestrel(result.resultLine()))),
                observedCall = "p$step",
                observation = observations.last(),
                summary = Redacted.kestrel(result.copy(step = step).summary()),
            ))
        }

        val all = texts(request(conversation, 4))
        assertTrue(all[3].startsWith("Step 1:"))
        assertTrue(all[3].contains("[OTP_1]"))
        assertTrue(all[5].contains("[OTP_1]"))
        assertFalse(all.joinToString("\n").contains("483920"))
        assertNotNull(vault.valueOf("[OTP_1]"))
    }

    // Design 7.3 of sub-project 7: an owner's message follows the last step and never changes.
    @Test
    fun anOwnersMessageFollowsTheLastStepAndNeverChanges() {
        val first = observe(1, chat("Chats", "Ana Petrović"))
        val second = observe(2, chat("Ana Petrović", "Hi"), first)
        val third = observe(3, chat("Ana Petrović", "Hi", "See you"), second)
        val fourth = observe(4, chat("Ana Petrović", "Hi", "See you", "Bye"), third)
        val conversation = conversation(first)
        conversation.add(tapStep(1, second))
        val message = Redacted.kestrel("The owner added (typed) at step 1: \"Also Mila\"")

        conversation.addOwnerMessage(1, message)

        assertEquals(message.text, texts(request(conversation, 2)).last())
        conversation.add(tapStep(2, third))
        conversation.add(tapStep(3, fourth))
        val later = texts(request(conversation, 4))
        assertEquals(1, later.count { it == message.text })
        assertEquals(later.indexOf("Step 1: tapped \"Ana\" (row) in Viber → another screen.") + 1, later.indexOf(message.text))
    }

    // Design 7.3 of sub-project 7: a local model's budget leaves out old steps whole, but never an owner's message.
    @Test
    fun aLocalModelsBudgetLeavesOutOldStepsButNeverAnOwnersMessage() {
        val observations = mutableListOf(observe(1, chat("Chats", "Ana Petrović")))
        for (step in 1..5) observations += observe(step + 1, chat("Ana Petrović", "Message $step"), observations.last())
        val message = Redacted.kestrel("The owner added (spoken) at step 1: \"Also Mila\"")
        fun built(budget: Int?) = conversation(observations[0], budget = budget, context = null).also { conversation ->
            conversation.add(tapStep(1, observations[1]))
            conversation.addOwnerMessage(1, message)
            for (step in 2..5) conversation.add(tapStep(step, observations[step]))
        }
        val full = built(null).request(ToolChoice.REQUIRED, null, 6)!!

        val tight = built(((tokensOf(full) - 2 * shrunkStepTokens() + Conversation.MAX_OUTPUT_TOKENS) / 0.9).toInt() + 1).request(ToolChoice.REQUIRED, null, 6)!!

        ChatRequests.check(tight)
        assertTrue(texts(tight).any { it.endsWith("earlier steps left out.)") })
        assertTrue(texts(tight).contains(message.text))
    }
}
