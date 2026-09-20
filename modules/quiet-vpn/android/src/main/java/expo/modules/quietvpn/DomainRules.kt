package expo.modules.quietvpn

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter

/**
 * A set of domains where any sub-domain is considered covered.
 *
 * Looking up every suffix of a name (`ads.example.com`, `example.com`, `com`) is what makes hosts
 * style lists behave the way people expect, and it costs one hash lookup per label.
 */
class DomainRules(
  private val blocked: Set<String>,
  private val allowed: Set<String>
) {
  val blockedCount: Int get() = blocked.size
  val allowedCount: Int get() = allowed.size

  fun isBlocked(host: String): Boolean {
    if (host.isEmpty()) return false
    // Lists are stored lower case, and DNS names are case insensitive by definition.
    var name = host.lowercase()
    if (name.endsWith(".")) name = name.substring(0, name.length - 1)
    if (name.isEmpty()) return false
    if (hits(allowed, name)) return false
    return hits(blocked, name)
  }

  private fun hits(set: Set<String>, name: String): Boolean {
    if (set.isEmpty()) return false
    var index = 0
    while (true) {
      if (set.contains(name.substring(index))) return true
      val dot = name.indexOf('.', index)
      if (dot < 0) return false
      index = dot + 1
      if (index >= name.length) return false
    }
  }

  companion object {
    val EMPTY = DomainRules(emptySet(), emptySet())
  }
}

object DomainRulesLoader {
  fun load(paths: List<String>, extraBlocked: List<String>, allowed: List<String>): DomainRules {
    val blocked = HashSet<String>(1 shl 17)
    val allowedSet = HashSet<String>(64)

    for (path in paths) {
      try {
        val file = File(path)
        if (!file.isFile) continue
        BufferedReader(file.reader(), 1 shl 16).use { reader ->
          while (true) {
            val line = reader.readLine() ?: break
            for (domain in parseLine(line)) blocked.add(domain)
          }
        }
      } catch (_: Exception) {
        // A missing or unreadable list must never stop protection.
      }
    }

    for (domain in extraBlocked) normalize(domain)?.let { blocked.add(it) }
    for (domain in allowed) normalize(domain)?.let { allowedSet.add(it) }

    return DomainRules(blocked, allowedSet)
  }

  /** Normalises a plain domain typed by the user. */
  fun normalize(raw: String): String? = parseLine(raw.trim()).firstOrNull()

  /** Splits one line of a hosts/adblock/domain list into the domains it blocks. */
  fun parseLine(raw: String): List<String> {
    var line = raw.trim()
    if (line.isEmpty()) return emptyList()
    val first = line[0]
    if (first == '#' || first == '!' || first == '[' || first == ':' || first == '@') return emptyList()
    val comment = line.indexOf('#')
    if (comment > 0) line = line.substring(0, comment).trim()
    if (line.isEmpty()) return emptyList()

    val tokens = line.split(' ', '\t').filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return emptyList()
    val candidates = if (isIpLiteral(tokens[0])) tokens.drop(1) else listOf(tokens[0])
    if (candidates.isEmpty()) return emptyList()

    val domains = ArrayList<String>(candidates.size)
    for (token in candidates) normalizeToken(token)?.let { domains.add(it) }
    return domains
  }

  private fun normalizeToken(raw: String): String? {
    var token = raw.trim()
    if (token.isEmpty()) return null
    if (token.startsWith("@")) return null // adblock exception rule: never block
    if (token.startsWith("||")) token = token.substring(2)
    // dnsmasq style: address=/example.com/0.0.0.0
    if (token.startsWith("address=/")) {
      val end = token.indexOf('/', 9)
      if (end > 9) token = token.substring(9, end)
    }
    // A whole URL is accepted too: keep the host part only.
    val scheme = token.indexOf("://")
    if (scheme >= 0) token = token.substring(scheme + 3)
    val cut = token.indexOfFirst { it == '^' || it == '$' || it == '/' || it == '?' || it == ':' }
    if (cut >= 0) token = token.substring(0, cut)
    if (token.startsWith("*.")) token = token.substring(2)
    while (token.endsWith(".")) token = token.dropLast(1)
    token = token.lowercase()
    if (token.isEmpty() || token.length > 253) return null
    if (!token.contains('.')) return null // single labels ("localhost") are never blocked
    if (isIpLiteral(token)) return null
    if (token.startsWith("-") || token.endsWith("-")) return null
    if (token.contains("..")) return null
    for (ch in token) {
      val ok = (ch in 'a'..'z') || (ch in '0'..'9') || ch == '-' || ch == '.' || ch == '_'
      if (!ok) return null
    }
    return token
  }

  private fun isIpLiteral(value: String): Boolean {
    var seenColon = false
    var digits = 0
    for (ch in value) {
      when {
        ch == ':' -> seenColon = true
        ch in '0'..'9' -> digits++
        ch == '.' || ch == 'a' || ch == 'b' || ch == 'c' || ch == 'd' || ch == 'e' || ch == 'f' -> {}
        else -> return false
      }
    }
    if (seenColon) return digits > 0 // bare IPv6
    return value.all { it in '0'..'9' || it == '.' } && value.count { it == '.' } == 3
  }

  /** Rewrites any supported list into a plain domain list. Returns the number of domains written. */
  fun normalizeInto(input: InputStream, output: OutputStream): Int {
    val seen = HashSet<String>(1 shl 16)
    var count = 0
    val writer = BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8), 1 shl 16)
    BufferedReader(input.reader(Charsets.UTF_8), 1 shl 16).use { reader ->
      while (true) {
        val line = reader.readLine() ?: break
        for (domain in parseLine(line)) {
          if (seen.add(domain)) {
            writer.write(domain)
            writer.write("\n")
            count++
          }
        }
      }
    }
    writer.flush()
    return count
  }

  /** Counts the domains in a file without building a matcher. */
  fun countIn(file: File): Int {
    if (!file.isFile) return 0
    var count = 0
    return try {
      FileInputStream(file).use { input ->
        BufferedReader(input.reader(Charsets.UTF_8), 1 shl 16).use { reader ->
          while (true) {
            if (reader.readLine() == null) break
            count++
          }
        }
      }
      count
    } catch (_: Exception) {
      0
    }
  }
}
