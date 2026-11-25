package app.tether.core

import java.io.File

/** Reads files off one machine for the phone to show (an image or Markdown file Claude points at). */
interface RemoteFiles {
    /**
     * A local copy of [path] (absolute, or `~/` for the home folder). A copy read earlier is reused unless
     * [fresh]. Throws [FileTooBig] past [maxBytes], an IOException when the file can't be read.
     */
    suspend fun fetch(path: String, maxBytes: Long, fresh: Boolean = false): File
}

class FileTooBig(val size: Long, val max: Long) :
    RuntimeException("Too big to show here (${size / (1024 * 1024)} MB, the limit is ${max / (1024 * 1024)} MB)")
