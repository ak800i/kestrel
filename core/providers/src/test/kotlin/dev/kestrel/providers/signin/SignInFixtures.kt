package dev.kestrel.providers.signin

import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.catalog.CatalogLoader
import mockwebserver3.MockResponse
import java.io.File

/** The fixtures of plan 4C's sign-in, Copilot and client tests, and the repository's catalog. */
internal object SignInFixtures {
    /** The merged catalog of the repository's `catalog` folder, which the build passes as `-Dkestrel.catalog`. */
    val catalog: Catalog by lazy {
        File(System.getProperty("kestrel.catalog") ?: error("The build passes -Dkestrel.catalog")).let {
            CatalogLoader.load(it.resolve("providers.json").readText(), it.resolve("overrides.json").readText())
        }
    }

    /** The text of `src/test/resources/fixtures/<path>`. */
    fun read(path: String): String =
        SignInFixtures::class.java.getResource("/fixtures/$path")?.readText() ?: error("There is no fixture $path")

    /** A JSON reply with [code] and [body], and the headers [headers]. */
    fun json(code: Int, body: String, vararg headers: Pair<String, String>): MockResponse =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body)
            .apply { headers.forEach { addHeader(it.first, it.second) } }.build()

    /** The fixture at [path] as a JSON reply with [code]. */
    fun fixture(code: Int, path: String): MockResponse = json(code, read(path))
}
