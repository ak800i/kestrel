package dev.kestrel.eval.hook

/**
 * One function per debug hook that :tools:eval uses (design D3 of sub-project 6; scripts\hook.ps1 lists the hooks). A
 * hook that only reads is tried again after a lost answer; one that acts runs once, and throws [HookLost] when its answer
 * was lost, so that the caller reads the state back.
 */
class Hooks(private val client: HookClient) {
    /**
     * agent-task -Result: `state=running`, or `state=done` with the task's end, counts, model and tokens; with [marker],
     * whether the reply held it, and with [digits] (design D17 of sub-project 6), whether its digits held them.
     */
    fun agentTask(tag: String, marker: String? = null, recipient: String? = null, digits: String? = null): HookAnswer =
        read(
            "agent-task", Extra.Flag("result", true), Extra.Text("tag", tag), marker?.let { Extra.Text("marker", it) },
            recipient?.let { Extra.Text("recipient", it) }, digits?.let { Extra.Text("digits", it) },
        )

    /** agent-state: the task's phase, its open question's kind, the hand-over and the open confirmation's category. */
    fun agentState(tag: String): HookAnswer = read("agent-state", Extra.Text("tag", tag))

    /** agent-answer: the owner's answer to the task's open question; a confirmation's answer carries the category that agent-state read. */
    fun agentAnswer(tag: String, answer: String, confirming: String?): HookAnswer =
        act("agent-answer", Extra.Text("tag", tag), Extra.Text("answer", answer), confirming?.let { Extra.Text("confirming", it) })

    /** agent-script -File: installs the scripted model from Kestrel's files/agent-scripts/[file]. */
    fun agentScript(file: String): HookAnswer = act("agent-script", Extra.Text("file", file))

    /** agent-script -Clear: removes the scripted model. */
    fun agentScriptClear(): HookAnswer = act("agent-script", Extra.Flag("clear", true))

    fun agentMemoryAdd(key: String, text: String): HookAnswer = act("agent-memory", Extra.Flag("add", true), Extra.Text("key", key), Extra.Text("text", text))

    fun agentMemoryRemove(key: String): HookAnswer = act("agent-memory", Extra.Flag("remove", true), Extra.Text("key", key))

    /** agent-memory -Has: whether a fact with [key] exists, never its value. */
    fun agentMemoryHas(key: String): Boolean = read("agent-memory", Extra.Flag("has", true), Extra.Text("key", key))["has"] == "yes"

    /** agent-settings: test mode's owner addresses and the fixtures' self-chat aliases; it answers counts only, and only reads without both. */
    fun agentSettings(ownerEmails: String? = null, selfChatAliases: String? = null): HookAnswer =
        if (ownerEmails == null && selfChatAliases == null) {
            read("agent-settings")
        } else {
            act("agent-settings", ownerEmails?.let { Extra.Text("ownerEmails", it) }, selfChatAliases?.let { Extra.Text("selfChatAliases", it) })
        }

    /** agent-live: the device's live session; reads it without [on], opens or closes it with [on]. */
    fun agentLive(on: Boolean? = null): HookAnswer = if (on == null) read("agent-live") else act("agent-live", Extra.Flag("on", on))

    /** agent-tree: writes the screen's structure, with only safe labels, to Kestrel's cache; answers its path. */
    fun agentTree(): HookAnswer = read("agent-tree")

    /** screen-has: the count of the front app's elements that match, with the first one's bounds and the app in front. */
    fun screenHas(packageName: String? = null, label: String? = null, contains: String? = null, className: String? = null, state: String? = null): HookAnswer =
        read(
            "screen-has",
            packageName?.let { Extra.Text("package", it) },
            label?.let { Extra.Text("label", it) },
            contains?.let { Extra.Text("contains", it) },
            className?.let { Extra.Text("class", it) },
            state?.let { Extra.Text("state", it) },
        )

    /**
     * screen-tap: the script's own tap, or with [long] long press, on the first element with exactly [label], or whose label
     * holds [contains]; with [packageName] only while that app is in front (design D17 of sub-project 6). Refused in
     * Kestrel, a phone app and a contacts app.
     */
    fun screenTap(label: String? = null, contains: String? = null, long: Boolean = false, packageName: String? = null): HookAnswer =
        act(
            "screen-tap", label?.let { Extra.Text("label", it) }, contains?.let { Extra.Text("contains", it) },
            Extra.Flag("long", true).takeIf { long }, packageName?.let { Extra.Text("package", it) },
        )

    /** network-allowlist: the hosts of the gateway's allowlist and the model download's hosts (`hosts=`, `modelHosts=`). */
    fun networkAllowlist(): HookAnswer = read("network-allowlist")

    /** selfchat-check: whether test mode's recogniser takes the chat on the screen for a self-chat. */
    fun selfchatCheck(): Boolean = read("selfchat-check")["selfchat"] == "yes"

    /** state: whether the screen layer runs, and test mode. */
    fun state(): HookAnswer = read("state")

    fun testMode(on: Boolean): HookAnswer = act("test-mode", Extra.Flag("on", on))

    /** voice-state: the Orchestrator's mode and the voice session, never text. */
    fun voiceState(): HookAnswer = read("voice-state")

    /** voice -Send heard: the transcript [text] with [tag], as if Kestrel had heard it; [live] and [model] go with its task. */
    fun voiceHeard(text: String, tag: String, live: Boolean = false, model: String? = null): HookAnswer =
        act(
            "voice",
            Extra.Text("event", "heard"),
            Extra.Text("text", text),
            Extra.Text("tag", tag),
            Extra.Flag("live", true).takeIf { live },
            model?.let { Extra.Text("model", it) },
        )

    /** provider-standin: the default profile's kind (none, copilot or other) and whether the stand-in runs. */
    fun providerStandin(): HookAnswer = read("provider-standin")

    private fun read(command: String, vararg extras: Extra?): HookAnswer = client.call(command, extras.filterNotNull(), retry = true)

    private fun act(command: String, vararg extras: Extra?): HookAnswer = client.call(command, extras.filterNotNull(), retry = false)
}
