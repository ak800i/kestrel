package dev.kestrel.app.commands

import dev.kestrel.screen.overlay.NumbersMode
import kotlinx.serialization.Serializable

/**
 * The owner's options for the instant commands (design spec, sections 9.5 and 9.8): [requireVerbs] stops bare labels
 * from tapping, and [numbers] chooses between numbers on all elements and on the unlabeled ones only.
 */
@Serializable
data class CommandOptions(val requireVerbs: Boolean = false, val numbers: NumbersMode = NumbersMode.ALL)
