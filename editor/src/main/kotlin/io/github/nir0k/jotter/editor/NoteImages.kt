package io.github.nir0k.jotter.editor

import android.graphics.ImageDecoder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The longest side an image of a note is decoded at, in pixels. */
private const val MaxImageSide = 2048

/**
 * NoteImages decodes the images of the open note's attachment folder off the main thread: turned
 * as their EXIF orientation says, a GIF as its first frame, a large picture scaled down. Their
 * sizes and pictures are state, so the editor draws them once they are ready.
 *
 * @param assets The note's attachments; null draws no image.
 * @param scope Where decoding runs.
 */
class NoteImages(private val assets: NoteAssets?, private val scope: CoroutineScope) {
    private val sizes = mutableStateMapOf<String, Pair<Int, Int>>()
    private val pictures = mutableStateMapOf<String, ImageBitmap>()
    private val requested = HashSet<String>()

    /** Counts the images decoded so far, so what is drawn with their sizes is laid out again. */
    var version by mutableIntStateOf(0)
        private set

    /** Returns the size of an image's file in pixels, starting to decode it when first asked; null while unknown. */
    fun size(file: String): Pair<Int, Int>? {
        sizes[file]?.let { return it }
        load(file)
        return null
    }

    /** Returns the decoded picture of an image, or null while it decodes or when it cannot be. */
    fun picture(file: String): ImageBitmap? {
        pictures[file]?.let { return it }
        load(file)
        return null
    }

    /** Decodes an image once. */
    private fun load(file: String) {
        if (assets == null || !requested.add(file)) return
        scope.launch { withContext(Dispatchers.IO) { decode(file) }?.let { put(file, it) } }
    }

    /** Decodes images now, as an export needs them all before it draws. */
    suspend fun preload(files: Collection<String>) {
        for (file in files) {
            if (assets == null || !requested.add(file)) continue
            withContext(Dispatchers.IO) { decode(file) }?.let { put(file, it) }
        }
    }

    /** Keeps a decoded image. */
    private fun put(file: String, decoded: Pair<Pair<Int, Int>, ImageBitmap>) {
        sizes[file] = decoded.first
        pictures[file] = decoded.second
        version++
    }

    /** Decodes an image file of the note, scaled down to the longest side; null when it is not an image that decodes. */
    private fun decode(file: String): Pair<Pair<Int, Int>, ImageBitmap>? {
        val resource = assets?.attachment(file) ?: return null
        if (!resource.mimeType.startsWith("image/")) return null
        var sample = 1
        return try {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resource.file)) { decoder, info, _ ->
                val longest = maxOf(info.size.width, info.size.height)
                while (longest / sample > MaxImageSide) sample *= 2
                decoder.setTargetSampleSize(sample)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            (bitmap.width * sample to bitmap.height * sample) to bitmap.asImageBitmap()
        } catch (_: IOException) {
            null
        } catch (_: OutOfMemoryError) {
            // A picture too large for the memory left is drawn as its place only.
            null
        }
    }
}
