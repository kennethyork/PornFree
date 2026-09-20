package expo.modules.pornfreevpn

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class BlockMode(val value: String) {
  NODATA("nodata"),
  NULL("null"),
  NXDOMAIN("nxdomain");

  companion object {
    fun fromValue(value: String?): BlockMode = entries.firstOrNull { it.value == value } ?: NODATA
  }
}

data class VpnConfig(
  val upstreams: List<String> = DEFAULT_UPSTREAMS,
  val listIds: List<String> = emptyList(),
  val allowlist: List<String> = emptyList(),
  val blockMode: BlockMode = BlockMode.NODATA,
  val interceptEncryptedDns: Boolean = true,
  val includeIpv6: Boolean = true,
  val autoRestart: Boolean = true,
  val logDomains: Boolean = true
) {
  fun toJson(): JSONObject = JSONObject().apply {
    put("upstreams", JSONArray(upstreams))
    put("listIds", JSONArray(listIds))
    put("allowlist", JSONArray(allowlist))
    put("blockMode", blockMode.value)
    put("interceptEncryptedDns", interceptEncryptedDns)
    put("includeIpv6", includeIpv6)
    put("autoRestart", autoRestart)
    put("logDomains", logDomains)
  }

  companion object {
    /** CleanBrowsing Family: filters adult content upstream as well as in the local lists. */
    val DEFAULT_UPSTREAMS = listOf("185.228.168.9", "185.228.169.9")

    fun fromJson(text: String?): VpnConfig {
      if (text.isNullOrBlank()) return VpnConfig()
      return try {
        val json = JSONObject(text)
        VpnConfig(
          upstreams = json.readStringList("upstreams").ifEmpty { DEFAULT_UPSTREAMS },
          listIds = json.readStringList("listIds"),
          allowlist = json.readStringList("allowlist"),
          blockMode = BlockMode.fromValue(json.optString("blockMode", "nodata")),
          interceptEncryptedDns = json.optBoolean("interceptEncryptedDns", true),
          includeIpv6 = json.optBoolean("includeIpv6", true),
          autoRestart = json.optBoolean("autoRestart", true),
          logDomains = json.optBoolean("logDomains", true)
        )
      } catch (_: Exception) {
        VpnConfig()
      }
    }

    private fun JSONObject.readStringList(key: String): List<String> {
      val array = optJSONArray(key) ?: return emptyList()
      val out = ArrayList<String>(array.length())
      for (i in 0 until array.length()) {
        val value = array.optString(i, "")
        if (value.isNotEmpty()) out.add(value)
      }
      return out
    }
  }
}

/** All persistence that has to outlive the JavaScript context (and the app itself). */
object Store {
  private const val PREFS = "pornfree_state"
  private const val KEY_CONFIG = "config"
  private const val KEY_LISTS = "lists"
  private const val KEY_PIN = "pin_hash"
  private const val KEY_COMMITMENT = "commitment_until"
  private const val KEY_SHOULD_RUN = "should_run"
  private const val KEY_INSTALLED_AT = "installed_at"

  fun prefs(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun saveConfig(context: Context, config: VpnConfig) {
    prefs(context).edit().putString(KEY_CONFIG, config.toJson().toString()).apply()
  }

  fun loadConfig(context: Context): VpnConfig = VpnConfig.fromJson(prefs(context).getString(KEY_CONFIG, null))

  /** Remembers the user's intent so the tunnel can come back after a reboot. */
  fun setShouldRun(context: Context, shouldRun: Boolean) {
    prefs(context).edit().putBoolean(KEY_SHOULD_RUN, shouldRun).apply()
  }

  fun shouldRun(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOULD_RUN, false)

  fun pinHash(context: Context): String? = prefs(context).getString(KEY_PIN, null)

  fun setPinHash(context: Context, hash: String?) {
    prefs(context).edit().apply {
      if (hash.isNullOrEmpty()) remove(KEY_PIN) else putString(KEY_PIN, hash)
    }.apply()
  }

  fun commitmentUntil(context: Context): Long = prefs(context).getLong(KEY_COMMITMENT, 0L)

  fun setCommitmentUntil(context: Context, until: Long) {
    prefs(context).edit().putLong(KEY_COMMITMENT, until).apply()
  }

  fun installedAt(context: Context): Long {
    val prefs = prefs(context)
    val existing = prefs.getLong(KEY_INSTALLED_AT, 0L)
    if (existing != 0L) return existing
    val now = System.currentTimeMillis()
    prefs.edit().putLong(KEY_INSTALLED_AT, now).apply()
    return now
  }

  fun listsMeta(context: Context): JSONArray {
    val text = prefs(context).getString(KEY_LISTS, null) ?: return JSONArray()
    return try {
      JSONArray(text)
    } catch (_: Exception) {
      JSONArray()
    }
  }

  fun saveListsMeta(context: Context, meta: JSONArray) {
    prefs(context).edit().putString(KEY_LISTS, meta.toString()).apply()
  }

  fun updateListMeta(context: Context, id: String, updater: (JSONObject) -> Unit) {
    val meta = listsMeta(context)
    var found: JSONObject? = null
    for (i in 0 until meta.length()) {
      val item = meta.optJSONObject(i) ?: continue
      if (item.optString("id") == id) {
        found = item
        break
      }
    }
    val entry = found ?: JSONObject().apply { put("id", id) }
    updater(entry)
    if (found == null) meta.put(entry)
    saveListsMeta(context, meta)
  }

  fun removeListMeta(context: Context, id: String) {
    val meta = listsMeta(context)
    val kept = JSONArray()
    for (i in 0 until meta.length()) {
      val item = meta.optJSONObject(i) ?: continue
      if (item.optString("id") != id) kept.put(item)
    }
    saveListsMeta(context, kept)
  }

  private fun dayKey(date: Date): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)

  fun addDailyCounters(context: Context, blocked: Long, allowed: Long) {
    if (blocked == 0L && allowed == 0L) return
    val prefs = prefs(context)
    val key = dayKey(Date())
    val edit = prefs.edit()
    if (blocked != 0L) edit.putLong("stat.$key.blocked", prefs.getLong("stat.$key.blocked", 0L) + blocked)
    if (allowed != 0L) edit.putLong("stat.$key.allowed", prefs.getLong("stat.$key.allowed", 0L) + allowed)
    edit.apply()
  }

  /** Returns the last [days] days, oldest first, as `{ date, blocked, allowed }` objects. */
  fun readDailyCounters(context: Context, days: Int): JSONArray {
    val prefs = prefs(context)
    val out = JSONArray()
    val calendar = Calendar.getInstance()
    calendar.add(Calendar.DAY_OF_YEAR, -(days - 1))
    for (i in 0 until days) {
      val key = dayKey(calendar.time)
      out.put(
        JSONObject().apply {
          put("date", key)
          put("blocked", prefs.getLong("stat.$key.blocked", 0L))
          put("allowed", prefs.getLong("stat.$key.allowed", 0L))
        }
      )
      calendar.add(Calendar.DAY_OF_YEAR, 1)
    }
    return out
  }

  fun todayBlocked(context: Context): Long =
    prefs(context).getLong("stat.${dayKey(Date())}.blocked", 0L)

  fun todayAllowed(context: Context): Long =
    prefs(context).getLong("stat.${dayKey(Date())}.allowed", 0L)

  fun clearDailyCounters(context: Context) {
    val edit = prefs(context).edit()
    for (key in prefs(context).all.keys) {
      if (key.startsWith("stat.")) edit.remove(key)
    }
    edit.apply()
  }

  fun totalBlocked(context: Context): Long {
    val prefs = prefs(context)
    var total = 0L
    for ((key, value) in prefs.all) {
      if (key.startsWith("stat.") && key.endsWith(".blocked") && value is Long) total += value
    }
    return total
  }
}
