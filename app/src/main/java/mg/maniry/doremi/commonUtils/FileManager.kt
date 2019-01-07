package mg.maniry.doremi.commonUtils


import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.lang.Exception
import java.util.*
import android.content.Intent
import android.content.res.AssetManager
import android.net.Uri
import mg.maniry.doremi.editor.viewModels.FileContent
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.uiThread


class FileManager {

    companion object {
        private const val doremi = "Doremi"
        private const val solfa = "solfa"
        private const val export = "export"
        private const val midi = "midi"
        private val sep = File.separator
        private var parentDir = File("${Environment.getExternalStorageDirectory().absoluteFile}$sep$doremi")
                .also { if (!it.exists()) it.mkdirs() }
        private val solfaDir = File("${parentDir.absolutePath}$sep$solfa")
        private val htmlDir = File("${parentDir.absolutePath}$sep$export")
                .also { if (!it.exists()) it.mkdirs() }
        private val solfaDirPath: String = solfaDir.absolutePath + sep
        private val htmlDirPath = htmlDir.absolutePath + sep
        private val midiExportDirPath = File("${parentDir.absolutePath}$sep$midi")
                .also { if (!it.exists()) it.mkdirs() }


        fun listFiles() = solfaDir
                .listFiles { f -> f.extension == "drm" }
                .map { it.name.replace(".drm", "") }.sorted()


        fun rename(oldName: String?, newName: String) {
            if (oldName != null && newName != "") {
                with(getFileFromName(oldName)) {
                    if (exists())
                        renameTo(File("$solfaDirPath$newName.drm"))
                }
            }
        }


        fun write(filename: String?, data: String) = try {
            FileOutputStream(getFileFromName(filename)).run {
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


        fun writeHtml(filename: String, data: String) {
            File(htmlDirPath).run {
                if (!exists())
                    mkdirs()
            }

            write("$htmlDirPath$filename.html", data)
        }


        fun getFileFromName(filename: String?) = when {
            filename == null -> File("${solfaDirPath}doremi.drm")
            filename.contains('/') -> File(filename)
            filename.endsWith(".drm") -> File("$solfaDirPath$filename")
            else -> File("$solfaDirPath$filename.drm")
        }


        fun read(filename: String): FileContent {
            val file = getFileFromName(filename)
            return if (file.exists()) {
                return try {
                    var content = ""
                    Scanner(file).run {
                        while (hasNextLine())
                            content += nextLine()
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


        fun share(filename: String, context: Context): String? {
            val file = getFileFromName(filename)
            return if (file.exists()) {
                return Intent().apply {
                    action = Intent.ACTION_SEND
                    type = "text/*"
                    putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file))
                }.let {
                    if (it.resolveActivity(context.packageManager) != null) {
                        context.startActivity(Intent.createChooser(it, filename))
                        null
                    } else {
                        Values.noSharingApp
                    }
                }
            } else {
                Values.notExisting
            }
        }


        fun importAllDoremiFiles(callback: (() -> Unit)? = null) {
            doAsync {
                val path = Environment.getExternalStorageDirectory().absolutePath + sep

                listOf("Download", "Bluetooth", "Xender", "Xender/other", "Documents", "Music/doremi").forEach { dirName ->
                    File("$path$dirName").also { dir ->
                        if (dir.exists())
                            dir.listFiles { f -> f.extension == "drm" }.forEach { file ->
                                moveIntoDoremiDir(file = file)
                            }
                    }
                }

                uiThread { callback?.invoke() }
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
                        if (src.parentFile.absolutePath + sep == solfaDirPath) {
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

            if (!destFile.exists())
                return destFile

            var dirName = getFileFromName(name).parentFile.name
            if (dirName == "solfa")
                dirName = ""

            val desFileName = destFile.name.replace(".drm", "")

            if (dirName != "") {
                getFileFromName("$desFileName ($dirName)").also {
                    if (!it.exists())
                        return it
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
                }
            }
        }


        fun getMidiExportFile(name: String): File {
            return File("${midiExportDirPath.absolutePath}$sep$name.mid")
        }
    }
}
