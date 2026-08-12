package app.gamenative.shaders

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * On-demand shader pack: a single filtered extraction of the libretro/slang-shaders
 * tarball into {@code filesDir/retroarch_pack}. Nothing ships in the APK — the pack is
 * downloaded only when the user asks for shaders, and only the files any preset needs
 * (the catalog's dependency-closure union) are written to disk.
 *
 * The pack preserves the repo-root-relative layout, so librashader's own relative
 * resolution of `shaderN`, `#include`, `#reference` and texture paths (including
 * cross-folder references like `../../crt/shaders/...`) works unchanged.
 */
class ShaderPack(context: Context, private val catalogCommit: String = "") {

    private val appContext = context.applicationContext

    val packDir: File get() = File(appContext.filesDir, "retroarch_pack")
    private val marker: File get() = File(packDir, ".complete")
    private val tmpDir: File get() = File(appContext.filesDir, "retroarch_pack.tmp")

    @Volatile private var activeCall: Call? = null
    @Volatile private var cancelRequested = false

    // Marker cache: `isLocal` runs per preset row while the browser renders a page, and
    // reading `.complete` from disk each time is wasteful. Invalidate on the only two
    // in-process mutators (install/clear); external changes (user restored data) are
    // picked up because a new ShaderPack is built per game session.
    @Volatile private var markerCache: String? = null
    @Volatile private var markerCacheValid = false

    companion object {
        /**
         * Pinned-commit tarball URL (spec §4.2.1). Branch URLs (`refs/heads/master`) drift:
         * upstream renames break the catalog whitelist the moment master moves. The catalog
         * pins a commit; downloading exactly that commit keeps pack and whitelist in sync.
         */
        fun tarballUrlFor(commit: String): String =
            "https://codeload.github.com/libretro/slang-shaders/tar.gz/$commit"
    }

    /**
     * Commit recorded in `.complete`, or null when no pack is installed.
     * This is the integrity base of §4.3.
     */
    fun markerCommit(): String? {
        if (!markerCacheValid) {
            markerCache = marker.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
            markerCacheValid = true
        }
        return markerCache
    }

    private fun invalidateMarkerCache() {
        markerCache = null
        markerCacheValid = false
    }

    /**
     * §4.3 integrity: a marker whose commit diverges from the catalog is not a valid install
     * (user restored data, or the APK shipped a newer catalog). The UI offers an update with
     * the same install flow (tmp → rename replaces the whole pack).
     */
    fun status(): PackStatus {
        val commit = markerCommit() ?: return PackStatus.NOT_INSTALLED
        return if (catalogCommit.isBlank() || commit == catalogCommit) {
            PackStatus.INSTALLED
        } else {
            PackStatus.UPDATE_AVAILABLE
        }
    }

    fun isInstalled(): Boolean = status() == PackStatus.INSTALLED

    /**
     * True when the pack is installed and this preset is ready to load. The pack is the
     * dependency-closure union, so presence of the preset file is sufficient — except for
     * upstream-broken presets, whose unresolved references can never become local.
     */
    fun isLocal(preset: ShaderPreset): Boolean {
        if (preset.broken) return false
        if (!isInstalled()) return false
        return File(packDir, preset.path).isFile
    }

    /** Absolute path of the preset inside the pack, or null when not fully local. */
    fun presetFile(preset: ShaderPreset): File? {
        if (!isLocal(preset)) return null
        val file = File(packDir, preset.path)
        return file.takeIf { it.isFile }
    }

    /**
     * Downloads and extracts the pack. Reports byte progress via [onProgress]
     * (downloaded, total; total may be -1) and calls [onExtracting] once.
     * The marker is written only after a fully successful extraction.
     *
     * Pre-checks (spec §4.2): free space (tmp + final + headroom) and metered-network
     * disclosure. [allowMetered] is the user's explicit consent from the confirmation
     * dialog — without it a metered network fails with [PackMeteredException] before any
     * byte is transferred. Failures are typed so the UI can show the honest state:
     * [PackNoSpaceException], [PackMeteredException], [PackCancelledException].
     */
    suspend fun install(
        catalog: ShaderCatalog,
        allowMetered: Boolean = false,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
        onExtracting: () -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val packBytes = catalog.data.source.packBytes
            val required = PackPrechecks.requiredFreeBytes(packBytes)
            val available = StatFs(appContext.filesDir.absolutePath).availableBytes
            if (!PackPrechecks.hasEnoughSpace(packBytes, available)) {
                throw PackNoSpaceException(required, available)
            }
            if (!allowMetered && PackPrechecks.needsMeteredConfirmation(isMeteredNetwork())) {
                throw PackMeteredException(packBytes)
            }

            cancelRequested = false
            tmpDir.deleteRecursively()
            tmpDir.mkdirs()

            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
            val commit = catalog.data.source.commit.ifBlank { "refs/heads/master" }
            val request = Request.Builder().url(tarballUrlFor(commit)).build()
            val call = client.newCall(request)
            activeCall = call
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("empty response body")
                    val total = body.contentLength()
                    var downloaded = 0L
                    val counting = object : FilterInputStream(body.byteStream()) {
                        override fun read(): Int {
                            val b = super.read()
                            if (b >= 0) {
                                downloaded++
                                onProgress(downloaded, total)
                            }
                            return b
                        }

                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            val n = super.read(b, off, len)
                            if (n > 0) {
                                downloaded += n
                                onProgress(downloaded, total)
                            }
                            return n
                        }
                    }
                    onExtracting()
                    TarGz.extract(counting, tmpDir, catalog.packFiles.toSet())
                }
            } catch (e: IOException) {
                if (cancelRequested) throw PackCancelledException()
                throw e
            } finally {
                activeCall = null
            }

            if (!marker.parentFile?.exists()!!) marker.parentFile?.mkdirs()
            packDir.deleteRecursively()
            if (!tmpDir.renameTo(packDir)) throw IOException("could not move pack into place")
            marker.writeText(commit.ifBlank { "unknown" })
            invalidateMarkerCache()
            Result.success(Unit)
        } catch (e: Throwable) {
            if (e !is PackCancelledException && e !is PackNoSpaceException && e !is PackMeteredException) {
                Timber.e(e, "ShaderPack: install failed")
            }
            tmpDir.deleteRecursively()
            Result.failure(e)
        }
    }

    /**
     * Aborts an in-flight download and removes the partial extraction (spec §4.2.4).
     * The in-flight [install] fails with [PackCancelledException]; the UI treats that as
     * a clean stop, not an error.
     */
    fun cancel() {
        cancelRequested = true
        activeCall?.cancel()
        tmpDir.deleteRecursively()
    }

    /** Removes the installed pack (files stay uninstalled, catalog remains browsable). */
    fun clear() {
        packDir.deleteRecursively()
        invalidateMarkerCache()
    }

    private fun isMeteredNetwork(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}

/** Pack installation state against the current catalog (spec §4.3). */
enum class PackStatus { NOT_INSTALLED, INSTALLED, UPDATE_AVAILABLE }

/** Pure pre-check decisions (spec §4.2.2/§4.2.3) — JVM-testable, no Android deps. */
object PackPrechecks {

    /** Headroom beyond tmp + final: extraction needs roughly 2× the pack size on disk. */
    const val HEADROOM_BYTES = 16L * 1024 * 1024

    fun requiredFreeBytes(packBytes: Long): Long = packBytes * 2 + HEADROOM_BYTES

    fun hasEnoughSpace(packBytes: Long, availableBytes: Long): Boolean =
        availableBytes >= requiredFreeBytes(packBytes)

    /** Metered networks require an explicit size disclosure before any byte moves. */
    fun needsMeteredConfirmation(isActiveNetworkMetered: Boolean): Boolean = isActiveNetworkMetered
}

/** Not enough free space for tmp + final pack (spec §4.2.2). */
class PackNoSpaceException(required: Long, available: Long) :
    IOException("not enough space: need ${required}B, have ${available}B")

/** Active network is metered and the user has not confirmed the download (spec §4.2.3). */
class PackMeteredException(val packBytes: Long) :
    IOException("metered network requires explicit confirmation")

/** User cancelled the in-flight download (spec §4.2.4) — a clean stop, not an error. */
class PackCancelledException : IOException("shader pack download cancelled")

/**
 * Minimal, safe tar.gz reader used to extract the shader pack.
 *
 * - Only regular files listed in [wanted] are written; everything else is skipped.
 * - Path traversal is rejected (absolute paths, `..` segments, escaping the dest root).
 * - Streams decompressed from a trusted (GitHub) tarball, but validated regardless.
 *
 * Pure JVM — unit-testable without Android.
 */
object TarGz {

    private const val BLOCK = 512L

    fun extract(input: InputStream, destDir: File, wanted: Set<String>, onEntry: (String) -> Unit = {}) {
        val destRoot = destDir.canonicalPath
        val src = BufferedInputStream(GZIPInputStream(input))
        val header = ByteArray(BLOCK.toInt())
        while (true) {
            readFully(src, header)
            if (header.all { it == 0.toByte() }) break // end-of-archive marker
            val name = cString(header, 0, 100)
            val size = octal(header, 124, 12)
            val type = header[156].toInt().toChar()

            if (type == '0' || type == '\u0000') {
                if (name in wanted && name.isNotEmpty() && !name.endsWith('/')) {
                    val target = File(destDir, name)
                    val canonical = target.canonicalPath
                    if (!canonical.startsWith(destRoot + File.separator)) {
                        throw IOException("tar path traversal rejected: $name")
                    }
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out -> copyN(src, out, size) }
                    onEntry(name)
                } else {
                    skipN(src, size)
                }
            } else {
                skipN(src, size) // directories, links, pax headers, ...
            }
            skipN(src, (BLOCK - (size % BLOCK)) % BLOCK)
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw IOException("truncated tar stream")
            off += n
        }
    }

    private fun copyN(input: InputStream, out: FileOutputStream, size: Long) {
        var remaining = size
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) throw IOException("truncated tar entry")
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun skipN(input: InputStream, size: Long) {
        var remaining = size
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) throw IOException("truncated tar stream")
            remaining -= n
        }
    }

    private fun cString(buf: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && buf[end] != 0.toByte()) end++
        return String(buf, off, end - off, Charsets.UTF_8)
    }

    private fun octal(buf: ByteArray, off: Int, len: Int): Long {
        var value = 0L
        var i = off
        while (i < off + len && buf[i] != 0.toByte()) {
            val c = buf[i].toInt().toChar()
            if (c < '0' || c > '7') break
            value = value * 8 + (c - '0')
            i++
        }
        return value
    }
}
