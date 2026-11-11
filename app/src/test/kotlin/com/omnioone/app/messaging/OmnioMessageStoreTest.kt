package com.omnioone.app.messaging

import org.junit.Assert.assertEquals
import org.junit.Test

class OmnioMessageStoreTest {
    @Test
    fun pictureMessageSecondsBecomeMilliseconds() {
        assertEquals(1_700_000_000_000L, OmnioMessageStore.toMillis(1_700_000_000L))
    }

    @Test
    fun textMillisecondsStayTheSame() {
        assertEquals(1_700_000_000_123L, OmnioMessageStore.toMillis(1_700_000_000_123L))
    }

    @Test
    fun recipientIdsAreRead() {
        assertEquals(listOf(12L, 34L, 56L), OmnioMessageStore.parseRecipientIds("12 34  56"))
        assertEquals(emptyList<Long>(), OmnioMessageStore.parseRecipientIds(null))
    }
}
