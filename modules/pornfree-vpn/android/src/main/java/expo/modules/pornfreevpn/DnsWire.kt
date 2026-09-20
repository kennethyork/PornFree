package expo.modules.pornfreevpn

/**
 * Minimal DNS message handling: read the question, and synthesise a small reply for blocked names.
 */
object DnsWire {
  const val TYPE_A = 1
  const val TYPE_AAAA = 28
  const val TYPE_OPT = 41
  const val CLASS_IN = 1

  private const val RCODE_OK = 0
  private const val RCODE_NXDOMAIN = 3

  class Question(val name: String, val type: Int, val questionEnd: Int)

  fun idOf(packet: ByteArray): Int = IpWire.u16(packet, 0)

  fun setQueryId(packet: ByteArray, id: Int) = IpWire.put16(packet, 0, id)

  /**
   * Reads the single question of a DNS query. Returns null whenever the message is anything other
   * than a plain, single-question query - callers then forward it untouched, so unusual traffic
   * keeps working instead of breaking.
   */
  fun parseQuery(buf: ByteArray, offset: Int, length: Int): Question? {
    if (length < 12) return null
    if (IpWire.u16(buf, offset + 4) != 1) return null
    if ((IpWire.u16(buf, offset + 2) and 0x8000) != 0) return null
    val end = offset + length
    var p = offset + 12
    val name = StringBuilder(48)
    while (true) {
      if (p >= end) return null
      val labelLength = buf[p].toInt() and 0xFF
      when {
        labelLength == 0 -> {
          p += 1
          break
        }
        (labelLength and 0xC0) == 0xC0 -> return null // compression in a question: unexpected
        labelLength > 63 -> return null
        else -> {
          if (p + 1 + labelLength > end) return null
          if (name.isNotEmpty()) name.append('.')
          for (i in 0 until labelLength) {
            name.append((buf[p + 1 + i].toInt() and 0xFF).toChar())
          }
          p += 1 + labelLength
        }
      }
      if (name.length > 255) return null
    }
    if (p + 4 > end) return null
    val type = IpWire.u16(buf, p)
    if (IpWire.u16(buf, p + 2) != CLASS_IN) return null
    return Question(name.toString(), type, p + 4)
  }

  /**
   * Builds the reply for a blocked name. [sinkhole] answers A/AAAA with 0.0.0.0 / ::, otherwise the
   * reply is an empty NOERROR (NODATA), which browsers and apps treat as "name exists, no address".
   */
  fun buildBlockedResponse(
    query: ByteArray,
    queryOffset: Int,
    questionEnd: Int,
    queryType: Int,
    sinkhole: Boolean,
    nxdomain: Boolean
  ): ByteArray {
    val questionLength = questionEnd - queryOffset
    val hasAnswer = sinkhole && (queryType == TYPE_A || queryType == TYPE_AAAA)
    val answerLength = if (queryType == TYPE_AAAA) 16 else 4
    // 12 bytes of fixed record fields (name pointer, type, class, ttl, rdlength) plus the address.
    val out = ByteArray(questionLength + if (hasAnswer) 12 + answerLength else 0)
    System.arraycopy(query, queryOffset, out, 0, questionLength)

    out[2] = (0x80 or (query[queryOffset + 2].toInt() and 0x01)).toByte() // QR=1, copy RD
    out[3] = ((if (nxdomain) RCODE_NXDOMAIN else RCODE_OK) or 0x80).toByte() // RA=1
    IpWire.put16(out, 4, 1) // QDCOUNT
    IpWire.put16(out, 6, if (hasAnswer) 1 else 0) // ANCOUNT
    IpWire.put16(out, 8, 0) // NSCOUNT
    IpWire.put16(out, 10, 0) // ARCOUNT

    if (hasAnswer) {
      var p = questionLength
      out[p++] = 0xC0.toByte() // name: pointer to the question name
      out[p++] = 0x0C
      IpWire.put16(out, p, queryType)
      p += 2
      IpWire.put16(out, p, CLASS_IN)
      p += 2
      IpWire.put16(out, p, 0)
      p += 2
      IpWire.put16(out, p, 30) // TTL
      p += 2
      if (queryType == TYPE_A) {
        IpWire.put16(out, p, 4)
        p += 2
        out[p++] = 0
        out[p++] = 0
        out[p++] = 0
        out[p++] = 0
      } else {
        IpWire.put16(out, p, 16)
        p += 2
        for (i in 0 until 16) out[p++] = 0
      }
    }
    return out
  }

  /** True when a response is a positive answer that is safe to cache. */
  fun isCacheable(response: ByteArray, length: Int): Boolean {
    if (length < 12) return false
    if ((IpWire.u16(response, 2) and 0x000F) != 0) return false
    return IpWire.u16(response, 6) > 0
  }

  /**
   * Caps the EDNS0 UDP payload size a client asked for, so responses comfortably fit in the tunnel
   * MTU. Without this, large answers would be truncated by the network stack.
   */
  fun clampUdpPayloadSize(buf: ByteArray, offset: Int, length: Int, maxSize: Int) {
    if (length < 12) return
    val end = offset + length
    val questionCount = IpWire.u16(buf, offset + 4)
    val answerCount = IpWire.u16(buf, offset + 6)
    val authorityCount = IpWire.u16(buf, offset + 8)
    val additionalCount = IpWire.u16(buf, offset + 10)
    if (additionalCount == 0) return

    var p = offset + 12
    for (i in 0 until questionCount) {
      p = skipName(buf, p, end) ?: return
      p += 4
      if (p > end) return
    }
    for (i in 0 until answerCount + authorityCount) {
      p = skipRecord(buf, p, end) ?: return
    }
    for (i in 0 until additionalCount) {
      val nameEnd = skipName(buf, p, end) ?: return
      if (nameEnd + 10 > end) return
      if (IpWire.u16(buf, nameEnd) == TYPE_OPT) {
        val classOffset = nameEnd + 2
        if (IpWire.u16(buf, classOffset) > maxSize) IpWire.put16(buf, classOffset, maxSize)
        return
      }
      p = skipRecord(buf, nameEnd, end) ?: return
    }
  }

  private fun skipName(buf: ByteArray, start: Int, end: Int): Int? {
    var p = start
    while (true) {
      if (p >= end) return null
      val labelLength = buf[p].toInt() and 0xFF
      if (labelLength == 0) return p + 1
      if ((labelLength and 0xC0) == 0xC0) return if (p + 1 < end) p + 2 else null
      if (labelLength > 63) return null
      p += 1 + labelLength
    }
  }

  private fun skipRecord(buf: ByteArray, start: Int, end: Int): Int? {
    val nameEnd = skipName(buf, start, end) ?: return null
    if (nameEnd + 10 > end) return null
    val next = nameEnd + 10 + IpWire.u16(buf, nameEnd + 8)
    return if (next > end) null else next
  }
}
