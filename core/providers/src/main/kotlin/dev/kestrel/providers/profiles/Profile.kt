package dev.kestrel.providers.profiles

import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.LocalServerKind
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.cost.Price
import kotlinx.serialization.Serializable

/**
 * A provider, the sign-in choice that made it, a credential and two models (spec 10.6, design D13). [baseUrl] is set only
 * when it is not the choice's or the catalog's (a custom address, an Azure endpoint, a local server, MiniMax's
 * `resource_url`); [region] is Bedrock's or Mantle's region or Vertex's location; [discoveredHosts] are the hosts that the
 * sign-in and discovery accepted (design D16). The ID and the provider are printed; nothing else, because names and
 * addresses can be the owner's own.
 */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val providerId: String,
    val choiceId: String,
    val method: AuthMethod,
    val credentialId: String?,
    val baseUrl: String? = null,
    val region: String? = null,
    val localServer: LocalServerKind? = null,
    val lightweight: ProfileModel,
    val versatile: ProfileModel,
    val discoveredHosts: List<String> = emptyList(),
    val signedInAt: Long? = null,
) {
    fun model(role: ModelRole): ProfileModel = if (role == ModelRole.LIGHTWEIGHT) lightweight else versatile

    override fun toString(): String = "Profile(id=$id, provider=$providerId)"
}

/**
 * A profile's model for one role: [style] is a style that the owner chose (an Azure deployment's API), [jsonActions] turns
 * on JSON actions (design D7), [price] is a price that the owner typed (design D14), and [images] and [tools] are the
 * owner's switches for a local server's model (design D11). A local server's model names may be the owner's own, so only
 * the ID's length is printed.
 */
@Serializable
data class ProfileModel(
    val id: String,
    val style: RequestStyle? = null,
    val jsonActions: Boolean = false,
    val price: Price? = null,
    val images: Boolean? = null,
    val tools: Boolean? = null,
) {
    override fun toString(): String =
        "ProfileModel(idLength=${id.length}, style=$style, jsonActions=$jsonActions, price=${if (price == null) "none" else "typed"})"
}

/** The lightweight model does most steps; the versatile one takes over when a task needs it (spec 10.6, 11.7). */
@Serializable
enum class ModelRole { LIGHTWEIGHT, VERSATILE }
