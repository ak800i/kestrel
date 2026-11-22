package dev.kestrel.app.settings

/**
 * Whether a profile of Bedrock (provider `amazon-bedrock`) names an application inference profile as a role's model, which
 * Kestrel's agent cannot use (design D32 item 37 of sub-project 5): such an ARN does not say which model it runs, so the
 * agent refuses it, and the Providers page shows the same sentence on the profile.
 */
internal fun bedrockApplicationProfile(providerId: String, modelIds: List<String>): Boolean =
    providerId == "amazon-bedrock" && modelIds.any { ":application-inference-profile/" in it }
