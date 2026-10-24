package com.so.zapretksu

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ZapretCtl {

    const val MODDIR = "/data/adb/modules/zapret-ksu"
    private const val CTL = "$MODDIR/scripts/zapret-ctl.sh"

    data class Result(val ok: Boolean, val lines: List<String>, val error: String) {
        val text: String get() = error.ifBlank { lines.joinToString("\n") }
    }

    data class Snapshot(
        val update: Map<String, String>,
        val blockcheck: Map<String, String>,
        val results: List<String>
    )

    data class AppEntry(val pkg: String, val uid: String, val label: String, val user: Boolean)

    data class Provider(val id: String, val label: String, val doh: String, val dot: String, val group: String = "Public")

    data class DnsRow(val id: String, val ms: Int, val ok: Int, val total: Int, val status: String, val pick: Boolean)

    data class DnsJob(val state: String, val rows: List<DnsRow>, val message: String)

    private val SECTION_RE = Regex("^---([A-Z]+)---$")

    private fun quote(arg: String): String = "'" + arg.replace("'", "'\\''") + "'"

    private fun kv(lines: List<String>): Map<String, String> =
        lines.mapNotNull { line ->
            val i = line.indexOf('=')
            if (i > 0) line.substring(0, i) to line.substring(i + 1) else null
        }.toMap()

    private fun nonBlank(lines: List<String>): List<String> =
        lines.map { it.trim() }.filter { it.isNotEmpty() }

    suspend fun hasRoot(): Boolean = withContext(Dispatchers.IO) {
        try {
            Shell.getShell().isRoot
        } catch (e: Exception) {
            false
        }
    }

    suspend fun run(vararg args: String): Result = withContext(Dispatchers.IO) {
        val cmd = (listOf("sh", quote(CTL)) + args.map(::quote)).joinToString(" ")
        val r = Shell.cmd(cmd).exec()
        Result(r.isSuccess, r.out, r.err.joinToString("\n"))
    }

    suspend fun status(): Map<String, String> = kv(run("status").lines)
    suspend fun dump(): Map<String, String> = kv(run("dump").lines)
    suspend fun set(key: String, value: String): Result = run("set", key, value)
    suspend fun strategies(engine: String): List<String> = nonBlank(run("strategies", engine).lines)
    suspend fun interfaces(): List<String> = nonBlank(run("interfaces").lines)

    suspend fun start(): Result = run("start")
    suspend fun stop(): Result = run("stop")
    suspend fun restart(): Result = run("restart")

    suspend fun restoreNetwork(): Result = run("emergency")

    suspend fun log(lines: Int = 300): List<String> = run("log", lines.toString()).lines
    suspend fun clearLog(): Result = run("log", "clear")

    suspend fun snapshot(logLines: Int = 20): Snapshot {
        val lines = run("snapshot", logLines.toString()).lines
        return withContext(Dispatchers.IO) {
            val buckets = mutableMapOf<String, MutableList<String>>()
            var section = "STATUS"
            for (line in lines) {
                val m = SECTION_RE.find(line)
                if (m != null) {
                    section = m.groupValues[1]
                    continue
                }
                buckets.getOrPut(section) { mutableListOf() }.add(line)
            }
            Snapshot(
                update = kv(buckets["UPDATE"].orEmpty()),
                blockcheck = kv(buckets["BLOCKCHECK"].orEmpty()),
                results = buckets["BCRESULTS"].orEmpty().filter { it.isNotBlank() }
            )
        }
    }

    suspend fun latestVersions(): Map<String, String> = kv(run("latest-versions").lines)
    suspend fun installedVersions(): Map<String, String> = kv(run("installed-versions").lines)
    suspend fun update(target: String): Result = run("update", target)
    suspend fun updateCancel(): Result = run("update-cancel")

    suspend fun fileList(root: String): List<String> = nonBlank(run("file", "list", root).lines)

    suspend fun fileRead(root: String, name: String): String? {
        val r = run("file", "read", root, name)
        if (!r.ok) return null
        val b64 = r.lines.joinToString("")
        return withContext(Dispatchers.IO) {
            runCatching {
                String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull()
        }
    }

    suspend fun fileSave(root: String, name: String, content: String): Result {
        val chunks = withContext(Dispatchers.IO) {
            android.util.Base64.encodeToString(content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                .chunked(24_000)
        }
        val begin = run("file", "write-begin", root, name)
        if (!begin.ok) return begin
        for (chunk in chunks) {
            val r = run("file", "write-chunk", root, "", chunk)
            if (!r.ok) return r
        }
        return run("file", "write-commit", root, name)
    }

    suspend fun fileCreate(root: String, name: String): Result = run("file", "create", root, name)
    suspend fun fileImport(root: String, src: String, name: String): Result = run("file", "import", root, src, name)
    suspend fun fileImportArchive(root: String, src: String): Result = run("file", "import-archive", root, src)
    suspend fun fileExportArchive(root: String, dest: String): Result = run("file", "export-archive", root, dest)

    suspend fun bcStart(spec: String, dns: String, engine: String, services: String = "", perList: Int = 5): Result =
        run("blockcheck", "start", spec, dns, engine, services, perList.toString())

    data class BcService(val key: String, val label: String, val domains: List<String>)

    suspend fun bcServices(): List<BcService> = run("bc-services").lines.mapNotNull { line ->
        val p = line.split("|")
        if (p.size < 3 || p[0].isBlank()) null
        else BcService(p[0].trim(), p[1].trim(), p[2].trim().split(" ").filter { it.isNotBlank() })
    }
    data class ListService(val key: String, val label: String, val on: Boolean, val domains: List<String>)

    suspend fun listServices(): List<ListService> = run("lists-services").lines.mapNotNull { line ->
        val p = line.split("|")
        if (p.size < 4 || p[0].isBlank()) null
        else ListService(
            key = p[0].trim(),
            label = p[1].trim(),
            on = p[2].trim() == "1",
            domains = p[3].trim().split(" ").filter { it.isNotBlank() }
        )
    }

    suspend fun setListService(key: String, on: Boolean): Result =
        run("lists-service", key, if (on) "on" else "off")

    suspend fun addListDomain(domain: String): Result = run("lists-domain", "add", domain)

    suspend fun bcCancel(): Result = run("blockcheck", "cancel")
    suspend fun bcReport(): List<String> = run("bc-report").lines

    suspend fun useCandidate(engine: String, name: String): Result {
        val e = run("set", "ENGINE", engine)
        if (!e.ok) return e
        return run("set", "STRATEGY", name)
    }

    suspend fun dnsStatus(): Map<String, String> = kv(run("dns-status").lines)
    suspend fun dohStart(): Result = run("doh-start")
    suspend fun dohStop(): Result = run("doh-stop")
    suspend fun tgStatus(): Map<String, String> = kv(run("tg-status").lines)
    suspend fun tgStart(): Result = run("tg-start")
    suspend fun tgStop(): Result = run("tg-stop")
    suspend fun tgLog(lines: Int = 80): List<String> = run("tg-log", lines.toString()).lines
    suspend fun smartStatus(): Map<String, String> = kv(run("smart", "status").lines)

    data class WhitelistSite(val host: String, val ru: Boolean, val ok: Boolean, val ms: Int)

    data class WhitelistReport(val verdict: String, val engineOn: Boolean, val sites: List<WhitelistSite>)

    suspend fun whitelistCheck(): WhitelistReport {
        val r = run("whitelist-check")
        if (!r.ok) return WhitelistReport("error", false, emptyList())
        val sites = r.lines.mapNotNull { line ->
            val p = line.split('|')
            if (p.size != 4) null else WhitelistSite(p[1], p[0] == "ru", p[2] == "1", p[3].toIntOrNull() ?: 0)
        }
        val info = kv(r.lines.filter { !it.contains('|') })
        return WhitelistReport(info["verdict"].orEmpty(), info["engine"] == "on", sites)
    }
    suspend fun tgCheck(): Result = run("tg-check")
    suspend fun providers(): List<Provider> =
        run("dns-providers").lines.mapNotNull { line ->
            val p = line.split("|")
            if (p.size >= 2) {
                Provider(p[0], p[1], p.getOrElse(2) { "" }, p.getOrElse(3) { "" }, p.getOrElse(4) { "Public" }.ifBlank { "Public" })
            } else null
        }

    suspend fun dnsJobStart(mode: String, ids: String): Result = run("dns-job", "start", mode, ids)

    suspend fun dnsJobStatus(): DnsJob {
        val lines = run("dns-job", "status").lines
        val state = lines.firstOrNull { it.startsWith("job_state=") }?.substringAfter("=").orEmpty()
        val rows = lines.mapNotNull { l ->
            val f = l.split("|")
            if (f.size < 5) null
            else DnsRow(
                id = f[0],
                ms = f[1].toDoubleOrNull()?.toInt() ?: 0,
                ok = f[2].toIntOrNull() ?: 0,
                total = f[3].toIntOrNull() ?: 0,
                status = f[4],
                pick = f.getOrNull(5) == "1"
            )
        }
        val message = if (state == "error") {
            lines.lastOrNull { it.isNotBlank() && !it.contains("=") && !it.contains("|") }.orEmpty()
        } else ""
        return DnsJob(state, rows, message)
    }

    suspend fun dnsFastest(ids: String): Result = run("dns-fastest", ids)

    suspend fun dnsOff(): Result = run("dns-apply", "off")
    suspend fun dnsApply(id: String): Result = run("dns-apply", id)


    private val HOST_RE = Regex("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")

    fun customError(name: String, isDoh: Boolean, input: String): String? {
        val n = name.trim()
        if (n.isEmpty() || n.length > 40 || n.any { it == '|' || it == '·' || it.isISOControl() }) {
            return "Name must be 1-40 characters without | or ·"
        }
        val text = input.trim()
        if (!isDoh) {
            return if (HOST_RE.matches(text.lowercase())) null
            else "DoT needs a hostname such as dns.example.net (no https:// and no slash)"
        }
        val u = runCatching { java.net.URI(text) }.getOrNull()
        val ok = u != null && u.scheme.equals("https", ignoreCase = true) && !u.host.isNullOrBlank() &&
            u.rawUserInfo == null && u.rawQuery == null && u.rawFragment == null &&
            (u.rawPath?.length ?: 0) <= 100 && (u.host?.length ?: 0) <= 100
        return if (ok) null else "DoH needs an https URL with a host, such as https://dns.example.net/dns-query"
    }

    fun dohUrl(input: String): String {
        val u = java.net.URI(input.trim())
        val port = if (u.port != -1) ":${u.port}" else ""
        val path = u.rawPath.orEmpty().ifEmpty { "/dns-query" }
        return "https://${u.host.lowercase()}$port$path"
    }

    fun dohStamp(url: String): String {
        val u = java.net.URI(url)
        val port = if (u.port != -1) ":${u.port}" else ""
        val out = java.io.ByteArrayOutputStream()
        out.write(0x02)
        out.write(ByteArray(8))
        val fields = listOf(
            ByteArray(0),
            ByteArray(0),
            (u.host.lowercase() + port).toByteArray(Charsets.US_ASCII),
            u.rawPath.orEmpty().toByteArray(Charsets.US_ASCII)
        )
        for (f in fields) {
            out.write(f.size)
            out.write(f)
        }
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    private fun customLine(p: Provider): String {
        val stamp = if (p.doh.isNotBlank()) dohStamp(p.doh) else ""
        return listOf(p.id, p.label, p.doh, p.dot, stamp, "", "Custom").joinToString("|")
    }

    suspend fun dnsCustomSave(rows: List<Provider>): Result {
        val text = if (rows.isEmpty()) "\n" else rows.joinToString("") { customLine(it) + "\n" }
        val b64 = withContext(Dispatchers.IO) {
            android.util.Base64.encodeToString(text.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        }
        return run("dns-custom", "set", b64)
    }

    suspend fun dnsCustomAdd(name: String, isDoh: Boolean, input: String): Result {
        customError(name, isDoh, input)?.let { return Result(false, emptyList(), it) }
        val all = providers()
        val taken = all.map { it.id }.toSet()
        val url = if (isDoh) dohUrl(input) else ""
        val host = if (isDoh) java.net.URI(url).host else input.trim().lowercase()
        val slug = host.replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trimEnd('-')
        val base = "custom-${if (isDoh) "doh" else "dot"}-$slug"
        var id = base
        var n = 2
        while (id in taken) id = "$base-${n++}"
        val row = if (isDoh) Provider(id, name.trim(), url, "", "Custom")
        else Provider(id, name.trim(), "", host, "Custom")
        return dnsCustomSave(all.filter { it.group == "Custom" } + row)
    }

    suspend fun dnsCustomDelete(id: String): Result =
        dnsCustomSave(providers().filter { it.group == "Custom" && it.id != id })

    suspend fun appsList(): List<AppEntry> {
        val lines = run("apps-list").lines
        return withContext(Dispatchers.IO) {
            lines.mapNotNull { line ->
                val p = line.split("|")
                if (p.size >= 4) AppEntry(p[0], p[1], p[2], p[3] == "u") else null
            }
        }
    }

    suspend fun ipsetGet(): String = run("ipset", "get").lines.firstOrNull()?.trim().orEmpty()
    suspend fun ipsetSet(mode: String): Result = run("ipset", "set", mode)
    suspend fun fakesList(): List<String> = nonBlank(run("fakes", "list").lines)
    suspend fun fakesGet(): Map<String, String> = kv(run("fakes", "get").lines)
    suspend fun fakesSet(discord: String, game: String): Result {
        val d = run("fakes", "set", "discord", discord)
        if (!d.ok) return d
        return run("fakes", "set", "game", game)
    }

    suspend fun backupExport(dest: String): Result = run("backup-export", dest)
    suspend fun backupImport(path: String): Result = run("backup-import", path)
}
