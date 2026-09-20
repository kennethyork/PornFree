package expo.modules.pornfreevpn

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The filtering engine.
 *
 * The tunnel is deliberately narrow: only the addresses of public resolvers (and, optionally,
 * encrypted-DNS endpoints) are routed into it. Everything the system sends us is plain DNS on
 * UDP/53, which we inspect, and then either answer locally (blocked) or relay to a resolver the
 * user chose. Anything else that arrives is answered with "port unreachable" so that DNS-over-HTTPS
 * and DNS-over-TLS fail immediately and clients fall back to DNS we can filter.
 *
 * Because no general traffic enters the tunnel, no TCP/IP stack is needed and battery/throughput
 * impact stays negligible.
 */
class PornFreeVpnService : VpnService() {
  companion object {
    const val TAG = "PornFreeVpn"
    const val ACTION_START = "expo.modules.pornfreevpn.START"
    const val ACTION_STOP = "expo.modules.pornfreevpn.STOP"
    const val ACTION_RESTART = "expo.modules.pornfreevpn.RESTART"

    private const val CHANNEL_ID = "pornfree.protection"
    private const val NOTIFICATION_ID = 4711
    private const val CACHE_TTL_MS = 60_000L
    private const val MAX_CACHE_ENTRIES = 4096
    private const val READ_BUFFER = 32 * 1024
    private const val UPSTREAM_TIMEOUT_MS = 4000

    fun start(context: Context) {
      val intent = Intent(context, PornFreeVpnService::class.java).setAction(ACTION_START)
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          context.startForegroundService(intent)
        } else {
          context.startService(intent)
        }
      } catch (error: Exception) {
        Log.w(TAG, "Could not start the tunnel: ${error.message}")
      }
    }

    fun stop(context: Context) {
      try {
        context.stopService(Intent(context, PornFreeVpnService::class.java))
      } catch (error: Exception) {
        Log.w(TAG, "Could not stop the tunnel: ${error.message}")
      }
    }

    /** Re-reads the stored configuration and rebuilds the tunnel, keeping the notification. */
    fun restart(context: Context) {
      val intent = Intent(context, PornFreeVpnService::class.java).setAction(ACTION_RESTART)
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          context.startForegroundService(intent)
        } else {
          context.startService(intent)
        }
      } catch (error: Exception) {
        Log.w(TAG, "Could not restart the tunnel: ${error.message}")
      }
    }
  }

  private class CachedResponse(val bytes: ByteArray, val expiresAt: Long)

  @Volatile private var stopped = true
  private var config = VpnConfig()
  private var rules: DomainRules = DomainRules.EMPTY
  private var upstreams: List<InetAddress> = emptyList()
  private var tun: ParcelFileDescriptor? = null

  @Volatile private var tunOutput: FileOutputStream? = null

  private var tunnelThread: Thread? = null
  private var ticker: ScheduledExecutorService? = null
  private val workers: ExecutorService = Executors.newFixedThreadPool(6)
  private val writeLock = Any()
  private val cache = ConcurrentHashMap<String, CachedResponse>()
  private val v4Socket = ThreadLocal.withInitial { protectedSocket(false) }
  private val v6Socket = ThreadLocal.withInitial { protectedSocket(true) }
  private var persistedBlocked = 0L
  private var persistedAllowed = 0L

  override fun onCreate() {
    super.onCreate()
    // The tunnel outlives the UI, so it also keeps the launcher icon hidden between visits.
    LauncherVisibility.registerAutoHide(this)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        shutDownTunnel()
        stopSelf()
        return START_NOT_STICKY
      }
      ACTION_RESTART -> {
        if (!stopped) shutDownTunnel()
        start()
        return START_STICKY
      }
    }
    if (stopped) start() else updateNotification()
    return START_STICKY
  }

  override fun onRevoke() {
    Log.i(TAG, "VPN permission revoked by the system")
    shutDownTunnel()
    stopSelf()
    super.onRevoke()
  }

  override fun onDestroy() {
    shutDownTunnel()
    super.onDestroy()
  }

  /**
   * Stops the reader loop and waits for it to finish, so a restart can never leave two loops
   * competing for the same tunnel.
   */
  private fun shutDownTunnel() {
    stopped = true
    try {
      tun?.close()
    } catch (_: IOException) {
    }
    val thread = tunnelThread
    if (thread != null && thread !== Thread.currentThread()) {
      try {
        thread.join(2000)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
      }
    }
    tunnelThread = null
    cleanup()
  }

  private fun start() {
    stopped = false
    ensureChannel()
    try {
      startForeground(NOTIFICATION_ID, buildNotification())
    } catch (error: Exception) {
      Log.w(TAG, "startForeground failed: ${error.message}")
      stopped = true
      return
    }
    tunnelThread = Thread({ runTunnel() }, "pornfree-tunnel").apply {
      isDaemon = true
      start()
    }
  }

  private fun runTunnel() {
    try {
      val loaded = Store.loadConfig(this)
      config = loaded
      VpnRuntime.logDomains = loaded.logDomains
      rules = DomainRulesLoader.load(
        loaded.listIds.map { ListStore.fileFor(this, it).absolutePath },
        emptyList(),
        loaded.allowlist
      )
      upstreams = resolveUpstreams(loaded)

      val fd = establish(loaded)
      if (fd == null) {
        Log.w(TAG, "The system refused to establish the tunnel")
        stopped = true
        stopSelf()
        return
      }
      tun = fd
      tunOutput = FileOutputStream(fd.fileDescriptor)
      persistedBlocked = Store.todayBlocked(this)
      persistedAllowed = Store.todayAllowed(this)
      VpnRuntime.startSession(rules.blockedCount, persistedBlocked, persistedAllowed)
      Store.setShouldRun(this, true)
      updateNotification()
      startTicker()

      val input = FileInputStream(fd.fileDescriptor)
      val buffer = ByteArray(READ_BUFFER)
      while (!stopped) {
        val length = try {
          input.read(buffer)
        } catch (_: IOException) {
          break
        }
        if (length <= 0) continue
        val packet = buffer.copyOf(length)
        try {
          workers.execute { handlePacket(packet, length) }
        } catch (_: Exception) {
          // The pool is shutting down; the loop will exit on the next iteration.
        }
      }
    } catch (error: Exception) {
      Log.w(TAG, "Tunnel stopped unexpectedly: ${error.message}")
    } finally {
      cleanup()
    }
  }

  private fun resolveUpstreams(config: VpnConfig): List<InetAddress> {
    val addresses = ArrayList<InetAddress>(config.upstreams.size)
    val ipv4 = ArrayList<InetAddress>()
    val ipv6 = ArrayList<InetAddress>()
    for (literal in config.upstreams) {
      val address = try {
        InetAddress.getByName(literal)
      } catch (_: Exception) {
        continue
      }
      if (address is Inet6Address) ipv6.add(address) else ipv4.add(address)
    }
    addresses.addAll(ipv4)
    if (config.includeIpv6) addresses.addAll(ipv6)
    if (addresses.isEmpty()) {
      for (literal in VpnConfig.DEFAULT_UPSTREAMS) {
        try {
          addresses.add(InetAddress.getByName(literal))
        } catch (_: Exception) {
          // Ignored: we fall through to whatever resolved.
        }
      }
    }
    return addresses
  }

  private fun establish(config: VpnConfig): ParcelFileDescriptor? {
    val builder = Builder()
      .setSession("PornFree")
      .setMtu(NetworkConstants.MTU)
      .addAddress(NetworkConstants.TUN_ADDRESS_V4, 32)

    for (route in NetworkConstants.routes(config.interceptEncryptedDns, config.includeIpv6)) {
      try {
        builder.addRoute(route.address, route.prefix)
      } catch (error: Exception) {
        Log.w(TAG, "Skipping route ${route.address}/${route.prefix}: ${error.message}")
      }
    }

    for (literal in config.upstreams) {
      try {
        if (literal.contains(':')) {
          if (config.includeIpv6) builder.addRoute(literal, 128)
        } else {
          builder.addRoute(literal, 32)
        }
      } catch (_: Exception) {
        // An unusable upstream must not stop the tunnel.
      }
    }

    val ipv4Resolvers = config.upstreams.filter { !it.contains(':') }
    val ipv6Resolvers = config.upstreams.filter { it.contains(':') }
    for (resolver in ipv4Resolvers) {
      try {
        builder.addDnsServer(resolver)
      } catch (_: Exception) {
      }
    }
    if (config.includeIpv6) {
      try {
        builder.addAddress(NetworkConstants.TUN_ADDRESS_V6, 128)
        for (resolver in ipv6Resolvers) builder.addDnsServer(resolver)
      } catch (_: Exception) {
      }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      try {
        builder.setMetered(false)
      } catch (_: Exception) {
      }
    }
    try {
      return builder.establish()
    } catch (error: Exception) {
      Log.w(TAG, "establish() failed: ${error.message}")
      return null
    }
  }

  private fun handlePacket(packet: ByteArray, length: Int) {
    val header = IpWire.parse(packet, length) ?: return
    if (header.protocol == IpWire.PROTO_UDP) {
      val l4 = header.l4Offset
      if (l4 + 8 <= length) {
        val sourcePort = IpWire.u16(packet, l4)
        val destinationPort = IpWire.u16(packet, l4 + 2)
        if (destinationPort == 53) {
          var udpLength = IpWire.u16(packet, l4 + 4)
          if (udpLength < 8 || l4 + udpLength > length) udpLength = length - l4
          val dnsLength = udpLength - 8
          if (dnsLength >= 12) {
            handleQuery(header, packet, l4 + 8, dnsLength, sourcePort, destinationPort)
            return
          }
        }
      }
    }
    // Not plain DNS: this is an encrypted-DNS endpoint, so fail it fast.
    VpnRuntime.recordDropped()
    if (header.version == 4) {
      IpWire.buildPortUnreachable(packet, header, length)?.let { writePacket(it) }
    }
  }

  private fun handleQuery(
    header: IpWire.Header,
    packet: ByteArray,
    dnsOffset: Int,
    dnsLength: Int,
    sourcePort: Int,
    destinationPort: Int
  ) {
    val question = DnsWire.parseQuery(packet, dnsOffset, dnsLength)
    if (question == null) {
      forward(header, packet, dnsOffset, dnsLength, sourcePort, destinationPort, null)
      return
    }

    val name = question.name.lowercase()
    if (rules.isBlocked(name)) {
      val response = DnsWire.buildBlockedResponse(
        packet,
        dnsOffset,
        question.questionEnd,
        question.type,
        sinkhole = config.blockMode == BlockMode.NULL,
        nxdomain = config.blockMode == BlockMode.NXDOMAIN
      )
      writeReply(header, destinationPort, sourcePort, response)
      VpnRuntime.recordBlocked(name)
      return
    }

    val key = "$name|${question.type}"
    val cached = cache[key]
    if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
      val copy = cached.bytes.copyOf()
      DnsWire.setQueryId(copy, DnsWire.idOf(packet))
      writeReply(header, destinationPort, sourcePort, copy)
      VpnRuntime.recordCached()
      return
    }

    forward(header, packet, dnsOffset, dnsLength, sourcePort, destinationPort, key)
  }

  private fun forward(
    header: IpWire.Header,
    packet: ByteArray,
    dnsOffset: Int,
    dnsLength: Int,
    sourcePort: Int,
    destinationPort: Int,
    cacheKey: String?
  ) {
    val query = packet.copyOfRange(dnsOffset, dnsOffset + dnsLength)
    DnsWire.clampUdpPayloadSize(query, 0, query.size, NetworkConstants.MAX_DNS_RESPONSE - 8)
    val queryId = DnsWire.idOf(query)

    val response = exchange(query, queryId) ?: run {
      VpnRuntime.recordDropped()
      return
    }

    if (response.size > NetworkConstants.MAX_DNS_RESPONSE) {
      // Too large for a single tunnel packet. The client will retry over TCP, which we do not
      // intercept - rare, and better than handing back a broken datagram.
      VpnRuntime.recordDropped()
      return
    }

    writeReply(header, destinationPort, sourcePort, response)
    VpnRuntime.recordAllowed()

    if (cacheKey != null && DnsWire.isCacheable(response, response.size)) {
      if (cache.size >= MAX_CACHE_ENTRIES) cache.clear()
      val stored = response.copyOf()
      DnsWire.setQueryId(stored, 0)
      cache[cacheKey] = CachedResponse(stored, System.currentTimeMillis() + CACHE_TTL_MS)
    }
  }

  private fun exchange(query: ByteArray, queryId: Int): ByteArray? {
    for (upstream in upstreams) {
      val socket = if (upstream is Inet6Address) v6Socket.get() else v4Socket.get()
      try {
        socket.soTimeout = UPSTREAM_TIMEOUT_MS
        socket.send(DatagramPacket(query, query.size, upstream, 53))
        val buffer = ByteArray(4096)
        val deadline = System.currentTimeMillis() + UPSTREAM_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
          val incoming = DatagramPacket(buffer, buffer.size)
          socket.receive(incoming)
          if (incoming.length >= 12 && IpWire.u16(buffer, 0) == queryId) {
            return buffer.copyOf(incoming.length)
          }
        }
      } catch (_: Exception) {
        // Try the next resolver.
      }
    }
    return null
  }

  private fun protectedSocket(ipv6: Boolean): DatagramSocket {
    val socket = try {
      if (ipv6) DatagramSocket(null) else DatagramSocket()
    } catch (_: Exception) {
      return DatagramSocket()
    }
    if (!protect(socket)) {
      Log.w(TAG, "Could not exempt the resolver socket from the tunnel")
    }
    return socket
  }

  private fun writeReply(header: IpWire.Header, sourcePort: Int, destinationPort: Int, dns: ByteArray) {
    val packet = IpWire.buildUdpReply(header, sourcePort, destinationPort, dns)
    writePacket(packet)
  }

  private fun writePacket(packet: ByteArray) {
    val output = tunOutput ?: return
    synchronized(writeLock) {
      try {
        output.write(packet)
      } catch (_: IOException) {
        // The tunnel is going away; nothing useful to do here.
      }
    }
  }

  private fun startTicker() {
    val scheduler = Executors.newSingleThreadScheduledExecutor()
    ticker = scheduler
    scheduler.scheduleWithFixedDelay(
      {
        try {
          persistCounters()
          updateNotification()
        } catch (_: Exception) {
        }
      },
      5,
      10,
      TimeUnit.SECONDS
    )
  }

  /** Moves the session counters that are not persisted yet into the daily totals. */
  private fun persistCounters() {
    val blocked = VpnRuntime.blockedToday.get()
    val allowed = VpnRuntime.allowedToday.get()
    val deltaBlocked = blocked - persistedBlocked
    val deltaAllowed = allowed - persistedAllowed
    if (deltaBlocked == 0L && deltaAllowed == 0L) return
    persistedBlocked = blocked
    persistedAllowed = allowed
    Store.addDailyCounters(this, deltaBlocked, deltaAllowed)
  }

  private fun cleanup() {
    stopped = true
    persistCounters()
    ticker?.shutdownNow()
    ticker = null
    VpnRuntime.endSession()
    cache.clear()
    try {
      tunOutput?.close()
    } catch (_: IOException) {
    }
    try {
      tun?.close()
    } catch (_: IOException) {
    }
    tunOutput = null
    tun = null
    rules = DomainRules.EMPTY
    upstreams = emptyList()
  }

  private fun ensureChannel() = Notices.ensureChannel(this)

  private fun buildNotification(): Notification {
    val total = Store.totalBlocked(this) + VpnRuntime.blockedSession.get()
    val title = "Protection is on"
    val text = if (total > 0) "$total blocked so far" else "Watching every DNS lookup"

    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Notification.Builder(this, CHANNEL_ID)
    } else {
      @Suppress("DEPRECATION")
      Notification.Builder(this)
    }
    builder
      .setContentTitle(title)
      .setContentText(text)
      .setOngoing(true)
      .setShowWhen(false)
      .setSmallIcon(if (applicationInfo.icon != 0) applicationInfo.icon else android.R.drawable.stat_sys_warning)

    // Tap target is the vault rather than the UI: while the app is hidden from the launcher its
    // main activity cannot be started by anyone, and the vault restores the icon first.
    var flags = PendingIntent.FLAG_UPDATE_CURRENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
    val open = Intent(this, VaultActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    builder.setContentIntent(PendingIntent.getActivity(this, 0, open, flags))
    return builder.build()
  }

  private fun updateNotification() {
    try {
      val manager = getSystemService(NotificationManager::class.java) ?: return
      manager.notify(NOTIFICATION_ID, buildNotification())
    } catch (_: Exception) {
    }
  }
}
