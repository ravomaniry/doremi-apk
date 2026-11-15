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
import mg.maniry.doremii.editor.viewModels.FileContent
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.Date
import java.util.Scanner


class FileManager {

    companion object {
        private const val authority = "mg.maniry.doremii.fileprovider"
        private val sep = File.separator
        private lateinit var appContext: Context
        private lateinit var solfaDir: File

        private fun buildSolfaPath(name: String) = "${solfaDir.absolutePath}$sep$name"

        fun initDir(context: Context) {
            appContext = context.applicationContext
            solfaDir = File(context.filesDir, "solfa")
        }

        fun listFiles(): List<SongFile> =
            solfaDir.listFiles { f -> f.extension == "drm" }
                ?.map { SongFile(it.name.removeSuffix(".drm"), it.lastModified()) }
                ?: emptyList()


        fun rename(oldName: String?, newName: String) {
            if (oldName != null && newName != "") {
                with(getFileFromName(oldName)) {
                    if (exists()) renameTo(File(buildSolfaPath("$newName.drm")))
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
            filename == null -> File(buildSolfaPath("doremi.drm"))
            filename.contains('/') -> File(filename)
            filename.endsWith(".drm") -> File(buildSolfaPath(filename))
            else -> File(buildSolfaPath("$filename.drm"))
        }


        fun read(filename: String): FileContent {
            val file = getFileFromName(filename)
            return if (file.exists()) {
                return try {
                    var content = ""
                    Scanner(file).run {
                        while (hasNextLine()) content += nextLine()
                    }
                    FileContent(content = content)

                } catch (e: Exception) {
                    FileContent(error = Values.fileOpenError)
                }

            } else {
                FileContent(error = Values.notExisting)
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
                    type = "application/octet-stream"
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
                when {
                    file != null -> file
                    path != null -> File(path)
                    else -> null
                }?.let { src ->
                    if (src.exists()) {
                        if (src.parentFile?.absolutePath == solfaDir.absolutePath) {
                            src.name.replace(".drm", "")
                        } else {
                            val copy = getCopy(src.absolutePath)
                            src.renameTo(copy)
                            copy.name.replace(".drm", "")
                        }
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                null
            }
        }

        fun importContentUri(uri: Uri): String? {
            return try {
                val display = queryDisplayName(uri) ?: "imported.drm"
                val base = display.removeSuffix(".drm").removeSuffix(".DRM")
                val dest = getCopy(base)
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: return null
                dest.name.removeSuffix(".drm")
            } catch (e: Exception) {
                null
            }
        }


        fun getCopy(name: String): File {
            val destFile = getFileFromName(getFileFromName(name).name)

            if (!destFile.exists()) return destFile

            var dirName = getFileFromName(name).parentFile?.name ?: ""
            if (dirName == "solfa") dirName = ""

            val desFileName = destFile.name.replace(".drm", "")

            if (dirName != "") {
                getFileFromName("$desFileName ($dirName)").also {
                    if (!it.exists()) return it
                }
            }

            dirName = if (dirName == "") "" else "$dirName - "
            var i = 1
            var copy = getFileFromName("$desFileName ($dirName$i)")

            while (copy.exists()) {
                i++
                copy = getFileFromName("$desFileName ($dirName$i)")
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
