package io.github.capstasher.gako.editor

import android.graphics.Typeface
import java.io.File

/** Fonts for the text tool: the phone's own (minus script-only fallbacks) and ones the user added. */
object Fonts {
    class Entry(val name: String, val path: String)

    private val typefaces = HashMap<String, Typeface?>()

    /** A font file as a Typeface, or null if it isn't a font. */
    fun load(path: String): Typeface? = synchronized(typefaces) {
        typefaces.getOrPut(path) { if (isFont(path)) runCatching { Typeface.createFromFile(path) }.getOrNull() else null }
    }

    /** Typeface.createFromFile quietly returns the default font for junk; Font.Builder actually checks. */
    fun isFont(path: String): Boolean = runCatching { android.graphics.fonts.Font.Builder(File(path)).build() }.isSuccess

    /** Forget a cached face (a file was replaced). */
    fun forget(path: String) = synchronized(typefaces) { typefaces.remove(path) }

    /** "DancingScript" → "Dancing Script". */
    fun pretty(s: String) = s.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").replace('_', ' ')

    fun system(): List<Entry> {
        val files = listOf("/system/fonts", "/product/fonts", "/system/product/fonts")
            .flatMap { File(it).listFiles()?.toList().orEmpty() }
        val byFamily = LinkedHashMap<String, File>()
        for (f in files.sortedBy { it.name }) {
            if (f.extension.lowercase() !in setOf("ttf", "otf")) continue
            val base = f.nameWithoutExtension
            val family = base.substringBefore('-')
            val style = base.substringAfter('-', "")
            // Noto's per-script fonts are fallbacks, not choices; emoji and the clock face aren't text fonts.
            if (family.startsWith("Noto") && family != "NotoSerif" && family != "NotoSans") continue
            if (family == "AndroidClock" || family.contains("Emoji")) continue
            if (style.isNotEmpty() && style !in setOf("Regular", "VF")) continue
            byFamily.putIfAbsent(family, f)
        }
        return byFamily.map { (family, f) -> Entry(pretty(family), f.path) }
    }

    fun userDir(filesDir: File) = File(filesDir, "fonts").apply { mkdirs() }

    fun user(filesDir: File): List<Entry> = userDir(filesDir).listFiles().orEmpty()
        .filter { it.extension.lowercase() in setOf("ttf", "otf") }
        .sortedBy { it.name.lowercase() }
        .map { Entry(pretty(it.nameWithoutExtension.substringBefore('-')), it.path) }
}
