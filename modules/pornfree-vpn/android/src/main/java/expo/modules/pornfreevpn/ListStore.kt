package expo.modules.pornfreevpn

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** The blocklists that ship inside the APK, so filtering works before anything is downloaded. */
object BundledLists {
  class Entry(
    val id: String,
    val title: String,
    val description: String,
    val asset: String,
    val sourceUrl: String
  )

  val ENTRIES: List<Entry> = listOf(
    Entry(
      id = "adult-core",
      title = "Adult content",
      description = "The pornography-only variant of the StevenBlack hosts project: " +
        "156k domains covering tube sites, cam sites, mirrors and redirectors.",
      asset = "blocklists/adult-core.txt",
      sourceUrl = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn/hosts"
    ),
    Entry(
      id = "doh-providers",
      title = "Encrypted DNS providers",
      description = "Hostnames of DNS-over-HTTPS and DNS-over-TLS services, so apps cannot quietly " +
        "bypass filtering with their own resolver.",
      asset = "blocklists/doh-providers.txt",
      sourceUrl = ""
    )
  )

  val DEFAULT_ENABLED: List<String> = ENTRIES.map { it.id }

  fun entry(id: String): Entry? = ENTRIES.firstOrNull { it.id == id }
}

/**
 * Owns the on-disk blocklists.
 *
 * Lists are always rewritten into a plain, deduplicated domain-per-line file, which keeps the
 * parser trivial and lets us report an accurate domain count to the UI.
 */
object ListStore {
  private const val DIR = "lists"
  private const val USER_AGENT = "PornFree/1.0"
  private const val MAX_BYTES = 64L * 1024 * 1024
  private const val MAX_REDIRECTS = 5

  fun dir(context: Context): File {
    val folder = File(context.applicationContext.filesDir, DIR)
    if (!folder.exists()) folder.mkdirs()
    return folder
  }

  fun fileFor(context: Context, id: String): File = File(dir(context), "${slug(id)}.txt")

  fun isInstalled(context: Context, id: String): Boolean {
    val file = fileFor(context, id)
    return file.isFile && file.length() > 0L
  }

  fun domainCount(context: Context, id: String): Int = DomainRulesLoader.countIn(fileFor(context, id))

  fun slug(raw: String): String {
    val builder = StringBuilder(raw.length)
    for (ch in raw.lowercase()) {
      if ((ch in 'a'..'z') || (ch in '0'..'9') || ch == '-' || ch == '_') builder.append(ch) else builder.append('-')
    }
    return if (builder.isEmpty()) "list" else builder.toString()
  }

  /** Copies a list that ships with the app. */
  fun seed(context: Context, id: String, asset: String): Int {
    val input = context.assets.open(asset)
    return writeAtomically(context, id, input)
  }

  /** Downloads a list and rewrites it in place. Returns the number of domains stored. */
  fun download(context: Context, id: String, url: String): Int {
    var current = url
    var redirects = 0
    while (true) {
      val connection = (URL(current).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 45_000
        instanceFollowRedirects = false
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Accept-Encoding", "gzip")
      }
      var stream: InputStream? = null
      try {
        val code = connection.responseCode
        if (code in 300..399) {
          val location = connection.getHeaderField("Location")
            ?: throw IOException("The server redirected without a destination")
          redirects++
          if (redirects > MAX_REDIRECTS) throw IOException("Too many redirects")
          current = if (location.startsWith("http://") || location.startsWith("https://")) {
            location
          } else {
            URL(URL(current), location).toString()
          }
          continue
        }
        if (code != 200) throw IOException("The server returned HTTP $code")
        if (connection.contentLengthLong > MAX_BYTES) throw IOException("The list is unexpectedly large")

        val raw = connection.inputStream
        stream = if (connection.contentEncoding?.contains("gzip", ignoreCase = true) == true) {
          GZIPInputStream(raw)
        } else {
          raw
        }
        return writeAtomically(context, id, LimitedStream(stream, MAX_BYTES))
      } finally {
        try {
          stream?.close()
        } catch (_: IOException) {
        }
        connection.disconnect()
      }
    }
  }

  fun delete(context: Context, id: String): Boolean {
    Store.removeListMeta(context, id)
    val file = fileFor(context, id)
    return !file.exists() || file.delete()
  }

  private fun writeAtomically(context: Context, id: String, input: InputStream): Int {
    val target = fileFor(context, id)
    val temporary = File(target.parentFile, "${target.name}.tmp")
    var count = 0
    try {
      FileOutputStream(temporary).use { output ->
        count = DomainRulesLoader.normalizeInto(input, output)
      }
    } catch (error: Exception) {
      temporary.delete()
      throw error
    } finally {
      try {
        input.close()
      } catch (_: IOException) {
      }
    }
    if (count <= 0) {
      temporary.delete()
      throw IOException("The list did not contain any usable domains")
    }
    if (target.exists() && !target.delete()) {
      temporary.delete()
      throw IOException("The existing list could not be replaced")
    }
    if (!temporary.renameTo(target)) {
      temporary.copyTo(target, overwrite = true)
      temporary.delete()
    }
    Store.updateListMeta(context, id) { entry ->
      entry.put("domains", count)
      entry.put("updatedAt", System.currentTimeMillis())
    }
    return count
  }

  private class LimitedStream(
    private val delegate: InputStream,
    private val limit: Long
  ) : InputStream() {
    private var consumed = 0L

    override fun read(): Int {
      val value = delegate.read()
      if (value >= 0) count(1)
      return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      val read = delegate.read(buffer, offset, length)
      if (read > 0) count(read)
      return read
    }

    private fun count(amount: Int) {
      consumed += amount
      if (consumed > limit) throw IOException("The list is larger than the allowed limit")
    }

    override fun close() = delegate.close()
  }
}
