package eu.woshicado.circletoshare.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrPayloadTest {

    private fun assertOpensAsItself(payload: String) =
        assertEquals(payload.trim(), qrOpenUri(payload))

    private fun assertNotOpenable(payload: String) =
        assertNull("should not be openable: $payload", qrOpenUri(payload))

    @Test
    fun webAndAppLinksOpenAsTheyAre() {
        assertOpensAsItself("https://example.com/hello")
        assertOpensAsItself("http://example.com")
        assertOpensAsItself("  https://example.com/padded \n")
        assertOpensAsItself("HTTPS://EXAMPLE.COM/UPPER")
        assertOpensAsItself("market://details?id=com.example")
        assertOpensAsItself("spotify:track:4uLU6hMCjMI75M1A2tKUQC")
        assertOpensAsItself("tel:+15550100")
        assertOpensAsItself("mailto:someone@example.com")
        assertOpensAsItself("geo:48.8584,2.2945")
        assertOpensAsItself("sms:+15550100")
        assertOpensAsItself("smsto:+15550100")
    }

    @Test
    fun linksWithRawSpacesStayOpenable() {
        assertOpensAsItself("upi://pay?pa=shop@bank&pn=Ram Kumar&am=10")
        assertOpensAsItself("bitcoin:1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa?label=My Shop")
        assertOpensAsItself("otpauth://totp/ACME Co:alice?secret=JBSWY3DPEHPK3PXP")
        assertOpensAsItself("tel:+1 555 0100")
        assertOpensAsItself("mailto:someone@example.com?subject=Hello there")
        assertOpensAsItself("sms:+15550100?body=on my way")
        assertOpensAsItself("geo:48.8584,2.2945?q=Eiffel Tower")
    }

    @Test
    fun qrTextMessageFormatBecomesAnSmsUri() {
        assertEquals("sms:+15550100?body=on%20my%20way", qrOpenUri("SMSTO:+15550100:on my way"))
        assertEquals("sms:+15550100?body=on%20my%20way", qrOpenUri("smsto:+15550100:on my way"))
        assertEquals("sms:+15550100?body=hi", qrOpenUri("MMSTO:+15550100:hi"))
        assertEquals(
            "sms:+15550100?body=a%26b%3Dc%20%3F%20100%25%0Aline%202",
            qrOpenUri("SMSTO:+15550100:a&b=c ? 100%\nline 2")
        )
        assertEquals("sms:+15550100?body=see%20http%3A%2F%2Fx.y", qrOpenUri("SMSTO:+15550100:see http://x.y"))
        assertEquals("sms:+15550100", qrOpenUri("SMSTO:+15550100:"))
        assertNotOpenable("SMSTO::no number")
    }

    @Test
    fun wrappedLinksOpenAsTheLinkInside() {
        assertEquals("https://example.com/a", qrOpenUri("URL:https://example.com/a"))
        assertEquals("https://example.com/a", qrOpenUri("url:https://example.com/a"))
        assertEquals("https://example.com/a", qrOpenUri("URI:https://example.com/a"))
        assertEquals("HTTPS://EXAMPLE.COM/A", qrOpenUri("URL:HTTPS://EXAMPLE.COM/A"))
        assertEquals("sms:+15550100?body=hi", qrOpenUri("URL:SMSTO:+15550100:hi"))
        // Nothing openable inside: Copy only.
        assertNotOpenable("URL:www.example.com")
        assertNotOpenable("URL:file:///sdcard/x")
        assertNotOpenable("URL:")
        assertNotOpenable("URL:URL:")
    }

    @Test
    fun titledLinksOpenAsTheLinkAfterTheTitle() {
        assertEquals("http://example.com", qrOpenUri("URLTO:Example:http://example.com"))
        assertEquals("http://example.com", qrOpenUri("urlto:Example:http://example.com"))
        assertEquals("https://example.com/a?b=c", qrOpenUri("URLTO:My Site:https://example.com/a?b=c"))
        // Empty title.
        assertEquals("http://example.com", qrOpenUri("URLTO::http://example.com"))
        // Colons in the title.
        assertEquals("https://example.com", qrOpenUri("URLTO:Docs: Home:https://example.com"))
        assertEquals("https://x.example/y", qrOpenUri("URLTO:Tip: 5 ways:https://x.example/y"))
        assertEquals("https://example.com/a:b", qrOpenUri("URLTO:A:B:C:https://example.com/a:b"))
        // No "://" to go by: the title ends at the first colon.
        assertEquals("tel:+15550100", qrOpenUri("URLTO:Call me:tel:+15550100"))
        assertEquals("mailto:a@example.com", qrOpenUri("URLTO:Write:mailto:a@example.com"))
        // Title left out altogether.
        assertEquals("http://example.com", qrOpenUri("URLTO:http://example.com"))
        assertEquals("https://example.com:8443/a", qrOpenUri("URLTO:https://example.com:8443/a"))
        // Nothing openable inside: Copy only.
        assertNotOpenable("URLTO:Example:www.example.com")
        assertNotOpenable("URLTO:example.com/path")
        assertNotOpenable("URLTO:Example:file:///sdcard/x")
        assertNotOpenable("URLTO:file:///sdcard/x")
        assertNotOpenable("URLTO:A: B:content://settings/secure/android_id")
        assertNotOpenable("URLTO:")
        assertNotOpenable("URLTO::")
    }

    @Test
    fun plainTextIsNotOpenable() {
        assertNotOpenable("")
        assertNotOpenable("just some plain text")
        assertNotOpenable("12345")
        assertNotOpenable("Note: buy milk")
        assertNotOpenable("Title:\tsomething")
        assertNotOpenable("https:")
        assertNotOpenable("https: ")
        assertNotOpenable(":nothing")
        assertNotOpenable("1abc:def")
        assertNotOpenable("first:line\nsecond:line")
        assertNotOpenable("https://example.com/a\r\nhttps://example.com/b")
    }

    @Test
    fun structuredQrFormatsAreNotOpenable() {
        assertNotOpenable("WIFI:T:WPA;S:TestNet;P:hunter22;;")
        assertNotOpenable("wifi:T:WPA;S:TestNet;P:hunter22;;")
        assertNotOpenable("MECARD:N:Doe,John;TEL:5550100;;")
        assertNotOpenable("BIZCARD:N:John;X:Doe;T:Dev;;")
        assertNotOpenable("MEBKM:TITLE:Example;URL:https://example.com;;")
        assertNotOpenable("MATMSG:TO:a@example.com;SUB:Hi;BODY:Yo;;")
        assertNotOpenable("BEGIN:VCARD\nVERSION:3.0\nFN:John Doe\nEND:VCARD")
        assertNotOpenable("BEGIN:VEVENT\nSUMMARY:Party\nEND:VEVENT")
    }

    @Test
    fun schemesThatMustNeverBeLaunchedAreNotOpenable() {
        assertNotOpenable("file:///sdcard/Download/secret.txt")
        assertNotOpenable("FILE:///sdcard/Download/secret.txt")
        assertNotOpenable("content://settings/secure/android_id")
        assertNotOpenable("javascript:alert(1)")
        assertNotOpenable("data:text/html,<h1>hi</h1>")
    }
}
