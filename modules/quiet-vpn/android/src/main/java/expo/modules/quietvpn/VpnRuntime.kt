package expo.modules.quietvpn

import java.util.concurrent.atomic.AtomicLong

data class BlockedDomain(val domain: String, val at: Long)

/**
 * Live counters shared between the tunnel service and the JavaScript bridge. Both live in the same
 * process, so a plain singleton is enough and avoids any IPC.
 */
object VpnRuntime {
  private const val MAX_RECENT = 400

  @Volatile
  var running: Boolean = false

  @Volatile
  var logDomains: Boolean = true

  @Volatile
  var listSize: Int = 0

  @Volatile
  var startedAt: Long = 0L

  val blockedSession = AtomicLong(0)
  val allowedSession = AtomicLong(0)
  val cachedSession = AtomicLong(0)
  val droppedSession = AtomicLong(0)

  /** Counters for today, live, including any earlier session from the same day. */
  val blockedToday = AtomicLong(0)
  val allowedToday = AtomicLong(0)

  private val recent = ArrayDeque<BlockedDomain>()
  private val recentLock = Any()

  fun startSession(listSize: Int, blockedToday: Long, allowedToday: Long) {
    blockedSession.set(0)
    allowedSession.set(0)
    cachedSession.set(0)
    droppedSession.set(0)
    this.blockedToday.set(blockedToday)
    this.allowedToday.set(allowedToday)
    synchronized(recentLock) { recent.clear() }
    this.listSize = listSize
    startedAt = System.currentTimeMillis()
    running = true
  }

  fun endSession() {
    running = false
    startedAt = 0L
  }

  fun recordBlocked(domain: String) {
    blockedSession.incrementAndGet()
    blockedToday.incrementAndGet()
    if (!logDomains) return
    synchronized(recentLock) {
      recent.addLast(BlockedDomain(domain, System.currentTimeMillis()))
      while (recent.size > MAX_RECENT) recent.removeFirst()
    }
  }

  fun recordAllowed() {
    allowedSession.incrementAndGet()
    allowedToday.incrementAndGet()
  }

  fun recordCached() {
    cachedSession.incrementAndGet()
    allowedSession.incrementAndGet()
    allowedToday.incrementAndGet()
  }

  fun recordDropped() {
    droppedSession.incrementAndGet()
  }

  fun drainRecent(): List<BlockedDomain> = synchronized(recentLock) {
    if (recent.isEmpty()) return emptyList()
    val out = ArrayList<BlockedDomain>(recent.size)
    out.addAll(recent)
    recent.clear()
    out
  }

  fun status(): Map<String, Any> = mapOf(
    "running" to running,
    "listSize" to listSize,
    "startedAt" to startedAt,
    "blockedSession" to blockedSession.get(),
    "allowedSession" to allowedSession.get(),
    "cachedSession" to cachedSession.get(),
    "droppedSession" to droppedSession.get(),
    "blockedToday" to blockedToday.get(),
    "allowedToday" to allowedToday.get(),
    "blockedRecent" to synchronized(recentLock) { recent.size }
  )
}
