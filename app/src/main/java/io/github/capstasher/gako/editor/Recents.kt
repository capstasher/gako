package io.github.capstasher.gako.editor

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import io.github.capstasher.gako.doc.Compositor
import io.github.capstasher.gako.doc.DocState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Recently opened and saved images, with thumbnails, for the start screen. */
class Recents(private val context: Context) {
    data class Item(val uri: Uri, val name: String, val time: Long, val thumb: File)

    private val prefs = context.getSharedPreferences("recents", 0)
    private val dir get() = File(context.filesDir, "recent-thumbs").apply { mkdirs() }

    fun list(): List<Item> = runCatching {
        val arr = JSONArray(prefs.getString("items", "[]"))
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Item(Uri.parse(o.getString("uri")), o.getString("name"), o.getLong("time"), File(o.getString("thumb")))
        }
    }.getOrDefault(emptyList())

    private fun save(items: List<Item>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("uri", it.uri.toString()).put("name", it.name).put("time", it.time).put("thumb", it.thumb.path)) }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    /** Remembers [uri] (keeping access to it if Android allows) with a thumbnail of [doc]. Off the main thread. */
    fun add(uri: Uri, name: String, doc: DocState) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val thumb = File(dir, "${uri.toString().hashCode().toUInt()}.png")
        runCatching {
            val flat = Compositor.flatten(doc)
            thumb.outputStream().use { Compositor.thumbnail(flat, 320).compress(Bitmap.CompressFormat.PNG, 90, it) }
        }
        val items = listOf(Item(uri, name, System.currentTimeMillis(), thumb)) + list().filter { it.uri != uri }
        items.drop(MAX).forEach { old -> old.thumb.delete() }
        save(items.take(MAX))
    }

    fun remove(uri: Uri) {
        val items = list()
        items.filter { it.uri == uri }.forEach { it.thumb.delete() }
        save(items.filter { it.uri != uri })
    }

    companion object {
        const val MAX = 12
    }
}
