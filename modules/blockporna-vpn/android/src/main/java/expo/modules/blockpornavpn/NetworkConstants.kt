package expo.modules.blockpornavpn

/**
 * Addresses that BlockPorna pulls into its tunnel.
 *
 * BlockPorna only ever handles plain DNS (UDP/53). Everything else that ends up in the
 * tunnel is answered with an ICMP "port unreachable", which is exactly what we want for
 * DNS-over-HTTPS / DNS-over-TLS endpoints: those connections fail immediately and the client
 * falls back to the plain DNS they were already sending, which we do filter.
 */
object NetworkConstants {
  /** Virtual addresses of the tunnel interface. */
  const val TUN_ADDRESS_V4 = "10.111.222.1"
  const val TUN_ADDRESS_V6 = "fd00:1:fd00:1:fd00:1:fd00:1"
  const val MTU = 1500

  /** Largest DNS payload we will hand back to a client over UDP. */
  const val MAX_DNS_RESPONSE = MTU - 40 - 8

  data class Route(val address: String, val prefix: Int)

  /**
   * Well known public recursive resolvers. Apps and devices that hard-code a DNS server
   * (instead of using the resolver we advertise) still have their lookups pulled into the tunnel
   * because of these routes.
   */
  private val RESOLVERS_V4: List<Route> = listOf(
    // Google
    Route("8.8.8.8", 32), Route("8.8.4.4", 32),
    // Cloudflare (plain, malware, family)
    Route("1.1.1.1", 32), Route("1.0.0.1", 32),
    Route("1.1.1.2", 32), Route("1.0.0.2", 32),
    Route("1.1.1.3", 32), Route("1.0.0.3", 32),
    // Quad9
    Route("9.9.9.9", 32), Route("9.9.9.10", 32), Route("9.9.9.11", 32),
    Route("149.112.112.112", 32), Route("149.112.112.10", 32), Route("149.112.112.11", 32),
    // OpenDNS / Umbrella
    Route("208.67.222.222", 32), Route("208.67.220.220", 32),
    Route("208.67.222.123", 32), Route("208.67.220.123", 32),
    Route("208.67.222.222", 32),
    // AdGuard DNS (plain, family, kids)
    Route("94.140.14.0", 24), Route("94.140.15.0", 24),
    // CleanBrowsing (security, adult, family)
    Route("185.228.168.0", 24), Route("185.228.169.0", 24),
    // NextDNS anycast
    Route("45.90.28.0", 24), Route("45.90.30.0", 24),
    // Control D
    Route("76.76.2.0", 24), Route("76.76.10.0", 24),
    // Neustar / Vercara
    Route("156.154.70.1", 32), Route("156.154.71.1", 32), Route("156.154.70.2", 32), Route("156.154.71.2", 32),
    Route("64.6.64.6", 32), Route("64.6.65.6", 32),
    // Level3 / CenturyLink
    Route("4.2.2.1", 32), Route("4.2.2.2", 32), Route("4.2.2.3", 32), Route("4.2.2.4", 32),
    Route("4.2.2.5", 32), Route("4.2.2.6", 32),
    // DNS.WATCH, DNS.SB, DNS0.eu, Mullvad, SafeDNS, Comodo, FreeDNS
    Route("84.200.69.80", 32), Route("84.200.70.40", 32),
    Route("185.222.222.222", 32), Route("45.11.45.11", 32),
    Route("193.110.81.0", 24), Route("185.253.5.0", 24),
    Route("194.242.2.0", 24),
    Route("195.46.39.39", 32), Route("195.46.39.40", 32),
    Route("8.26.56.26", 32), Route("8.20.247.20", 32),
    Route("37.235.1.174", 32), Route("37.235.1.177", 32),
    // Regional resolvers frequently hard-coded by apps
    Route("223.5.5.5", 32), Route("223.6.6.6", 32), Route("119.29.29.29", 32),
    Route("114.114.114.114", 32), Route("114.114.115.115", 32),
    Route("180.76.76.76", 32),
    Route("168.95.1.1", 32), Route("168.95.192.1", 32),
    Route("101.101.101.101", 32), Route("101.102.103.104", 32),
    Route("193.0.14.129", 32), Route("193.0.14.130", 32),
    Route("77.88.8.1", 32), Route("77.88.8.8", 32), Route("77.88.8.7", 32), Route("77.88.8.3", 32),
    Route("146.112.41.0", 24)
  )

  private val RESOLVERS_V6: List<Route> = listOf(
    // Google
    Route("2001:4860:4860::8888", 128), Route("2001:4860:4860::8844", 128),
    // Cloudflare (plain, malware, family)
    Route("2606:4700:4700::1111", 128), Route("2606:4700:4700::1001", 128),
    Route("2606:4700:4700::1112", 128), Route("2606:4700:4700::1002", 128),
    Route("2606:4700:4700::1113", 128), Route("2606:4700:4700::1003", 128),
    // Quad9
    Route("2620:fe::fe", 128), Route("2620:fe::9", 128), Route("2620:fe::10", 128), Route("2620:fe::11", 128),
    Route("2620:fe::fe:10", 128), Route("2620:fe::fe:11", 128),
    // OpenDNS
    Route("2620:119:35::35", 128), Route("2620:119:53::53", 128),
    // AdGuard
    Route("2a10:50c0::ad1:ff", 128), Route("2a10:50c0::ad2:ff", 128),
    Route("2a10:50c0::1:ff", 128), Route("2a10:50c0::2:ff", 128),
    // CleanBrowsing
    Route("2a0d:2a00:1::", 128), Route("2a0d:2a00:2::", 128),
    Route("2a0d:2a00:1::2", 128), Route("2a0d:2a00:2::2", 128),
    // Mullvad, Neustar, DNS.SB
    Route("2a07:e340::2", 128), Route("2a07:e340::3", 128),
    Route("2620:74:1b::1:1", 128), Route("2620:74:1c::2:2", 128),
    Route("2a09::", 128), Route("2a09::1", 128)
  )

  /**
   * Addresses that serve encrypted DNS only. These are kept to individually verified hosts on
   * purpose: pulling a wide host range into a DNS-only tunnel would break unrelated traffic.
   *
   * Most encrypted-DNS bypass is stopped by things already in place: the routes above, which catch
   * resolvers that also serve DoH/DoT, and the bundled list of DoH/DoT hostnames.
   */
  private val DOH_ONLY_V4: List<Route> = listOf(
    // Cloudflare's dedicated 1.1.1.1 family addresses
    Route("162.159.36.1", 32), Route("162.159.46.1", 32),
    Route("162.159.36.12", 32), Route("162.159.46.12", 32),
    // Cloudflare resolver anycast outside the general-purpose CDN range
    Route("172.64.36.1", 32)
  )

  private val DOH_ONLY_V6: List<Route> = listOf(
    Route("2606:4700:4700::1111", 128)
  )

  fun routes(interceptEncryptedDns: Boolean, includeIpv6: Boolean): List<Route> {
    val out = ArrayList<Route>(RESOLVERS_V4.size + 32)
    out.addAll(RESOLVERS_V4)
    if (interceptEncryptedDns) out.addAll(DOH_ONLY_V4)
    if (includeIpv6) {
      out.addAll(RESOLVERS_V6)
      if (interceptEncryptedDns) out.addAll(DOH_ONLY_V6)
    }
    return out
  }
}
