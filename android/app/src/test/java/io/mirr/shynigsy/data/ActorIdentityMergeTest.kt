package io.mirr.shynigsy.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActorIdentityMergeTest {
    private val client = ShyniClient("https://synthetic.example", "synthetic-account", deviceId = "synthetic-device")

    private fun detail(neutral: List<JSONObject>, raw: List<JSONObject>) = JSONObject()
        .put("metadata_sections", JSONArray().put(JSONObject().put("presentation", "person_row")
            .put("groups", JSONArray().put(JSONObject().put("id", "cast").put("items", JSONArray(neutral)))
                .put(JSONObject().put("id", "crew").put("items", JSONArray().put(JSONObject().put("title", "Crew")))))))
        .put("extra", JSONObject().put("actors", JSONArray(raw)))

    private fun neutral(name: String, id: Int) = JSONObject().put("title", name)
        .put("action", JSONObject().put("type", "open_person").put("value", id.toString()))

    private fun raw(name: String, id: Int, ppkey: String) = JSONObject().put("name", name)
        .put("tmdb_id", id).put("ppkey", ppkey)

    @Test fun serverNeutralPersonReceivesExplicitExtraIdentityWithSameTmdbId() {
        // The server's neutral open_person card intentionally omits ppkey.
        val actual = client.parseCastMembers(detail(listOf(neutral("Alias", 71)), listOf(raw("Name", 71, "actor-71"))))
        assertEquals(1, actual.size)
        assertEquals(71, actual.single().tmdbId)
        assertEquals("actor-71", actual.single().ppkey)
    }

    @Test fun conflictingPpkeysForOneTmdbIdAreNotMerged() {
        val actual = client.parseCastMembers(detail(listOf(neutral("Person", 71)),
            listOf(raw("Person", 71, "actor-a"), raw("Person", 71, "actor-b"))))
        assertNull(actual.single().ppkey)
    }

    @Test fun samePpkeyLinkedToDifferentTmdbIdsIsNotMerged() {
        val actual = client.parseCastMembers(detail(listOf(neutral("Person", 71), neutral("Other", 72)),
            listOf(raw("Person", 71, "shared"), raw("Other", 72, "shared"))))
        assertTrue(actual.all { it.ppkey == null })
    }

    @Test fun matchingNamesNeverJoinDifferentTmdbIdentities() {
        val actual = client.parseCastMembers(detail(listOf(neutral("Same name", 71)),
            listOf(raw("Same name", 72, "actor-72"))))
        assertNull(actual.single().ppkey)
    }

    @Test fun explicitlyEmptyNeutralCastIsNotFilledFromExtraOrCrew() {
        assertTrue(client.parseCastMembers(detail(emptyList(), listOf(raw("Person", 71, "actor-71")))).isEmpty())
    }
}
