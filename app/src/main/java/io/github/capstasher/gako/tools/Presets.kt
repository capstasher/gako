package io.github.capstasher.gako.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A saved brush: the tool and the settings that shape its strokes. */
data class BrushPreset(
    val name: String,
    val tool: Tool,
    val size: Float,
    val opacity: Float,
    val hardness: Float,
    val shape: BrushShape = BrushShape.Round,
    val angle: Float = 30f,
    val smoothing: Float = 0f,
    val builtIn: Boolean = false,
) {
    fun applyTo(o: ToolOptions) = o.copy(size = size, opacity = opacity, hardness = hardness, brushShape = shape,
        brushAngle = angle, smoothing = smoothing)

    fun toJson(): JSONObject = JSONObject().put("name", name).put("tool", tool.name).put("size", size.toDouble())
        .put("opacity", opacity.toDouble()).put("hardness", hardness.toDouble()).put("shape", shape.name)
        .put("angle", angle.toDouble()).put("smoothing", smoothing.toDouble())

    companion object {
        fun fromJson(o: JSONObject) = runCatching {
            BrushPreset(o.getString("name"), Tool.valueOf(o.getString("tool")), o.getDouble("size").toFloat(),
                o.getDouble("opacity").toFloat(), o.getDouble("hardness").toFloat(), BrushShape.valueOf(o.getString("shape")),
                o.optDouble("angle", 30.0).toFloat(), o.optDouble("smoothing", 0.0).toFloat())
        }.getOrNull()
    }
}

/** Built-in brushes plus the ones the user saved. */
class Presets(context: Context) {
    private val prefs = context.getSharedPreferences("presets", 0)

    val builtIns = listOf(
        BrushPreset("Ink pen", Tool.Paintbrush, 6f, 1f, 0.95f, smoothing = 0.4f, builtIn = true),
        BrushPreset("Soft brush", Tool.Paintbrush, 60f, 0.8f, 0.1f, builtIn = true),
        BrushPreset("Marker", Tool.Paintbrush, 28f, 0.6f, 0.9f, BrushShape.Square, 0f, builtIn = true),
        BrushPreset("Calligraphy", Tool.Paintbrush, 36f, 1f, 0.9f, BrushShape.Calligraphy, 35f, 0.5f, builtIn = true),
        BrushPreset("Pixel", Tool.Pencil, 1f, 1f, 1f, builtIn = true),
        BrushPreset("Airbrush", Tool.Airbrush, 120f, 0.5f, 0.2f, builtIn = true),
        BrushPreset("Soft eraser", Tool.Eraser, 80f, 1f, 0.15f, builtIn = true),
    )

    fun saved(): List<BrushPreset> = runCatching {
        val a = JSONArray(prefs.getString("saved", "[]"))
        List(a.length()) { BrushPreset.fromJson(a.getJSONObject(it)) }.filterNotNull()
    }.getOrDefault(emptyList())

    fun all() = saved() + builtIns

    fun save(p: BrushPreset) {
        val list = listOf(p) + saved().filter { it.name != p.name }
        prefs.edit().putString("saved", JSONArray(list.take(24).map { it.toJson() }).toString()).apply()
    }

    fun delete(name: String) {
        prefs.edit().putString("saved", JSONArray(saved().filter { it.name != name }.map { it.toJson() }).toString()).apply()
    }
}
