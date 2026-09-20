package expo.modules.blockpornavpn

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import org.json.JSONObject

class VpnPermissionMissingException : CodedException(
  "ERR_VPN_PERMISSION_MISSING",
  "Android has not granted the VPN permission yet",
  null
)

class NotDeviceOwnerException : CodedException(
  "ERR_NOT_DEVICE_OWNER",
  "This needs BlockPorna to be provisioned as device owner",
  null
)

class WrongPinException : CodedException("ERR_WRONG_PIN", "That PIN is not correct", null)

class PinRequiredException : CodedException(
  "ERR_PIN_REQUIRED",
  "Set a PIN first: it is what protects this setting",
  null
)

class PinNotSetException : CodedException(
  "ERR_PIN_NOT_SET",
  "There is no PIN on this device yet",
  null
)

class CommitmentLockedException : CodedException(
  "ERR_COMMITMENT_LOCKED",
  "Protection is locked until your commitment ends",
  null
)

class StartOptions : Record {
  @Field val pinHash: String? = null
  @Field val upstreams: List<String> = emptyList()
  @Field val listIds: List<String> = emptyList()
  @Field val allowlist: List<String> = emptyList()
  @Field val blockMode: String = "nodata"
  @Field val interceptEncryptedDns: Boolean = true
  @Field val includeIpv6: Boolean = true
  @Field val autoRestart: Boolean = true
  @Field val logDomains: Boolean = true
}

class StopOptions : Record {
  @Field val pinHash: String? = null
}

class CommitmentOptions : Record {
  @Field val hours: Double = 0.0
  @Field val pinHash: String? = null
}

class SetPinOptions : Record {
  /** The new hash, or null to remove the PIN entirely. */
  @Field val hash: String? = null
  @Field val currentPinHash: String? = null
}

class PinOptions : Record {
  @Field val pinHash: String? = null
}

class UninstallOptions : Record {
  @Field val blocked: Boolean = false
  @Field val pinHash: String? = null
}

class EnableOptions : Record {
  @Field val enabled: Boolean = false
  @Field val pinHash: String? = null
}

class BlockPornaVpnModule : Module() {
  private val handler = Handler(Looper.getMainLooper())
  private var observing = false
  private var lastRunning = false

  private val ticker = object : Runnable {
    override fun run() {
      if (!observing) return
      try {
        emitState()
      } catch (_: Exception) {
      }
      handler.postDelayed(this, 1000)
    }
  }

  private val reactContext: Context
    get() = appContext.reactContext
      ?: throw CodedException("ERR_NO_CONTEXT", "The React context is no longer available", null)

  override fun definition() = ModuleDefinition {
    Name("BlockPornaVpn")

    Events("onStats", "onBlocked", "onStateChange")

    OnStartObserving {
      observing = true
      handler.post(ticker)
    }

    OnStopObserving {
      observing = false
      handler.removeCallbacks(ticker)
    }

    AsyncFunction("isPermissionGrantedAsync") {
      isPermissionGranted()
    }

    AsyncFunction("requestPermissionAsync") { promise: Promise ->
      requestPermission(promise)
    }

    AsyncFunction("startAsync") { options: StartOptions ->
      applyConfig(options)
      if (!isPermissionGranted()) throw VpnPermissionMissingException()
      BlockPornaVpnService.start(reactContext)
      status()
    }

    AsyncFunction("configureAsync") { options: StartOptions ->
      // Changing what gets blocked is protected: otherwise anyone holding the phone could
      // simply switch the lists off.
      requirePin(options.pinHash)
      applyConfig(options)
      if (VpnRuntime.running) BlockPornaVpnService.restart(reactContext)
      status()
    }

    AsyncFunction("stopAsync") { options: StopOptions ->
      val context = reactContext
      if (Store.commitmentUntil(context) > System.currentTimeMillis()) throw CommitmentLockedException()
      requirePin(options.pinHash)
      Store.setShouldRun(context, false)
      BlockPornaVpnService.stop(context)
      VpnRuntime.endSession()
      status()
    }

    AsyncFunction("getStatusAsync") {
      status()
    }

    AsyncFunction("getConfigAsync") {
      configMap(Store.loadConfig(reactContext))
    }

    AsyncFunction("getListsAsync") {
      listMetadata()
    }

    AsyncFunction("ensureListsAsync") {
      seedBundledLists()
      listMetadata()
    }

    AsyncFunction("installBundledListAsync") { id: String ->
      installBundledList(id)
    }

    AsyncFunction("downloadListAsync") { id: String, url: String, title: String ->
      val context = reactContext
      val domains = ListStore.download(context, id, url)
      Store.updateListMeta(context, id) { entry ->
        entry.put("title", title)
        entry.put("sourceUrl", url)
      }
      metaFor(id, domains, url, title)
    }

    AsyncFunction("deleteListAsync") { id: String ->
      val context = reactContext
      val config = Store.loadConfig(context)
      if (config.listIds.contains(id)) {
        Store.saveConfig(context, config.copy(listIds = config.listIds.filter { it != id }))
      }
      ListStore.delete(context, id)
      true
    }

    AsyncFunction("getDailyStatsAsync") { days: Int ->
      val history = Store.readDailyCounters(reactContext, days.coerceIn(1, 365))
      val out = ArrayList<Map<String, Any>>(history.length())
      for (i in 0 until history.length()) {
        val item = history.optJSONObject(i) ?: continue
        out.add(
          mapOf(
            "date" to item.optString("date"),
            "blocked" to item.optLong("blocked"),
            "allowed" to item.optLong("allowed")
          )
        )
      }
      out
    }

    AsyncFunction("clearStatsAsync") {
      Store.clearDailyCounters(reactContext)
      true
    }

    AsyncFunction("setPinAsync") { options: SetPinOptions ->
      val context = reactContext
      // Replacing or removing a PIN always requires the one that is already in place.
      if (Store.pinHash(context) != null) requirePin(options.currentPinHash)
      val hash = options.hash
      if (hash.isNullOrEmpty()) {
        Store.setPinHash(context, null)
        Store.setCommitmentUntil(context, 0L)
        if (isDeviceOwner()) {
          // Without a PIN there is nothing standing between a user and the uninstall lock,
          // so the lock is released together with the PIN.
          try {
            devicePolicyManager()?.setUninstallBlocked(adminComponent(context), context.packageName, false)
          } catch (_: Exception) {
          }
        }
      } else {
        if (hash.length < 32) throw WrongPinException()
        Store.setPinHash(context, hash)
      }
      status()
    }

    AsyncFunction("verifyPinAsync") { hash: String ->
      Store.pinHash(reactContext) == hash
    }

    AsyncFunction("setCommitmentAsync") { options: CommitmentOptions ->
      val context = reactContext
      requirePin(options.pinHash)
      val hours = options.hours.coerceIn(0.0, 24.0 * 365.0)
      val until = if (hours <= 0.0) 0L else System.currentTimeMillis() + (hours * 3_600_000.0).toLong()
      Store.setCommitmentUntil(context, until)
      if (until > 0L && !VpnRuntime.running && isPermissionGranted()) {
        BlockPornaVpnService.start(context)
      }
      status()
    }

    AsyncFunction("isDeviceOwnerAsync") {
      isDeviceOwner()
    }

    AsyncFunction("getUninstallStateAsync") {
      uninstallState()
    }

    /**
     * The closest thing Android offers to "a password is needed to uninstall".
     *
     * Only a device owner (or profile owner) may block an uninstall; a plain device admin lost that
     * power in Android 7. Turning the lock on therefore requires the app to have been provisioned as
     * device owner over adb once, and it always requires a PIN to have been set - the PIN is what
     * stops someone from simply walking into the app and switching the lock off again.
     */
    AsyncFunction("setUninstallBlockedAsync") { options: UninstallOptions ->
      val context = reactContext
      requirePin(options.pinHash)
      if (options.blocked && Store.pinHash(context) == null) throw PinRequiredException()
      val manager = devicePolicyManager() ?: throw NotDeviceOwnerException()
      if (!manager.isDeviceOwnerApp(context.packageName)) throw NotDeviceOwnerException()
      manager.setUninstallBlocked(adminComponent(context), context.packageName, options.blocked)
      uninstallState()
    }

    AsyncFunction("setAlwaysOnVpnAsync") { options: EnableOptions ->
      val context = reactContext
      requirePin(options.pinHash)
      val manager = devicePolicyManager() ?: throw NotDeviceOwnerException()
      if (!manager.isDeviceOwnerApp(context.packageName) && !manager.isProfileOwnerApp(context.packageName)) {
        throw NotDeviceOwnerException()
      }
      // Lockdown is intentionally never enabled: BlockPorna only routes DNS, so blocking
      // non-VPN traffic would take the device offline.
      manager.setAlwaysOnVpnPackage(adminComponent(context), if (options.enabled) context.packageName else null, false)
      uninstallState()
    }

    AsyncFunction("removeDeviceOwnerAsync") { options: PinOptions ->
      val context = reactContext
      requirePin(options.pinHash)
      if (Store.pinHash(context) == null) throw PinNotSetException()
      val manager = devicePolicyManager() ?: throw NotDeviceOwnerException()
      if (!manager.isDeviceOwnerApp(context.packageName)) throw NotDeviceOwnerException()
      try {
        manager.setUninstallBlocked(adminComponent(context), context.packageName, false)
        manager.setAlwaysOnVpnPackage(adminComponent(context), null, false)
      } catch (_: Exception) {
      }
      manager.clearDeviceOwnerApp(context.packageName)
      uninstallState()
    }
  }

  private fun emitState() {
    sendEvent("onStats", status())
    val drained = VpnRuntime.drainRecent()
    if (drained.isNotEmpty()) {
      sendEvent(
        "onBlocked",
        mapOf(
          "domains" to drained.map { mapOf("domain" to it.domain, "at" to it.at) }
        )
      )
    }
    val running = VpnRuntime.running
    if (running != lastRunning) {
      lastRunning = running
      sendEvent("onStateChange", mapOf("running" to running))
    }
  }

  private fun isPermissionGranted(): Boolean = try {
    VpnService.prepare(reactContext) == null
  } catch (_: Exception) {
    false
  }

  private fun requestPermission(promise: Promise) {
    val context = reactContext
    if (isPermissionGranted()) {
      promise.resolve(true)
      return
    }
    val activity = appContext.currentActivity ?: appContext.activityProvider?.currentActivity
    if (activity == null) {
      promise.resolve(false)
      return
    }
    val settled = java.util.concurrent.atomic.AtomicBoolean(false)
    VpnConsent.await { granted ->
      if (settled.compareAndSet(false, true)) promise.resolve(granted)
    }
    val intent = android.content.Intent(context, VpnConsentActivity::class.java)
      .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
      context.startActivity(intent)
    } catch (_: Exception) {
      if (settled.compareAndSet(false, true)) promise.resolve(false)
      return
    }
    handler.postDelayed(
      { if (settled.compareAndSet(false, true)) promise.resolve(isPermissionGranted()) },
      180_000
    )
  }

  private fun applyConfig(options: StartOptions) {
    val context = reactContext
    val config = VpnConfig(
      upstreams = options.upstreams.ifEmpty { VpnConfig.DEFAULT_UPSTREAMS },
      listIds = options.listIds,
      allowlist = options.allowlist.mapNotNull { DomainRulesLoader.normalize(it) },
      blockMode = BlockMode.fromValue(options.blockMode),
      interceptEncryptedDns = options.interceptEncryptedDns,
      includeIpv6 = options.includeIpv6,
      autoRestart = options.autoRestart,
      logDomains = options.logDomains
    )
    Store.saveConfig(context, config)
  }

  /** On a fresh install, make sure the bundled lists exist and are switched on. */
  private fun seedBundledLists() {
    val context = reactContext
    for (entry in BundledLists.ENTRIES) {
      if (!ListStore.isInstalled(context, entry.id)) installBundledList(entry.id)
    }
    val prefs = Store.prefs(context)
    if (!prefs.contains("config")) {
      Store.saveConfig(context, VpnConfig(listIds = BundledLists.DEFAULT_ENABLED))
    }
  }

  private fun installBundledList(id: String): Map<String, Any> {
    val context = reactContext
    val entry = BundledLists.entry(id)
      ?: throw CodedException("ERR_UNKNOWN_LIST", "There is no bundled list called $id", null)
    val domains = ListStore.seed(context, entry.id, entry.asset)
    return metaFor(entry.id, domains, entry.sourceUrl, entry.title)
  }

  private fun metaFor(id: String, domains: Int, sourceUrl: String, title: String): Map<String, Any> {
    val context = reactContext
    val config = Store.loadConfig(context)
    val entry = BundledLists.entry(id)
    val meta = Store.listsMeta(context)
    var updatedAt = 0L
    for (i in 0 until meta.length()) {
      val item = meta.optJSONObject(i) ?: continue
      if (item.optString("id") == id) updatedAt = item.optLong("updatedAt", 0L)
    }
    return mapOf(
      "id" to id,
      "title" to (entry?.title ?: title),
      "description" to (entry?.description ?: ""),
      "sourceUrl" to (entry?.sourceUrl ?: sourceUrl),
      "bundled" to (entry != null),
      "installed" to ListStore.isInstalled(context, id),
      "enabled" to config.listIds.contains(id),
      "domains" to domains,
      "updatedAt" to updatedAt
    )
  }

  private fun listMetadata(): List<Map<String, Any>> {
    val context = reactContext
    val config = Store.loadConfig(context)
    val storedCounts = HashMap<String, Int>()
    val storedMeta = HashMap<String, JSONObject>()
    val meta = Store.listsMeta(context)
    for (i in 0 until meta.length()) {
      val item = meta.optJSONObject(i) ?: continue
      val id = item.optString("id")
      if (id.isEmpty()) continue
      storedMeta[id] = item
      storedCounts[id] = item.optInt("domains", 0)
    }

    val out = ArrayList<Map<String, Any>>()
    for (entry in BundledLists.ENTRIES) {
      out.add(describe(entry.id, entry.title, entry.description, entry.sourceUrl, true, storedMeta))
    }
    for ((id, item) in storedMeta) {
      if (BundledLists.entry(id) != null) continue
      out.add(
        describe(
          id,
          item.optString("title", id),
          "",
          item.optString("sourceUrl", ""),
          false,
          storedMeta
        )
      )
    }
    // The config knows which lists are on; the counts were just read.
    return out.map { item ->
      val id = item["id"] as String
      val domains = (item["domains"] as Int).takeIf { it > 0 }
        ?: storedCounts[id]?.takeIf { it > 0 }
        ?: if (ListStore.isInstalled(context, id)) ListStore.domainCount(context, id) else 0
      item + mapOf("domains" to domains, "enabled" to config.listIds.contains(id))
    }
  }

  private fun describe(
    id: String,
    title: String,
    description: String,
    sourceUrl: String,
    bundled: Boolean,
    storedMeta: Map<String, JSONObject>
  ): Map<String, Any> {
    val context = reactContext
    return mapOf(
      "id" to id,
      "title" to title,
      "description" to description,
      "sourceUrl" to sourceUrl,
      "bundled" to bundled,
      "installed" to ListStore.isInstalled(context, id),
      "enabled" to false,
      "domains" to (storedMeta[id]?.optInt("domains", 0) ?: 0),
      "updatedAt" to (storedMeta[id]?.optLong("updatedAt", 0L) ?: 0L)
    )
  }

  private fun status(): Map<String, Any> {
    val context = reactContext
    val out = HashMap<String, Any>(VpnRuntime.status())
    out["permissionGranted"] = isPermissionGranted()
    out["totalBlocked"] = Store.totalBlocked(context)
    out["commitmentUntil"] = Store.commitmentUntil(context)
    out["hasPin"] = Store.pinHash(context) != null
    out["deviceOwner"] = isDeviceOwner()
    out["installedAt"] = Store.installedAt(context)
    out["shouldRun"] = Store.shouldRun(context)
    return out
  }

  private fun requirePin(pinHash: String?) {
    val stored = Store.pinHash(reactContext)
    if (stored == null) return
    if (stored != pinHash) throw WrongPinException()
  }

  private fun configMap(config: VpnConfig): Map<String, Any> = mapOf(
    "upstreams" to config.upstreams,
    "listIds" to config.listIds,
    "allowlist" to config.allowlist,
    "blockMode" to config.blockMode.value,
    "interceptEncryptedDns" to config.interceptEncryptedDns,
    "includeIpv6" to config.includeIpv6,
    "autoRestart" to config.autoRestart,
    "logDomains" to config.logDomains
  )

  private fun uninstallState(): Map<String, Any> {
    val context = reactContext
    val manager = devicePolicyManager()
    val deviceOwner = isDeviceOwner()
    var uninstallBlocked = false
    var alwaysOn = false
    if (manager != null) {
      try {
        uninstallBlocked = manager.isUninstallBlocked(adminComponent(context), context.packageName)
      } catch (_: Exception) {
      }
      if (deviceOwner) {
        try {
          alwaysOn = manager.getAlwaysOnVpnPackage(adminComponent(context)) != null
        } catch (_: Exception) {
        }
      }
    }
    return mapOf(
      "deviceOwner" to deviceOwner,
      "profileOwner" to (
        try {
          manager?.isProfileOwnerApp(context.packageName) == true
        } catch (_: Exception) {
          false
        }
        ),
      "uninstallBlocked" to uninstallBlocked,
      "alwaysOnVpn" to alwaysOn,
      "hasPin" to (Store.pinHash(context) != null),
      "packageName" to context.packageName,
      "adminComponent" to adminComponent(context).flattenToShortString()
    )
  }

  private fun devicePolicyManager(): DevicePolicyManager? =
    reactContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager

  private fun isDeviceOwner(): Boolean = try {
    devicePolicyManager()?.isDeviceOwnerApp(reactContext.packageName) == true
  } catch (_: Exception) {
    false
  }

  private fun adminComponent(context: Context) =
    ComponentName(context, BlockPornaDeviceAdminReceiver::class.java)
}
