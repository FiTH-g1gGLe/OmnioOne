package com.omnioone.app.messaging

import org.junit.Assert.assertEquals
import org.junit.Test

class OmnioCodesTest {

    private fun code(text: String) = OmnioCodes.find(text)?.code

    @Test
    fun findsCommonVerificationCodes() {
        assertEquals("482913", code("Your WhatsApp code: 482-913. Don't share this code with others"))
        assertEquals("482913", code("G-482913 is your Google verification code."))
        assertEquals("4829", code("Your Amazon OTP is 4829. Do not share it."))
        assertEquals("739201", code("Use 739201 to verify your Monzo account"))
        assertEquals("553812", code("Your PayPal security code is: 553 812"))
        assertEquals("9183", code("Microsoft account security code: 9183"))
        assertEquals("7215", code("Your Uber code: 7215. Never share this code."))
    }

    @Test
    fun skipsDatesPhoneNumbersAndMoney() {
        assertEquals("663019", code("Your login code expires on 12 Nov 2026. Code: 663019"))
        assertEquals("112233", code("Call 0800123456 if you didn't request this code 112233"))
        assertEquals(null, code("Your bill of £1234 is due. Login to pay"))
    }

    @Test
    fun ignoresOrdinaryTexts() {
        assertEquals(null, code("Hi mate, see you at 7?"))
        assertEquals(null, code("Your parcel 12345678 will arrive tomorrow"))
    }
}
