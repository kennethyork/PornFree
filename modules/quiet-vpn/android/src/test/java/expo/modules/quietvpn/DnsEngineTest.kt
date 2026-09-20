package expo.modules.quietvpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** A DNS query for `name`, type A, with the recursion-desired bit set. */
private fun dnsQuery(name: String, type: Int = DnsWire.TYPE_A, id: Int = 0x1234): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf((id shr 8).toByte(), id.toByte()))
    out.write(byteArrayOf(0x01, 0x00)) // RD
    out.write(byteArrayOf(0x00, 0x01)) // QDCOUNT
    out.write(byteArrayOf(0, 0, 0, 0, 0, 0)) // AN, NS, AR
    for (label in name.split('.')) {
        out.write(label.length)
        out.write(label.toByteArray(Charsets.US_ASCII))
    }
    out.write(0)
    out.write(byteArrayOf(0x00, type.toByte(), 0x00, DnsWire.CLASS_IN.toByte()))
    return out.toByteArray()
}

/** Wraps a DNS payload in an IPv4 + UDP packet. */
private fun ipv4Udp(
    payload: ByteArray,
    sourceIp: ByteArray = byteArrayOf(10, 0, 0, 5),
    destinationIp: ByteArray = byteArrayOf(1, 1, 1, 1),
    sourcePort: Int = 40000,
    destinationPort: Int = 53
): ByteArray {
    val header = ByteArray(20)
    header[0] = 0x45
    IpWire.put16(header, 2, 20 + 8 + payload.size)
    header[8] = 64
    header[9] = IpWire.PROTO_UDP.toByte()
    System.arraycopy(sourceIp, 0, header, 12, 4)
    System.arraycopy(destinationIp, 0, header, 16, 4)
    val packet = header + ByteArray(8) + payload
    IpWire.put16(packet, 20, sourcePort)
    IpWire.put16(packet, 22, destinationPort)
    IpWire.put16(packet, 24, 8 + payload.size)
    return packet
}

private fun checksum(data: ByteArray): Int {
    var sum = 0L
    var i = 0
    while (i + 1 < data.size) {
        sum += (((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)).toLong()
        i += 2
    }
    if (i < data.size) sum += ((data[i].toInt() and 0xFF) shl 8).toLong()
    while ((sum shr 16) != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
    return (sum.inv() and 0xFFFF).toInt()
}

class DnsWireTest {
    @Test
    fun `reads the question of a query`() {
        val query = dnsQuery("www.example.com")
        val parsed = DnsWire.parseQuery(query, 0, query.size)
        assertNotNull(parsed)
        assertEquals("www.example.com", parsed!!.name)
        assertEquals(DnsWire.TYPE_A, parsed.type)
        assertEquals(query.size, parsed.questionEnd)
    }

    @Test
    fun `keeps unusual queries away from the matcher`() {
        val response = dnsQuery("example.com").copyOf()
        response[2] = (response[2].toInt() or 0x80).toByte() // QR bit: this is a response
        assertNull(DnsWire.parseQuery(response, 0, response.size))

        val truncated = dnsQuery("example.com").copyOf(20)
        assertNull(DnsWire.parseQuery(truncated, 0, truncated.size))
    }

    @Test
    fun `nodata answer has no records and keeps the question`() {
        val query = dnsQuery("porn.example")
        val response = DnsWire.buildBlockedResponse(query, 0, query.size, DnsWire.TYPE_A, false, false)
        assertEquals(query.size, response.size)
        assertEquals(0x81, response[2].toInt() and 0xFF) // QR + copied RD
        assertEquals(0x80, response[3].toInt() and 0xFF) // RA, rcode 0
        assertEquals(1, IpWire.u16(response, 4)) // QDCOUNT
        assertEquals(0, IpWire.u16(response, 6)) // ANCOUNT
        assertTrue(query.copyOfRange(12, query.size).contentEquals(response.copyOfRange(12, response.size)))
    }

    @Test
    fun `sinkhole answer points at 0_0_0_0`() {
        val query = dnsQuery("porn.example")
        val response = DnsWire.buildBlockedResponse(query, 0, query.size, DnsWire.TYPE_A, true, false)
        assertEquals(query.size + 16, response.size)
        assertEquals(1, IpWire.u16(response, 6)) // ANCOUNT
        assertEquals(DnsWire.TYPE_A, IpWire.u16(response, query.size + 2))
        assertEquals(4, IpWire.u16(response, query.size + 10)) // RDLENGTH
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), response.copyOfRange(response.size - 4, response.size))
    }

    @Test
    fun `sinkhole answer for aaaa is all zeroes`() {
        val query = dnsQuery("porn.example", DnsWire.TYPE_AAAA)
        val response = DnsWire.buildBlockedResponse(query, 0, query.size, DnsWire.TYPE_AAAA, true, false)
        assertEquals(16, IpWire.u16(response, query.size + 10))
        assertArrayEquals(ByteArray(16), response.copyOfRange(response.size - 16, response.size))
    }

    @Test
    fun `nxdomain answer carries the rcode`() {
        val query = dnsQuery("porn.example")
        val response = DnsWire.buildBlockedResponse(query, 0, query.size, DnsWire.TYPE_A, false, true)
        assertEquals(3, response[3].toInt() and 0x0F)
    }

    @Test
    fun `only positive answers are cached`() {
        val query = dnsQuery("example.com")
        val positive = query.copyOf()
        positive[2] = 0
        IpWire.put16(positive, 6, 1)
        assertTrue(DnsWire.isCacheable(positive, positive.size))

        val nothing = query.copyOf()
        IpWire.put16(nothing, 6, 0)
        assertFalse(DnsWire.isCacheable(nothing, nothing.size))
    }

    @Test
    fun `edns payload size is clamped to the tunnel mtu`() {
        val query = dnsQuery("example.com")
        val withOpt = query + byteArrayOf(0x00, 0x00, 0x29, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        IpWire.put16(withOpt, 10, 1) // ARCOUNT
        assertEquals(4096, IpWire.u16(withOpt, query.size + 3))

        DnsWire.clampUdpPayloadSize(withOpt, 0, withOpt.size, 1232)
        assertEquals(DnsWire.TYPE_OPT, IpWire.u16(withOpt, query.size + 1))
        assertEquals(1232, IpWire.u16(withOpt, query.size + 3))
    }
}

class IpWireTest {
    @Test
    fun `parses an ipv4 udp packet`() {
        val payload = dnsQuery("example.com")
        val packet = ipv4Udp(payload)
        val header = IpWire.parse(packet, packet.size)
        assertNotNull(header)
        assertEquals(4, header!!.version)
        assertEquals(IpWire.PROTO_UDP, header.protocol)
        assertEquals(20, header.l4Offset)
        assertArrayEquals(byteArrayOf(10, 0, 0, 5), header.source)
        assertArrayEquals(byteArrayOf(1, 1, 1, 1), header.destination)
        assertEquals(53, IpWire.u16(packet, header.l4Offset + 2))
    }

    @Test
    fun `rejects a packet that claims to be longer than it is`() {
        val packet = ipv4Udp(dnsQuery("example.com"))
        IpWire.put16(packet, 2, packet.size + 400)
        assertNull(IpWire.parse(packet, packet.size))
    }

    @Test
    fun `reply swaps addresses and carries a valid udp checksum`() {
        val query = dnsQuery("example.com")
        val request = ipv4Udp(query)
        val header = IpWire.parse(request, request.size)!!
        val answer = dnsQuery("example.com").copyOf()

        val reply = IpWire.buildUdpReply(header, 53, 40000, answer)
        assertEquals(IpWire.PROTO_UDP, reply[9].toInt() and 0xFF)
        // Source is now the resolver, destination is us.
        assertArrayEquals(byteArrayOf(1, 1, 1, 1), reply.copyOfRange(12, 16))
        assertArrayEquals(byteArrayOf(10, 0, 0, 5), reply.copyOfRange(16, 20))
        assertEquals(20 + 8 + answer.size, IpWire.u16(reply, 2))

        // The IP header checksum must verify over the header, checksum field included.
        assertEquals(0, checksum(reply.copyOfRange(0, 20)))

        // And so must the UDP checksum over the pseudo header.
        val pseudo = byteArrayOf(1, 1, 1, 1, 10, 0, 0, 5, 0, IpWire.PROTO_UDP.toByte(), 0, 0)
        val udpLength = IpWire.u16(reply, 24)
        IpWire.put16(pseudo, 10, udpLength)
        val segment = reply.copyOfRange(20, 20 + udpLength)
        assertEquals(0, checksum(pseudo + segment))
    }

    @Test
    fun `icmp port unreachable echoes the offending packet`() {
        val query = dnsQuery("example.com")
        val request = ipv4Udp(query, destinationPort = 443)
        val header = IpWire.parse(request, request.size)!!
        val icmp = IpWire.buildPortUnreachable(request, header, request.size)
        assertNotNull(icmp)
        assertEquals(IpWire.PROTO_ICMP, icmp!![9].toInt() and 0xFF)
        assertEquals(3, icmp[20].toInt() and 0xFF) // destination unreachable
        assertEquals(3, icmp[21].toInt() and 0xFF) // port unreachable
        assertTrue(request.copyOfRange(0, 28).contentEquals(icmp.copyOfRange(28, 56)))
    }
}

class DomainRulesTest {
    private fun load(vararg lines: String): DomainRules {
        val file = File.createTempFile("list", ".txt")
        file.writeText(lines.joinToString("\n"))
        file.deleteOnExit()
        return DomainRulesLoader.load(listOf(file.absolutePath), emptyList(), emptyList())
    }

    @Test
    fun `normalises every list format it claims to support`() {
        assertEquals(listOf("example.com"), DomainRulesLoader.parseLine("example.com"))
        assertEquals(listOf("example.com"), DomainRulesLoader.parseLine("0.0.0.0 example.com"))
        assertEquals(listOf("example.com"), DomainRulesLoader.parseLine("127.0.0.1 example.com # comment"))
        assertEquals(listOf("example.com"), DomainRulesLoader.parseLine("||example.com^"))
        assertEquals(listOf("example.com"), DomainRulesLoader.parseLine("*.example.com"))
        assertEquals(listOf("a.com", "b.com"), DomainRulesLoader.parseLine("0.0.0.0 a.com b.com"))
        assertEquals(listOf("example.com"), DomainRulesLoader.parseLine("  EXAMPLE.COM  "))

        assertTrue(DomainRulesLoader.parseLine("# comment").isEmpty())
        assertTrue(DomainRulesLoader.parseLine("").isEmpty())
        assertTrue(DomainRulesLoader.parseLine("0.0.0.0").isEmpty())
        assertTrue(DomainRulesLoader.parseLine("localhost").isEmpty())
        assertTrue(DomainRulesLoader.parseLine("127.0.0.1").isEmpty())
        assertTrue(DomainRulesLoader.parseLine("@@||example.com^").isEmpty())
        assertEquals(listOf("example.com"), DomainRulesLoader.parseLine("address=/example.com/0.0.0.0"))
        assertEquals(listOf("example.com"), DomainRulesLoader.parseLine("https://example.com/path?q=1"))
    }

    @Test
    fun `blocks a domain and everything under it`() {
        val rules = load("0.0.0.0 porn.example", "||tubes.example^")
        assertTrue(rules.isBlocked("porn.example"))
        assertTrue(rules.isBlocked("www.porn.example"))
        assertTrue(rules.isBlocked("a.b.c.tubes.example"))
        assertFalse(rules.isBlocked("example"))
        assertFalse(rules.isBlocked("notporn.example"))
        assertFalse(rules.isBlocked("porn.example.evil.net"))
    }

    @Test
    fun `trailing dots and case do not matter`() {
        val rules = load("porn.example")
        assertTrue(rules.isBlocked("WWW.Porn.Example."))
        assertTrue(rules.isBlocked("porn.example."))
    }

    @Test
    fun `the allowlist wins over the blocklist`() {
        val file = File.createTempFile("list", ".txt")
        file.writeText("porn.example\n")
        file.deleteOnExit()
        val rules = DomainRulesLoader.load(listOf(file.absolutePath), emptyList(), listOf("safe.porn.example"))
        assertTrue(rules.isBlocked("porn.example"))
        assertFalse(rules.isBlocked("safe.porn.example"))
        assertTrue(rules.isBlocked("other.porn.example"))
    }

    @Test
    fun `domains from the user are normalised too`() {
        assertEquals("example.com", DomainRulesLoader.normalize("https://example.com/path"))
        assertEquals("example.com", DomainRulesLoader.normalize("EXAMPLE.com."))
        assertNull(DomainRulesLoader.normalize("not a domain"))
    }

    @Test
    fun `a downloaded list is rewritten as plain deduplicated domains`() {
        val source = """
            # Title: test
            0.0.0.0 adult.example
            0.0.0.0 adult.example
            127.0.0.1 localhost
            ||tubes.example^
            something else
        """.trimIndent()
        val output = ByteArrayOutputStream()
        val count = DomainRulesLoader.normalizeInto(ByteArrayInputStream(source.toByteArray()), output)
        assertEquals(2, count)
        assertEquals("adult.example\ntubes.example\n", output.toString("UTF-8"))
    }

    @Test
    fun `an unreadable file does not stop protection`() {
        val rules = DomainRulesLoader.load(listOf("/nope/missing.txt"), listOf("gone.example"), emptyList())
        assertTrue(rules.isBlocked("gone.example"))
        assertEquals(1, rules.blockedCount)
    }
}
