package dev.kestrel.providers.discovery

import kotlinx.serialization.Serializable

/**
 * A profile's last model list with its time (design D13), kept in a setting of its own (`provider-models-<profile ID>`),
 * because lists of hundreds of rows should not be rewritten at every change of a profile. Deleting a profile writes an
 * empty list.
 */
@Serializable
data class ModelList(val models: List<DiscoveredModel> = emptyList(), val refreshedAt: Long? = null) {
    override fun toString(): String = "ModelList(models=${models.size}, refreshedAt=$refreshedAt)"
}
