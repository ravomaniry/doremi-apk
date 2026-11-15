package mg.maniry.doremii.commonUtils


import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import mg.maniry.doremii.editor.partition.PartitionData
import mg.maniry.doremii.editor.viewModels.FileContent
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.Date
import java.util.Scanner


class FileManager {

    companion object {
        const val SOLFA_EXTENSION = ".doremi.json"
        private const val LEGACY_EXTENSION = ".drm"
        private const val authority = "mg.maniry.doremii.fileprovider"
        private val sep = File.separator
        private lateinit var appContext: Context
        private lateinit var solfaDir: File

        private fun buildSolfaPath(name: String) = "${solfaDir.absolutePath}$sep$name"

        fun solfaStem(name: String): String {
            val lower = name.lowercase()
            val suffix = when {
                lower.endsWith(SOLFA_EXTENSION) -> SOLFA_EXTENSION
                lower.endsWith(LEGACY_EXTENSION) -> LEGACY_EXTENSION
                else -> return name
            }
            return name.dropLast(suffix.length)
        }

        fun extensionOf(name: String): String {
            val lower = name.lowercase()
            return when {
                lower.endsWith(SOLFA_EXTENSION) -> SOLFA_EXTENSION
                lower.endsWith(LEGACY_EXTENSION) -> LEGACY_EXTENSION
                else -> SOLFA_EXTENSION
            }
        }

        fun isJsonSolfa(filename: String): Boolean =
            getFileFromName(filename).name.lowercase().endsWith(SOLFA_EXTENSION)

        fun initDir(context: Context) {
            appContext = context.applicationContext
            solfaDir = File(context.filesDir, "solfa")
        }

        fun listFiles(): List<SongFile> =
            solfaDir.listFiles { f -> f.isFile && f.name.lowercase().endsWith(SOLFA_EXTENSION) }
                ?.map { SongFile(solfaStem(it.name), it.lastModified()) }
                ?: emptyList()


        fun rename(oldName: String?, newName: String) {
            if (oldName != null && newName != "") {
                val src = getFileFromName(oldName)
                if (src.exists()) {
                    src.renameTo(File(buildSolfaPath(solfaStem(newName) + SOLFA_EXTENSION)))
                }
            }
        }


        fun write(filename: String?, data: String) = try {
            val file = getFileFromName(filename)
            FileOutputStream(file).run {
                write(data.toByteArray())
                flush()
                close()
            }
            Values.saved
        } catch (e: FileNotFoundException) {
            Values.saveError
        } catch (e: Exception) {
            Values.unknownErr
        }

        fun writeHtml(filename: String, data: String): String {
            return writeDownload(downloadName(filename, ".html"), "text/html", data.toByteArray())
        }

        fun newCacheFile(filename: String, extension: String): File {
            val dir = File(appContext.cacheDir, "exports")
            if (!dir.exists()) dir.mkdirs()
            return File(dir, downloadName(filename, extension))
        }

        fun writeDownload(displayName: String, mimeType: String, bytes: ByteArray): String {
            if (!::appContext.isInitialized) return Values.saveError
            val safeName = displayName.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
                .ifEmpty { "doremi" }
            val resolver = appContext.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            for (name in listOf(safeName, timestamped(safeName))) {
                val uri = insertDownload(collection, name, mimeType) ?: continue
                try {
                    resolver.openOutputStream(uri)?.use { stream ->
                        stream.write(bytes)
                    } ?: throw IOException("Unable to open download")
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                        null,
                        null
                    )
                    return Values.saved
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                }
            }
            return Values.saveError
        }

        fun getFileFromName(filename: String?) = when {
            filename == null -> File(buildSolfaPath("doremi$SOLFA_EXTENSION"))
            filename.contains('/') -> File(filename)
            else -> File(buildSolfaPath(solfaStem(filename) + extensionOf(filename)))
        }


        fun read(filename: String): FileContent {
            val file = getFileFromName(filename)
            if (!file.exists()) return FileContent(error = Values.notExisting)
            return try {
                FileContent(content = file.readText())
            } catch (e: Exception) {
                FileContent(error = Values.fileOpenError)
            }
        }

        fun delete(filename: String) = getFileFromName(filename).let {
            return@let if (it.exists()) {
                it.delete()
                Values.deleted
            } else {
                Values.notExisting
            }
        }


        fun share(filename: String, ctx: Context): String? {
            val file = getFileFromName(filename)
            if (!file.exists()) return Values.notExisting
            return try {
                val uri = FileProvider.getUriForFile(ctx, authority, file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = if (file.name.lowercase().endsWith(SOLFA_EXTENSION)) {
                        "application/json"
                    } else {
                        "application/octet-stream"
                    }
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newRawUri(file.name, uri)
                }
                ctx.startActivity(
                    Intent.createChooser(send, filename)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
                null
            } catch (e: android.content.ActivityNotFoundException) {
                Values.noSharingApp
            } catch (e: Exception) {
                Values.shareErr
            }
        }

        fun moveIntoDoremiDir(file: File? = null, path: String? = null): String? {
            return try {
                val src = when {
                    file != null -> file
                    path != null -> File(path)
                    else -> return null
                }
                if (!src.exists()) return null
                val placed = if (src.parentFile?.absolutePath == solfaDir.absolutePath) {
                    src
                } else {
                    val dest = getCopy(src.absolutePath)
                    if (!src.renameTo(dest)) return null
                    dest
                }
                val result = if (placed.name.lowercase().endsWith(LEGACY_EXTENSION)) {
                    migrateLegacyFile(solfaDir, placed) ?: return null
                } else {
                    placed
                }
                solfaStem(result.name)
            } catch (e: Exception) {
                null
            }
        }

        fun importContentUri(uri: Uri): String? {
            return try {
                val display = queryDisplayName(uri) ?: "imported$LEGACY_EXTENSION"
                val dest = getCopy(display)
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: return null
                val result = if (dest.name.lowercase().endsWith(LEGACY_EXTENSION)) {
                    migrateLegacyFile(solfaDir, dest) ?: return null
                } else {
                    dest
                }
                solfaStem(result.name)
            } catch (e: Exception) {
                null
            }
        }


        fun getCopy(name: String): File {
            val source = if (name.contains('/')) File(name) else File(name)
            val extension = extensionOf(source.name)
            val stem = solfaStem(source.name).ifBlank { "imported" }
            val destFile = File(solfaDir, stem + extension)
            if (!destFile.exists()) return destFile

            var dirName = source.parentFile?.name ?: ""
            if (dirName == "solfa") dirName = ""

            if (dirName != "") {
                val withDir = File(solfaDir, "$stem ($dirName)$extension")
                if (!withDir.exists()) return withDir
            }

            val prefix = if (dirName == "") "" else "$dirName - "
            var i = 1
            var copy = File(solfaDir, "$stem ($prefix$i)$extension")
            while (copy.exists()) {
                i++
                copy = File(solfaDir, "$stem ($prefix$i)$extension")
            }
            return copy
        }


        fun copyDemoFiles(assets: AssetManager) {
            if (!solfaDir.exists()) {
                try {
                    solfaDir.mkdirs()
                    assets.list("demo")?.forEach { name ->
                        assets.open("demo/$name").copyTo(FileOutputStream(getFileFromName(name)))
                    }
                } catch (e: Exception) {
                    println("Unable to copy demo files $e")
                }
            }
            migrateLegacySolfa()
        }

        fun migrateLegacySolfa() {
            try {
                if (!::solfaDir.isInitialized || !solfaDir.exists()) return
                migrateDirectory(solfaDir)
            } catch (e: Exception) {
                // A failed scan leaves the .drm files in place for the next launch.
            }
        }

        fun saveDownloadedSolfa(name: String, content: String): String? {
            return try {
                if (!::solfaDir.isInitialized) return null
                if (!solfaDir.exists() && !solfaDir.mkdirs()) return null
                if (content.trimStart().startsWith("{")) {
                    PartitionData().fromJson(content)
                    val stem = solfaStem(name).ifBlank { "imported" }
                    val dest = getCopy(stem)
                    if (!writeAtomically(dest, content)) return null
                    solfaStem(dest.name)
                } else {
                    val stem = solfaStem(name).ifBlank { "imported" }
                    val dest = getCopy(stem + LEGACY_EXTENSION)
                    if (!writeAtomically(dest, content)) return null
                    val json = migrateLegacyFile(solfaDir, dest) ?: return null
                    solfaStem(json.name)
                }
            } catch (e: Exception) {
                null
            }
        }

        internal fun migrateDirectory(dir: File) {
            if (!dir.isDirectory) return
            val legacy = dir.listFiles()?.filter {
                it.isFile && it.name.lowercase().endsWith(LEGACY_EXTENSION)
            } ?: return
            legacy.forEach { migrateLegacyFile(dir, it) }
        }

        internal fun migrateLegacyFile(dir: File, file: File): File? {
            if (!file.isFile) return null
            val stem = solfaStem(file.name).ifBlank { "imported" }
            val dest = uniqueFile(dir, stem, SOLFA_EXTENSION)
            return try {
                val data = PartitionData()
                data.parseRawString(readJoinedLines(file))
                if (!writeAtomically(dest, data.toJson())) return null
                file.delete()
                dest
            } catch (e: Exception) {
                File(dir, dest.name + ".tmp").delete()
                null
            }
        }

        internal fun uniqueFile(dir: File, stem: String, extension: String): File {
            val first = File(dir, stem + extension)
            if (!first.exists()) return first
            var i = 1
            while (true) {
                val candidate = File(dir, "$stem ($i)$extension")
                if (!candidate.exists()) return candidate
                i++
            }
        }

        private fun writeAtomically(dest: File, text: String): Boolean {
            val parent = dest.parentFile ?: return false
            val tmp = File(parent, dest.name + ".tmp")
            return try {
                tmp.writeText(text)
                val moved = tmp.renameTo(dest)
                if (!moved) tmp.delete()
                moved
            } catch (e: Exception) {
                tmp.delete()
                false
            }
        }

        private fun readJoinedLines(file: File): String {
            val content = StringBuilder()
            Scanner(file).use { scanner ->
                while (scanner.hasNextLine()) content.append(scanner.nextLine())
            }
            return content.toString()
        }

        private fun insertDownload(collection: Uri, name: String, mimeType: String): Uri? {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            return try {
                appContext.contentResolver.insert(collection, values)
            } catch (e: Exception) {
                null
            }
        }

        private fun downloadName(name: String, extension: String): String {
            val base = name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifEmpty { "doremi" }
            return base + extension
        }

        private fun timestamped(displayName: String): String {
            val dot = displayName.lastIndexOf('.')
            val stamp = Date().time
            return if (dot > 0) {
                displayName.substring(0, dot) + "-$stamp" + displayName.substring(dot)
            } else {
                "$displayName-$stamp"
            }
        }

        private fun queryDisplayName(uri: Uri): String? {
            appContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index)
                }
            }
            return uri.lastPathSegment
        }
    }
}
