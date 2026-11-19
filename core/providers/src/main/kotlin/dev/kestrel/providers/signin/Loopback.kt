package dev.kestrel.providers.signin

/** The loopback addresses that a redirect names (design D10). */
enum class LoopbackAddresses {
    /** `http://127.0.0.1:<port>/…`: the ChatGPT plan. */
    IPV4,

    /** `http://localhost:<port>/…`, which Chrome tries on `::1` first: OpenRouter. */
    BOTH,
}

/**
 * The loopback server of a browser sign-in, which `:app` gives over `:core:privacy`'s LoopbackServer, because only the
 * privacy module may open a socket (design D1 and D10). [open] returns null when the port is busy on any address that it
 * needs; a null [port] means a free port.
 */
interface LoopbackListener {
    suspend fun open(addresses: LoopbackAddresses, port: Int?, path: String): LoopbackSession?
}

/**
 * One listening server. [await] waits for a `GET` of the exact path with [expectedState] and returns its query, or null
 * after [timeoutMillis] or on close; a request with another state gets an error page, and the server keeps waiting.
 */
interface LoopbackSession : AutoCloseable {
    val port: Int

    suspend fun await(expectedState: String, timeoutMillis: Long): LoopbackCallback?
}

/** The callback's query, which holds the authorization code and the state, so toString prints only the field names. */
class LoopbackCallback(val query: Map<String, String>) {
    override fun toString(): String = "LoopbackCallback(fields=${query.keys.sorted()})"
}

/**
 * A sign-in in the owner's browser with a loopback redirect (design D9 and D10). [run] opens the listener before it calls
 * [openBrowser] with the sign-in page's address, and waits at most 5 minutes for the callback. [signInHosts] are the hosts
 * of Kestrel's own requests while it runs; the caller leases them on the allowlist.
 */
interface BrowserFlow {
    val signInHosts: Set<String>

    suspend fun run(loopback: LoopbackListener, openBrowser: (String) -> Unit): SignInResult
}
