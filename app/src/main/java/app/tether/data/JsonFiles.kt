package app.tether.data

import android.util.Log
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** JSON configuration shared by every file-backed store in this package. */
internal val TetherJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = false
    isLenient = true
}

/** Small JSON documents in private storage with crash-safe (temp + fsync + rename) writes. */
internal object JsonFiles {
    private const val TAG = "TetherJsonFiles"

    fun <T> read(file: File, serializer: KSerializer<T>, fallback: T): T {
        if (!file.exists()) return fallback
        return try {
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) fallback else TetherJson.decodeFromString(serializer, text)
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't read ${file.name}; keeping a copy and starting fresh", e)
            runCatching { file.copyTo(File(file.parentFile, file.name + ".corrupt"), overwrite = true) }
            fallback
        }
    }

    fun <T> write(file: File, serializer: KSerializer<T>, value: T) {
        val text = TetherJson.encodeToString(serializer, value)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            // Some file systems don't support ATOMIC_MOVE; a plain replace is still better than nothing.
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
