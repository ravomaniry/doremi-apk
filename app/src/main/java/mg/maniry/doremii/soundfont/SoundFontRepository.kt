package mg.maniry.doremii.soundfont

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mg.maniry.doremii.R
import mg.maniry.doremii.editor.partition.VoiceTimbre
import mg.maniry.doremii.editor.sfplayer.SfPlayer
import mg.maniry.doremii.editor.sfplayer.VoicePatch
import mg.maniry.doremii.soundfont.db.DoremiDatabase
import mg.maniry.doremii.soundfont.db.PresetEntity
import mg.maniry.doremii.soundfont.db.SoundFontEntity
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

data class InstalledSoundFont(
    val id: String,
    val displayName: String,
    val fileName: String,
    val absolutePath: String,
    val sizeBytes: Long,
    val sourceUrl: String?,
    val bundled: Boolean,
    val active: Boolean,
    val presets: List<DiscoveredPreset>,
) {
    val canDelete: Boolean get() = !bundled && !active
}

data class DownloadProgress(
    val key: String,
    val label: String,
    val received: Long,
    val total: Long,
    val checking: Boolean,
)

data class DownloadFailure(
    val key: String,
    val label: String,
    val message: String,
)

data class SoundFontMessage(
    val id: Long,
    val text: String,
)

data class CuratedSoundFont(
    val name: String,
    val detail: String,
    val url: String,
)

class SoundFontRepository(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "soundfonts")
    private val partialDirectory = File(directory, ".partial")
    private val dao = DoremiDatabase.get(appContext).soundFonts()
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ ->
            postMessage(appContext.getString(R.string.soundfonts_download_failed))
        },
    )
    private val gate = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val calls = ConcurrentHashMap<String, Call>()
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    private val catalogData = MutableLiveData<List<InstalledSoundFont>>(emptyList())
    val catalog: LiveData<List<InstalledSoundFont>> = catalogData
    private val downloadsData = MutableLiveData<List<DownloadProgress>>(emptyList())
    val downloads: LiveData<List<DownloadProgress>> = downloadsData
    private val failuresData = MutableLiveData<List<DownloadFailure>>(emptyList())
    val failures: LiveData<List<DownloadFailure>> = failuresData
    private val failureLock = Any()
    private var failureSnapshot = emptyList<DownloadFailure>()
    private val messagesData = MutableLiveData<SoundFontMessage>()
    val messages: LiveData<SoundFontMessage> = messagesData
    private val messageIds = AtomicLong()

    @Volatile
    private var cache: List<InstalledSoundFont> = emptyList()

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            try {
                directory.mkdirs()
                partialDirectory.mkdirs()
                installBundledPiano()
                reload()
            } finally {
                if (!ready.isCompleted) ready.complete(Unit)
            }
        }
    }

    fun defaultTimbre(): VoiceTimbre {
        val font = cache.firstOrNull { it.active } ?: cache.firstOrNull { it.id == VoiceTimbre.PIANO_ID }
        if (font == null) return VoiceTimbre.piano()
        val preset = font.presets.firstOrNull { it.bank == 0 && it.program == 0 }
            ?: font.presets.firstOrNull()
        return VoiceTimbre(font.id, preset?.bank ?: 0, preset?.program ?: 0)
    }

    fun assignment(timbre: VoiceTimbre): VoicePatch {
        val font = resolveSongFont(if (timbre.legacyIndex != null) VoiceTimbre.PIANO_ID else timbre.soundFontId)
        return assignmentOnFont(font, timbre)
    }

    fun resolveSongFont(preferredId: String?): InstalledSoundFont? {
        ensureReady()
        return cache.firstOrNull { preferredId != null && it.id == preferredId }
            ?: cache.firstOrNull { it.active }
            ?: cache.firstOrNull { it.id == VoiceTimbre.PIANO_ID }
            ?: cache.firstOrNull()
    }

    fun assignmentOnFont(font: InstalledSoundFont?, timbre: VoiceTimbre): VoicePatch {
        ensureReady()
        val pianoPath = cache.firstOrNull { it.id == VoiceTimbre.PIANO_ID }?.absolutePath
            ?: File(directory, PIANO_FILE).absolutePath
        if (font == null) return VoicePatch(pianoPath, 0, 0)
        val (bank, program) = timbre.midiBankAndProgram()
        val match = font.presets.firstOrNull { it.bank == bank && it.program == program }
        if (match != null) return VoicePatch(font.absolutePath, match.bank, match.program)
        val fallback = font.presets.firstOrNull()
        return VoicePatch(font.absolutePath, fallback?.bank ?: 0, fallback?.program ?: 0)
    }

    fun rememberPresets(path: String, presets: List<DiscoveredPreset>) {
        if (presets.isEmpty()) return
        scope.launch {
            gate.withLock {
                val font = cache.firstOrNull { it.absolutePath == path } ?: return@withLock
                if (font.presets.isNotEmpty()) return@withLock
                dao.replacePresets(font.id, presets.map { it.toEntity(font.id) })
                reload()
            }
        }
    }

    fun download(url: String, displayName: String) {
        start()
        val key = url.trim()
        val scheme = Uri.parse(key).scheme
        if (scheme != "http" && scheme != "https") {
            message(R.string.soundfonts_invalid_url)
            return
        }
        if (cache.any { it.sourceUrl == key } || jobs[key]?.isActive == true) return
        clearFailure(key)
        val call = client.newCall(Request.Builder().url(key).build())
        calls[key] = call
        val job = scope.launch {
            val partial = File(partialDirectory, "${UUID.randomUUID()}.part")
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("empty body")
                    val fileName = soundFontName(response.request.url.encodedPath, response.header("Content-Disposition"))
                        ?: throw IOException("not a soundfont")
                    publish(key, displayName, 0, body.contentLength(), false)
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(16 * 1024)
                            var received = 0L
                            val total = body.contentLength()
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                received += count
                                if (received == total || received % (256 * 1024) < count) {
                                    publish(key, displayName, received, total, false)
                                }
                            }
                        }
                    }
                    publish(key, displayName, partial.length(), partial.length(), true)
                    val presets = SfPlayer.probe(partial.absolutePath) ?: throw IOException("probe failed")
                    gate.withLock {
                        val destination = uniqueFile(directory, fileName)
                        if (!partial.renameTo(destination)) {
                            partial.copyTo(destination, overwrite = true)
                            partial.delete()
                        }
                        val id = newId()
                        val active = dao.active() == null
                        dao.insert(
                            SoundFontEntity(
                                id = id,
                                displayName = displayName.ifBlank { displayNameFromFile(destination.name) },
                                fileName = destination.name,
                                sizeBytes = destination.length(),
                                sourceUrl = key,
                                bundled = false,
                                active = active,
                            )
                        )
                        dao.replacePresets(id, presets.map { it.toEntity(id) })
                        reload()
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                partial.delete()
                throw cancelled
            } catch (error: Exception) {
                partial.delete()
                if (coroutineContext[Job]?.isActive == false) return@launch
                fail(
                    key,
                    displayName,
                    when (error.message) {
                        "probe failed" -> R.string.soundfonts_invalid_file
                        "not a soundfont" -> R.string.soundfonts_invalid_url
                        else -> R.string.soundfonts_download_failed
                    },
                )
            } finally {
                calls.remove(key)
                clearProgress(key)
            }
        }
        jobs[key] = job
        job.invokeOnCompletion { jobs.remove(key, job) }
    }

    fun cancel(key: String) {
        calls[key]?.cancel()
        jobs[key]?.cancel()
    }

    fun import(uri: Uri) {
        start()
        val key = "import:${uri}"
        if (jobs[key]?.isActive == true) return
        clearFailure(key)
        val job = scope.launch {
            val partial = File(partialDirectory, "${UUID.randomUUID()}.part")
            val label = displayName(uri) ?: "SoundFont"
            try {
                publish(key, label, 0, -1, true)
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    partial.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IOException("unreadable")
                val fileName = soundFontName(label, null) ?: throw IOException("extension")
                val presets = SfPlayer.probe(partial.absolutePath) ?: throw IOException("probe failed")
                gate.withLock {
                    val destination = uniqueFile(directory, fileName)
                    if (!partial.renameTo(destination)) {
                        partial.copyTo(destination, overwrite = true)
                        partial.delete()
                    }
                    val id = newId()
                    dao.insert(
                        SoundFontEntity(
                            id = id,
                            displayName = displayNameFromFile(destination.name),
                            fileName = destination.name,
                            sizeBytes = destination.length(),
                            sourceUrl = null,
                            bundled = false,
                            active = dao.active() == null,
                        )
                    )
                    dao.replacePresets(id, presets.map { it.toEntity(id) })
                    reload()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                partial.delete()
                throw cancelled
            } catch (error: Exception) {
                partial.delete()
                fail(
                    key,
                    label,
                    if (error.message == "extension") R.string.soundfonts_invalid_url else R.string.soundfonts_import_failed,
                )
            } finally {
                clearProgress(key)
            }
        }
        jobs[key] = job
        job.invokeOnCompletion { jobs.remove(key, job) }
    }

    fun activate(id: String) {
        scope.launch {
            gate.withLock {
                if (dao.get(id) == null) return@withLock
                dao.setActive(id)
                reload()
            }
        }
    }

    fun delete(id: String) {
        scope.launch {
            gate.withLock {
                val font = dao.get(id) ?: return@withLock
                if (font.bundled || font.active) return@withLock
                dao.delete(id)
                File(directory, font.fileName).delete()
                reload()
            }
        }
    }

    private suspend fun installBundledPiano() {
        val destination = File(directory, PIANO_FILE)
        if (!destination.exists() || destination.length() == 0L) {
            destination.parentFile?.mkdirs()
            appContext.assets.open("soundfonts/$PIANO_FILE").use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val existing = dao.get(VoiceTimbre.PIANO_ID)
        if (existing == null) {
            dao.insert(
                SoundFontEntity(
                    id = VoiceTimbre.PIANO_ID,
                    displayName = "Piano",
                    fileName = PIANO_FILE,
                    sizeBytes = destination.length(),
                    sourceUrl = null,
                    bundled = true,
                    active = dao.active() == null,
                )
            )
        } else if (existing.sizeBytes != destination.length()) {
            dao.insert(existing.copy(sizeBytes = destination.length(), fileName = PIANO_FILE, bundled = true))
        }
        if (dao.presets(VoiceTimbre.PIANO_ID).isEmpty()) {
            val presets = SfPlayer.probe(destination.absolutePath).orEmpty().ifEmpty {
                listOf(DiscoveredPreset(0, 0, "Piano"))
            }
            dao.replacePresets(VoiceTimbre.PIANO_ID, presets.map { it.toEntity(VoiceTimbre.PIANO_ID) })
        }
    }

    private suspend fun reload() {
        val installed = dao.all().map { font ->
            val presets = dao.presets(font.id).map { DiscoveredPreset(it.bank, it.program, it.name) }
            InstalledSoundFont(
                id = font.id,
                displayName = font.displayName,
                fileName = font.fileName,
                absolutePath = File(directory, font.fileName).absolutePath,
                sizeBytes = font.sizeBytes,
                sourceUrl = font.sourceUrl,
                bundled = font.bundled,
                active = font.active,
                presets = presets,
            )
        }
        cache = installed
        catalogData.postValue(installed)
    }

    private fun publish(key: String, label: String, received: Long, total: Long, checking: Boolean) {
        val current = downloadsData.value.orEmpty().filterNot { it.key == key }
        downloadsData.postValue(current + DownloadProgress(key, label, received, total, checking))
    }

    private fun clearProgress(key: String) {
        downloadsData.postValue(downloadsData.value.orEmpty().filterNot { it.key == key })
    }

    private fun message(resId: Int) {
        postMessage(appContext.getString(resId))
    }

    private fun postMessage(text: String) {
        if (text.isBlank()) return
        messagesData.postValue(SoundFontMessage(messageIds.incrementAndGet(), text))
    }

    private fun fail(key: String, label: String, resId: Int) {
        val text = appContext.getString(resId)
        val next = synchronized(failureLock) {
            failureSnapshot = failureSnapshot.filterNot { it.key == key } +
                DownloadFailure(key, label.ifBlank { "SoundFont" }, text)
            failureSnapshot
        }
        failuresData.postValue(next)
        postMessage(text)
    }

    private fun clearFailure(key: String) {
        val next = synchronized(failureLock) {
            if (failureSnapshot.none { it.key == key }) return
            failureSnapshot = failureSnapshot.filterNot { it.key == key }
            failureSnapshot
        }
        failuresData.postValue(next)
    }

    private fun ensureReady() {
        start()
        if (!ready.isCompleted) {
            runBlocking { ready.await() }
        }
    }

    private fun displayName(uri: Uri): String? {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index)
                }
            }
        return uri.lastPathSegment
    }

    companion object {
        const val PIANO_FILE = "Piano.sf2"

        val curated = listOf(
            CuratedSoundFont(
                name = "Florestan Piano",
                detail = "Piano, .sf2, ~7.5 MB",
                url = "https://raw.githubusercontent.com/ZmeyKolbasnik/Instruments/master/000_Florestan_Piano.sf2",
            ),
            CuratedSoundFont(
                name = "Jeux d'orgues",
                detail = "Orgue, .sf2, ~3.8 MB",
                url = "https://raw.githubusercontent.com/nickbailey/smrg-live/master/config/includes.chroot/usr/local/share/soundfonts/jeux14.sf2",
            ),
            CuratedSoundFont(
                name = "Florestan Strings",
                detail = "Strings, .sf2, ~3 MB",
                url = "https://raw.githubusercontent.com/ZmeyKolbasnik/Instruments/master/040_Florestan_String_Quartet.sf2",
            ),
            CuratedSoundFont(
                name = "MuseScore General",
                detail = "GM feno, .sf3, ~40 MB",
                url = "https://huggingface.co/MuScriptor/assets/resolve/main/MuseScore_General.sf3",
            ),
            CuratedSoundFont(
                name = "FluidR3 GM",
                detail = "GM klasika, .sf3, ~23 MB",
                url = "https://raw.githubusercontent.com/Jacalz/fluid-soundfont/master/SF3/FluidR3.sf3",
            ),
        )

        fun isSoundFontUrl(value: String): Boolean {
            val uri = runCatching { Uri.parse(value.trim()) }.getOrNull() ?: return false
            if (uri.scheme != "http" && uri.scheme != "https") return false
            return looksLikeDirectLink(uri.path ?: value)
        }

        private fun looksLikeDirectLink(value: String): Boolean {
            val path = value.substringBefore('?').substringBefore('#')
            return path.endsWith(".sf2", true) || path.endsWith(".sf3", true)
        }

        private fun soundFontName(path: String, disposition: String?): String? {
            val fromHeader = disposition?.let { header ->
                Regex("""filename\*?=(?:UTF-8''|"|)?([^\";]+)""", RegexOption.IGNORE_CASE)
                    .find(header)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()
                    ?.trim('"')
            }
            val raw = URLDecoder.decode(fromHeader ?: path.substringAfterLast('/'), "UTF-8")
            if (!looksLikeDirectLink(raw)) return null
            val cleaned = raw.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
            return cleaned.ifBlank { null }
        }

        private fun displayNameFromFile(fileName: String): String {
            return fileName.substringBeforeLast('.')
                .replace('_', ' ')
                .replace('-', ' ')
                .trim()
                .ifBlank { "SoundFont" }
        }

        private fun uniqueFile(directory: File, fileName: String): File {
            val first = File(directory, fileName)
            if (!first.exists()) return first
            val dot = fileName.lastIndexOf('.')
            val stem = if (dot > 0) fileName.substring(0, dot) else fileName
            val extension = if (dot > 0) fileName.substring(dot) else ""
            var index = 2
            var candidate = File(directory, "$stem-$index$extension")
            while (candidate.exists()) {
                index++
                candidate = File(directory, "$stem-$index$extension")
            }
            return candidate
        }

        private fun newId(): String = "sf-" + UUID.randomUUID().toString().substring(0, 8)

        private fun DiscoveredPreset.toEntity(soundFontId: String) = PresetEntity(
            soundFontId = soundFontId,
            bank = bank,
            program = program,
            name = name,
        )
    }
}
