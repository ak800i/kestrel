package dev.kestrel.providers.discovery

import dev.kestrel.providers.catalog.InputKind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ModelListTest {
    @Test
    fun aListSurvivesJsonAndPrintsNoModelNames() {
        val list = ModelList(
            listOf(DiscoveredModel("my-private-finetune", "My private finetune", input = setOf(InputKind.TEXT, InputKind.IMAGE), tools = false)),
            refreshedAt = 1_700_000_000_000,
        )

        val printed = list.toString() + list.models.single().toString()

        assertEquals(list, Json.decodeFromString(ModelList.serializer(), Json.encodeToString(ModelList.serializer(), list)))
        assertFalse(printed, printed.contains("private"))
        assertEquals("ModelList(models=1, refreshedAt=1700000000000)", list.toString())
    }
}
