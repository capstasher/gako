package io.github.capstasher.gako.engine

import org.json.JSONObject
import kotlin.math.pow

enum class OpKind {
    Filter, Source, Composer;

    companion object {
        fun from(s: String) = when (s) {
            "source" -> Source
            "composer" -> Composer
            else -> Filter
        }
    }
}

data class OpInfo(
    val name: String,
    val title: String,
    val description: String,
    val categories: List<String>,
    val kind: OpKind,
)

/** One editable property of a GEGL operation. */
sealed interface Param {
    val name: String
    val label: String
    val description: String
    val unit: String?
    val default: Any?

    /** Pixel sizes have to shrink when previewing on a downscaled copy. */
    val scalesWithImage: Boolean get() = unit == "pixel-distance" || unit == "pixel-coordinate"

    data class Number(
        override val name: String,
        override val label: String,
        override val description: String,
        override val unit: String?,
        override val default: Double,
        val min: Double,
        val max: Double,
        val uiMin: Double,
        val uiMax: Double,
        val gamma: Double,
        val digits: Int,
        val isInteger: Boolean,
        val isSeed: Boolean,
    ) : Param {
        /** Slider position 0..1 to value, following GEGL's UI gamma like GIMP does. */
        fun fromSlider(t: Float): Double {
            val v = uiMin + (uiMax - uiMin) * t.toDouble().coerceIn(0.0, 1.0).pow(gamma)
            return if (isInteger) Math.round(v).toDouble() else v
        }

        fun toSlider(value: Double): Float {
            if (uiMax <= uiMin) return 0f
            val n = ((value - uiMin) / (uiMax - uiMin)).coerceIn(0.0, 1.0)
            return n.pow(1.0 / gamma).toFloat()
        }

        fun clamp(value: Double) = value.coerceIn(min, max)
    }

    data class Toggle(
        override val name: String,
        override val label: String,
        override val description: String,
        override val unit: String?,
        override val default: Boolean,
    ) : Param

    data class Choice(
        override val name: String,
        override val label: String,
        override val description: String,
        override val unit: String?,
        override val default: String?,
        val options: List<Pair<String, String>>,   // nick to label
    ) : Param

    data class Colour(
        override val name: String,
        override val label: String,
        override val description: String,
        override val unit: String?,
        override val default: String,             // #rrggbbaa
    ) : Param

    data class Text(
        override val name: String,
        override val label: String,
        override val description: String,
        override val unit: String?,
        override val default: String?,
    ) : Param
}

data class OpDescription(
    val name: String,
    val title: String,
    val description: String,
    val params: List<Param>,
    val unsupported: Int,
) {
    fun defaults(): Map<String, Any?> = params.associate { p ->
        p.name to when (p) {
            is Param.Number -> if (p.isInteger) p.default.toInt() else p.default
            else -> p.default
        }
    }

    companion object {
        fun parse(json: String): OpDescription {
            val o = JSONObject(json)
            val props = o.getJSONArray("properties")
            val params = mutableListOf<Param>()
            var unsupported = 0
            for (i in 0 until props.length()) {
                val p = props.getJSONObject(i)
                if (p.optStringOrNull("role") == "output-extent") continue
                val name = p.getString("name")
                val label = p.optStringOrNull("label") ?: name
                val desc = p.optStringOrNull("description") ?: ""
                val unit = p.optStringOrNull("unit")
                when (p.getString("type")) {
                    "double", "int", "seed" -> {
                        val isInt = p.getString("type") != "double"
                        val min = p.getDouble("min")
                        val max = p.getDouble("max")
                        var uiMin = p.optDouble("uiMin", min)
                        var uiMax = p.optDouble("uiMax", max)
                        if (!uiMin.isFinite() || uiMin < -1e6) uiMin = maxOf(min, -1000.0)
                        if (!uiMax.isFinite() || uiMax > 1e6) uiMax = minOf(max, 1000.0)
                        params += Param.Number(
                            name, label, desc, unit,
                            default = p.getDouble("default"),
                            min = min, max = max, uiMin = uiMin, uiMax = uiMax,
                            gamma = p.optDouble("uiGamma", 1.0).takeIf { it > 0 && it.isFinite() } ?: 1.0,
                            digits = p.optInt("digits", if (isInt) 0 else 2),
                            isInteger = isInt,
                            isSeed = p.getString("type") == "seed",
                        )
                    }
                    "boolean" -> params += Param.Toggle(name, label, desc, unit, p.getBoolean("default"))
                    "enum" -> {
                        val values = p.getJSONArray("values")
                        params += Param.Choice(
                            name, label, desc, unit, p.optStringOrNull("default"),
                            List(values.length()) {
                                val v = values.getJSONObject(it)
                                v.getString("nick") to (v.optStringOrNull("label") ?: v.getString("nick"))
                            },
                        )
                    }
                    "color" -> params += Param.Colour(name, label, desc, unit, p.optStringOrNull("default") ?: "#000000ff")
                    "string" -> params += Param.Text(name, label, desc, unit, p.optStringOrNull("default"))
                    else -> unsupported++
                }
            }
            return OpDescription(
                name = o.getString("name"),
                title = o.optStringOrNull("title") ?: o.getString("name"),
                description = o.optStringOrNull("description") ?: "",
                params = params,
                unsupported = unsupported,
            )
        }
    }
}
