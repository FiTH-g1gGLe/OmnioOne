package com.omnioone.app.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Checks the social-link rules that have caught us out before (Facebook share links, Messenger, Discord). */
class OmnioSocialsTest {

    private fun network(key: String) = OmnioSocials.network(key)

    @Test
    fun recognisesNetworksFromLinks() {
        assertEquals("facebook", OmnioSocials.networkFor("https://www.facebook.com/jones388")?.key)
        assertEquals("facebook", OmnioSocials.networkFor("https://m.facebook.com/jones388")?.key)
        assertEquals("messenger", OmnioSocials.networkFor("https://m.me/jones388")?.key)
        assertEquals("instagram", OmnioSocials.networkFor("https://www.instagram.com/jen_x?igsh=abc")?.key)
        assertEquals("x", OmnioSocials.networkFor("https://twitter.com/someone")?.key)
        assertEquals("discord", OmnioSocials.networkFor("https://discord.com/users/185102032955244556")?.key)
        assertNull(OmnioSocials.networkFor("https://example.com/jones388"))
    }

    @Test
    fun facebookShareLinksHaveNoUsername() {
        val facebook = network("facebook")
        assertEquals("", facebook.usernameFrom("https://www.facebook.com/share/1CDsYhRBec/"))
        assertEquals("jones388", facebook.usernameFrom("https://www.facebook.com/jones388"))
        assertEquals("100012345", facebook.usernameFrom("https://facebook.com/profile.php?id=100012345"))
    }

    @Test
    fun messengerIsBuiltFromFacebook() {
        assertEquals("https://m.me/jones388", OmnioSocials.messengerFromFacebook("https://www.facebook.com/jones388"))
        assertNull(OmnioSocials.messengerFromFacebook("https://www.facebook.com/share/1CDsYhRBec/"))
    }

    @Test
    fun displayHandles() {
        assertEquals("@jen_x", network("instagram").displayHandle("https://instagram.com/jen_x"))
        assertEquals("@jen", network("tiktok").displayHandle("https://tiktok.com/@jen"))
        assertEquals("", network("discord").displayHandle("https://discord.com/users/185102032955244556"))
    }

    @Test
    fun discordNeedsAnId() {
        assertEquals("https://discord.com/users/185102032955244556", OmnioSocials.discordUrl("185102032955244556"))
        assertEquals("https://discord.com/users/185102032955244556", OmnioSocials.discordUrl("https://discord.com/users/185102032955244556"))
        assertNull(OmnioSocials.discordUrl("leejones"))
        assertNull(OmnioSocials.discordUrl("123"))
    }

    @Test
    fun buildsLinksFromWhatPeopleType() {
        assertEquals("https://instagram.com/jen_x", OmnioSocials.buildUrl(network("instagram"), "@jen_x"))
        assertEquals("https://snapchat.com/add/jen", OmnioSocials.buildUrl(network("snapchat"), "jen"))
        assertEquals("https://instagram.com/jen_x", OmnioSocials.buildUrl(network("instagram"), "https://instagram.com/jen_x"))
        assertNull(OmnioSocials.buildUrl(network("instagram"), "  "))
    }

    @Test
    fun findsProfileAddressesInText() {
        assertEquals("https://www.facebook.com/jones388", OmnioSocials.profileUrlIn("Lee Jones's profile link https://www.facebook.com/jones388 Copy link"))
        assertEquals("https://facebook.com/jones388", OmnioSocials.profileUrlIn("facebook.com/jones388"))
        assertNull(OmnioSocials.profileUrlIn("https://www.facebook.com/share/1CDsYhRBec/"))
        assertEquals("https://x.com/a", OmnioSocials.firstUrl("look https://x.com/a, thanks"))
    }
}
