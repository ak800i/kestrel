package dev.kestrel.app.settings

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kestrel.app.R
import dev.kestrel.voice.models.ModelStatus

/** The texts that the About page shows. The build copies them from the repository into the app's assets. */
enum class Licence(val asset: String) {
    KESTREL("licences/LICENSE"),
    NOTICES("licences/THIRD_PARTY_NOTICES.md"),
    APACHE("licences/Apache-2.0.txt"),
    MIT("licences/MIT.txt"),
    ONNX_RUNTIME("licences/onnxruntime-ThirdPartyNotices.txt"),
    MPL("licences/MPL-2.0.txt"),
    HCLUST("licences/hclust-cpp.txt"),
}

/** The About page: versions, the status of the model files and the licences (design spec, section 15). */
@Composable
internal fun AboutPage(data: SettingsData, models: ModelCheck, onOpen: (Licence) -> Unit, onBack: () -> Unit) {
    PageHeader(stringResource(R.string.settings_about), onBack)
    Text(stringResource(R.string.about_version, data.version), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.about_android, Build.VERSION.RELEASE, Build.VERSION.SDK_INT))
    Text(stringResource(R.string.about_models), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
    when (models) {
        ModelCheck.Checking -> Text(stringResource(R.string.about_models_checking))
        ModelCheck.Failed -> Text(stringResource(R.string.about_models_check_failed))
        is ModelCheck.Done -> for (status in models.statuses) {
            for (line in modelLines(status)) Text(line)
        }
    }
    Text(stringResource(R.string.about_licences), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
    for (licence in Licence.entries) {
        val texts = texts(licence)
        PageEntry(stringResource(texts.first), stringResource(texts.second)) { onOpen(licence) }
    }
}

/** A model's name and licence, and whether its files are ready: one line for missing files and one for damaged files. */
@Composable
private fun modelLines(status: ModelStatus): List<String> {
    val name = "${status.model.name} (${status.model.licence})"
    val lines = mutableListOf<String>()
    if (status.missing.isNotEmpty()) {
        lines += pluralStringResource(R.plurals.about_model_missing, status.missing.size, name, status.missing.size)
    }
    if (status.damaged.isNotEmpty()) {
        lines += pluralStringResource(R.plurals.about_model_damaged, status.damaged.size, name, status.damaged.size)
    }
    if (lines.isEmpty()) lines += stringResource(R.string.about_model_ready, name)
    return lines
}

/** One licence text, in paragraphs. */
@Composable
internal fun LicencePage(licence: Licence, data: SettingsData, onBack: () -> Unit) {
    var paragraphs by remember(licence) { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(licence) { paragraphs = licenceParagraphs(data.licenceText(licence)) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item { PageHeader(stringResource(texts(licence).first), onBack) }
        val shown = paragraphs
        if (shown == null) {
            item { Text(stringResource(R.string.about_reading)) }
        } else {
            items(shown) { Text(it, Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/**
 * Splits a text into paragraphs at blank lines. The licence texts wrap their lines at about 70 characters, so the
 * lines of a paragraph are joined and the screen wraps them; tables and lists keep their own lines.
 */
internal fun licenceParagraphs(text: String): List<String> =
    text.split(BLANK_LINE).mapNotNull { paragraph ->
        val lines = paragraph.lines().map(String::trim).filter(String::isNotEmpty)
        when {
            lines.isEmpty() -> null
            lines.any { line -> LINE_STARTS.any(line::startsWith) } -> lines.joinToString("\n")
            else -> lines.joinToString(" ")
        }
    }

private val BLANK_LINE = Regex("\\n\\s*\\n")
private val LINE_STARTS = listOf("|", "- ", "* ")

/** The title and summary of a licence on the About page. */
private fun texts(licence: Licence): Pair<Int, Int> = when (licence) {
    Licence.KESTREL -> R.string.about_licence_kestrel to R.string.about_licence_kestrel_summary
    Licence.NOTICES -> R.string.about_licence_notices to R.string.about_licence_notices_summary
    Licence.APACHE -> R.string.about_licence_apache to R.string.about_licence_apache_summary
    Licence.MIT -> R.string.about_licence_mit to R.string.about_licence_mit_summary
    Licence.ONNX_RUNTIME -> R.string.about_licence_onnxruntime to R.string.about_licence_onnxruntime_summary
    Licence.MPL -> R.string.about_licence_mpl to R.string.about_licence_mpl_summary
    Licence.HCLUST -> R.string.about_licence_hclust to R.string.about_licence_hclust_summary
}
