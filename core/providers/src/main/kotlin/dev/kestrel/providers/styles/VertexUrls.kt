package dev.kestrel.providers.styles

import okhttp3.HttpUrl

/**
 * The addresses of Vertex AI (design D4 and D9, research r35): `global` uses `aiplatform.googleapis.com`, `us` and `eu`
 * use `aiplatform.<location>.rep.googleapis.com`, and every other location `<location>-aiplatform.googleapis.com`. The
 * location becomes part of the host that receives the service account's token, so it must be a plain name, and the
 * project ID must have Google's form (with an optional `domain:` prefix), so that a broken key file cannot build another
 * path, for example through `..`.
 */
object VertexUrls {
    private val LOCATION = Regex("^[a-z0-9-]+$")
    private val PROJECT = Regex("^(?:[a-z0-9.-]+:)?[a-z][a-z0-9-]{4,28}[a-z0-9]$")

    fun validLocation(location: String): Boolean = LOCATION.matches(location)

    /** Whether [projectId] has the form of a Google Cloud project ID. */
    fun validProject(projectId: String): Boolean = PROJECT.matches(projectId)

    /** `https://<host>/v1/projects/<project>/locations/<location>`; throws IllegalArgumentException for a bad location or project. */
    fun base(location: String, projectId: String): HttpUrl {
        require(validLocation(location)) { "A location must match ${LOCATION.pattern}" }
        require(validProject(projectId)) { "A project ID must match ${PROJECT.pattern}" }
        val host = when (location) {
            "global" -> "aiplatform.googleapis.com"
            "us", "eu" -> "aiplatform.$location.rep.googleapis.com"
            else -> "$location-aiplatform.googleapis.com"
        }
        return HttpUrl.Builder().scheme("https").host(host)
            .addPathSegment("v1").addPathSegment("projects").addPathSegment(projectId)
            .addPathSegment("locations").addPathSegment(location)
            .build()
    }

    /** The path that the network log records: the project, which names the owner's account, becomes `{project}` (design D16). */
    fun logPath(url: HttpUrl): String {
        val segments = url.encodedPathSegments
        return "/" + segments.mapIndexed { index, segment -> if (index == 2 && segments.getOrNull(1) == "projects") "{project}" else segment }.joinToString("/")
    }
}
