package dev.kestrel.app.download

import dev.kestrel.privacy.network.BlockedRequestException
import dev.kestrel.privacy.network.ModelDownloadTag
import dev.kestrel.privacy.network.NetworkGateway
import dev.kestrel.voice.models.ModelDownloader
import dev.kestrel.voice.models.ModelFiles

/**
 * The model download's downloader, whose requests go through [gateway] with [ModelDownloadTag], so that they may reach
 * the model hosts (release plan). This is the only place in the app that may use the tag: kestrelAudit refuses it
 * elsewhere (audit/rules.txt).
 */
internal fun gatewayDownloader(files: ModelFiles, gateway: NetworkGateway): ModelDownloader =
    ModelDownloader(files, gateway.callFactory, refused = { it is BlockedRequestException }) {
        it.tag(ModelDownloadTag::class.java, ModelDownloadTag)
    }