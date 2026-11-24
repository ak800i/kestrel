package dev.kestrel.eval.command

import dev.kestrel.eval.egress.EgressSetup
import dev.kestrel.eval.egress.Pcapdroid
import dev.kestrel.eval.egress.PcapdroidKeys
import dev.kestrel.eval.egress.ReleaseDownload

/**
 * `egress-setup --device <s>` (design D2 and D11 of sub-project 6): PCAPdroid's one-time setup, with its APK and its keys
 * in `eval\local\`, which git ignores; prints `egress-setup=<device> result=<checked|set-up>`.
 */
class EgressSetupCommand(private val environment: Environment, private val command: Command.EgressSetup) {
    fun execute(): Int {
        val session = Session(environment, command.device)
        val local = environment.root.resolve("eval").resolve("local")
        return session.guarded {
            val setup = EgressSetup(session.device, session.hooks, environment.clock, PcapdroidKeys(local.resolve("pcapdroid-keys.json"))) {
                ReleaseDownload().get(Pcapdroid.RELEASE, local)
            }
            session.console.line("egress-setup=${session.device.kind.folder} result=${setup.run()}")
            0
        }
    }
}
