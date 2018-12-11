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
import mg.maniry.doremi.viewModels.FileContent
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.uiThread


class FileManager {

    companion object {
        private val sep = File.separator
        private val parentDir: File = with(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)) {
            if (canRead() && canWrite()) this@with
            else Environment.getExternalStorageDirectory()
        }
        private val directory = File("${parentDir.absolutePath}${sep}doremi$sep")
        private val directoryPath: String = directory.absolutePath
        private val htmlDirPath = "$directoryPath${sep}export$sep"


        fun listFiles() = directory
                .listFiles { f -> f.extension == "drm" }
                .map { it.name.replace(".drm", "") }.sorted()


        fun rename(oldName: String?, newName: String) {
            if (oldName != null && newName != "") {
                with(getFileFromName(oldName)) {
                    if (exists())
                        renameTo(File("$directoryPath$sep$newName.drm"))
                }
            }
        }


        fun write(filename: String?, data: String) = try {
            FileOutputStream(getFileFromName(filename)).run {
                write(data.toByteArray())
                flush()
                close()
            }

            "Fichier enregistré"
        } catch (e: FileNotFoundException) {
            "Fichier introuvable :("

        } catch (e: Exception) {
            "Erreur inconnue :("
        }


        fun writeHtml(filename: String, data: String) {
            File(htmlDirPath).run { if (!exists()) mkdirs() }
            write("$htmlDirPath$filename.html", data)
        }


        fun getFileFromName(filename: String?) = when {
            filename == null -> File("$directoryPath${sep}doremi.drm")
            filename.contains('/') -> File(filename)
            filename.endsWith(".drm") -> File("$directoryPath$sep$filename")
            else -> File("$directoryPath$sep$filename.drm")
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
                    FileContent(error = e.toString())
                }

            } else {
                FileContent(error = "Le fichier n'existe pas")
            }
        }


        fun delete(filename: String) = getFileFromName(filename).let {
            return@let if (it.exists()) {
                it.delete()
                "Fichier supprimé"
            } else {
                "Le fichier n'existe pas."
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
                        "No sharing application found!"
                    }
                }
            } else {
                "No file specified!"
            }
        }


        fun importAllDoremiFiles(callback: () -> Unit) {
            doAsync {
                val path = Environment.getExternalStorageDirectory().absolutePath

                listOf("Download", "Bluetooth", "Xender", "Xender/other", "Documents").forEach { dirName ->
                    File("$path$sep$dirName").also { dir ->
                        if (dir.exists())
                            dir.listFiles { f -> f.extension == "drm" }.forEach { file ->
                                moveIntoDoremiDir(file = file)
                            }
                    }
                }

                uiThread { callback() }
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
                        if (src.parentFile.absolutePath == directoryPath)
                            src.name.replace(".drm", "")
                        else {
                            val copy = getCopy(src.absolutePath)
                            src.renameTo(copy)
                            copy.name.replace(".drm", "")
                        }
                    } else
                        null
                }
            } catch (e: Exception) {
                null
            }
        }


        private fun getCopy(name: String): File {
            val destFile = getFileFromName(getFileFromName(name).name)

            if (!destFile.exists())
                return destFile

            val dirName = getFileFromName(name).parentFile.name
            val desFileName = destFile.name.replace(".drm", "")

            getFileFromName("$desFileName ($dirName)").also {
                if (!it.exists())
                    return it
            }

            var i = 1
            var copy = getFileFromName("$desFileName ($dirName - $i)")
            while (copy.exists()) {
                i++
                copy = getFileFromName("$desFileName ($dirName - $i)")
            }

            return copy
        }


        fun copyDemoFiles(assets: AssetManager) {
            if (!directory.exists()) {
                try {
                    directory.mkdirs()
                    assets.list("demo")?.forEach { name ->
                        assets.open("demo/$name").copyTo(FileOutputStream(getFileFromName(name)))
                    }
                } catch (e: Exception) {
                }
            }
        }
    }
}