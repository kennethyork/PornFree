package expo.modules.pornfreevpn

/**
 * Just enough IPv4/IPv6/UDP/ICMP wire format to answer DNS queries that the system routes into
 * our tunnel, and to fail fast on everything else.
 */
object IpWire {
  const val PROTO_ICMP = 1
  const val PROTO_TCP = 6
  const val PROTO_UDP = 17
  const val PROTO_ICMPV6 = 58

  class Header(
    val version: Int,
    val protocol: Int,
    val source: ByteArray,
    val destination: ByteArray,
    val l4Offset: Int,
    val ipHeaderLength: Int
  )

  fun u16(b: ByteArray, i: Int): Int = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

  fun put16(b: ByteArray, i: Int, value: Int) {
    b[i] = ((value ushr 8) and 0xFF).toByte()
    b[i + 1] = (value and 0xFF).toByte()
  }

  /** Parses the IP header. Returns null for anything we cannot safely reason about. */
  fun parse(buf: ByteArray, length: Int): Header? {
    if (length < 20) return null
    return when (buf[0].toInt() and 0xF0) {
      0x40 -> {
        val ihl = (buf[0].toInt() and 0x0F) * 4
        if (ihl < 20 || ihl > length) return null
        val total = u16(buf, 2)
        if (total < ihl || total > length) return null
        Header(4, buf[9].toInt() and 0xFF, buf.copyOfRange(12, 16), buf.copyOfRange(16, 20), ihl, ihl)
      }
      0x60 -> {
        if (length < 40) return null
        // Extension headers are not handled: such packets are dropped rather than mishandled.
        Header(6, buf[6].toInt() and 0xFF, buf.copyOfRange(8, 24), buf.copyOfRange(24, 40), 40, 40)
      }
      else -> null
    }
  }

  /** Builds a UDP datagram (as a full IP packet) that answers the packet described by [h]. */
  fun buildUdpReply(h: Header, sourcePort: Int, destinationPort: Int, udpPayload: ByteArray): ByteArray {
    val udp = ByteArray(8 + udpPayload.size)
    put16(udp, 0, sourcePort)
    put16(udp, 2, destinationPort)
    put16(udp, 4, udp.size)
    put16(udp, 6, 0)
    System.arraycopy(udpPayload, 0, udp, 8, udpPayload.size)
    var checksum = udpChecksum(h.version, h.destination, h.source, udp)
    if (checksum == 0) checksum = 0xFFFF
    put16(udp, 6, checksum)
    return wrap(h, PROTO_UDP, udp)
  }

  /**
   * An ICMP "destination unreachable, port unreachable" for the packet in [buf]. This is what makes
   * DNS-over-HTTPS and DNS-over-TLS requests fail instantly instead of hanging, so clients drop back
   * to plain DNS that we can filter.
   */
  fun buildPortUnreachable(buf: ByteArray, h: Header, length: Int): ByteArray? {
    if (h.version != 4) return null
    val echoed = minOf(h.ipHeaderLength + 8, length)
    val icmp = ByteArray(8 + echoed)
    icmp[0] = 3 // destination unreachable
    icmp[1] = 3 // port unreachable
    System.arraycopy(buf, 0, icmp, 8, echoed)
    put16(icmp, 2, fold16(checksumSum(icmp, 0, icmp.size, 0L)))
    return wrap(h, PROTO_ICMP, icmp)
  }

  private fun wrap(h: Header, protocol: Int, l4: ByteArray): ByteArray {
    return if (h.version == 4) {
      val out = ByteArray(20 + l4.size)
      out[0] = 0x45
      out[1] = 0
      put16(out, 2, out.size)
      put16(out, 4, 0)
      put16(out, 6, 0x4000) // don't fragment
      out[8] = 64
      out[9] = protocol.toByte()
      System.arraycopy(h.destination, 0, out, 12, 4)
      System.arraycopy(h.source, 0, out, 16, 4)
      put16(out, 10, fold16(checksumSum(out, 0, 20, 0L)))
      System.arraycopy(l4, 0, out, 20, l4.size)
      out
    } else {
      val out = ByteArray(40 + l4.size)
      out[0] = 0x60
      put16(out, 4, l4.size)
      out[6] = protocol.toByte()
      out[7] = 64
      System.arraycopy(h.destination, 0, out, 8, 16)
      System.arraycopy(h.source, 0, out, 24, 16)
      System.arraycopy(l4, 0, out, 40, l4.size)
      out
    }
  }

  private fun udpChecksum(version: Int, source: ByteArray, destination: ByteArray, udp: ByteArray): Int {
    var sum = checksumSum(source, 0, source.size, 0L)
    sum = checksumSum(destination, 0, destination.size, sum)
    if (version == 4) {
      sum += PROTO_UDP.toLong()
      sum += udp.size.toLong()
    } else {
      sum += ((udp.size ushr 16) and 0xFFFF).toLong()
      sum += (udp.size and 0xFFFF).toLong()
      sum += PROTO_UDP.toLong()
    }
    return fold16(checksumSum(udp, 0, udp.size, sum))
  }

  private fun checksumSum(data: ByteArray, offset: Int, length: Int, initial: Long): Long {
    var sum = initial
    var i = offset
    val end = offset + length
    while (i + 1 < end) {
      sum += (((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)).toLong()
      i += 2
    }
    if (i < end) sum += ((data[i].toInt() and 0xFF) shl 8).toLong()
    return sum
  }

  private fun fold16(sum: Long): Int {
    var s = sum
    while ((s shr 16) != 0L) s = (s and 0xFFFF) + (s shr 16)
    return (s.inv() and 0xFFFF).toInt()
  }
}
