package mg.maniry.doremii.commonUtils


import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.lang.Exception
import java.util.*
import android.content.Intent
import android.content.res.AssetManager
import android.support.v4.content.FileProvider
import mg.maniry.doremii.editor.viewModels.FileContent


class FileManager {

    companion object {
        private const val authority = "mg.maniry.doremi"
        private val sep = File.separator
        private lateinit var rootDir: File
        private lateinit var solfaDir: File
        private val exportDir =
            "${Environment.getExternalStorageDirectory().absoluteFile}${sep}${Environment.DIRECTORY_DOWNLOADS}$sep"
        private val instrumentFilePath get() = File(rootDir, "instrument.txt").absolutePath

        private fun buildSolfaPath(name: String) = "${solfaDir.absolutePath}$sep$name"

        fun initDir(context: Context) {
            rootDir = context.filesDir
            solfaDir = File(context.filesDir, "solfa")
        }

        fun listFiles() =
            solfaDir.listFiles { f -> f.extension == "drm" }?.map { it.name.replace(".drm", "") }
                ?.sorted()


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

        fun createExportFilePath(filename: String, extension: String): String {
            // It's possible that the app does not have permission to write to the file
            val defaultFile = File("$exportDir$filename$extension")
            if (defaultFile.exists() || defaultFile.createNewFile()) {
                return defaultFile.absolutePath
            }
            return "$exportDir$filename${Date().time}$extension"
        }

        fun writeHtml(filename: String, data: String): String {
            return write(createExportFilePath(filename, ".html"), data)
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

        fun redInstrumentName(): String {
            val content = read(instrumentFilePath)
            return if (content.content == "") "Piano" else content.content
        }

        fun saveInstrumentName(value: String) {
            write(instrumentFilePath, value)
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
            return if (file.exists()) {
                try {
                    val uri = FileProvider.getUriForFile(ctx, authority, file)
                    ctx.grantUriPermission(authority, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    return Intent().apply {
                        action = Intent.ACTION_SEND
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                    }.let {
                        if (it.resolveActivity(ctx.packageManager) != null) {
                            ctx.startActivity(Intent.createChooser(it, filename))
                            null
                        } else {
                            Values.noSharingApp
                        }
                    }
                } catch (e: Exception) {
                    Values.shareErr
                }
            } else {
                Values.notExisting
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
                        if (src.parentFile.absolutePath == solfaDir.absolutePath) {
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


        fun getCopy(name: String): File {
            val destFile = getFileFromName(getFileFromName(name).name)

            if (!destFile.exists()) return destFile

            var dirName = getFileFromName(name).parentFile.name
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
                    attemptToCopyFromDoremiDir()
                } catch (e: Exception) {
                    println("Unable to copy demo files $e")
                }
            }
        }

        fun attemptToCopyFromDoremiDir() {
            try {
                val dir = File("${Environment.getExternalStorageDirectory()}${sep}Doremi", "solfa")
                val files = dir.listFiles()
                files?.forEach {
                    it.copyTo(getFileFromName(it.nameWithoutExtension))
                }
            } catch (e: Throwable) {
                println("Unable to copy form Doremi external storage dir $e")
            }
        }
    }
}
