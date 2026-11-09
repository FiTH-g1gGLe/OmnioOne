package com.omnioone.messages.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** iPhone and Google Messages reaction texts must turn into "👍 to “…”" style reactions. */
class OmnioReactionsTest {

    @Test
    fun googleMessagesReactions() {
        assertEquals("👍 to “Infact no lol”", OmnioReactions.describe("👍 to ' Infact no lol '"))
        assertEquals("😮 to “See you at 8”", OmnioReactions.describe("Reacted 😮 to “See you at 8”"))
    }

    @Test
    fun iphoneReactions() {
        assertEquals("👍 to “Yes 🥰”", OmnioReactions.describe("Liked “Yes 🥰”"))
        assertEquals("❤️ to “Night”", OmnioReactions.describe("Loved \"Night\""))
        assertEquals("😂 to “lol”", OmnioReactions.describe("Laughed at “lol”"))
        assertEquals("Removed 👍 from “Yes”", OmnioReactions.describe("Removed a like from “Yes”"))
    }

    @Test
    fun normalTextsAreNotReactions() {
        assertNull(OmnioReactions.describe("Getting on now won't be long"))
        assertNull(OmnioReactions.describe("I said \"hello\" to him"))
        assertNull(OmnioReactions.describe("Does this work?"))
    }
}
