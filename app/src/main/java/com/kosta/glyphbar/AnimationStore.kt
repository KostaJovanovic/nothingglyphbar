package com.kosta.glyphbar

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "GlyphStore"
private const val FILE_NAME = "custom_animations.json"

data class CustomAnimation(
    val name: String,
    val keys: Keyframes,
)

/**
 * Custom animations, persisted as JSON in the app's private files dir.
 *
 * Hand-rolled JSON rather than a serialization dependency: the schema is one
 * array of int arrays, and this keeps the build free of kapt/KSP.
 */
class AnimationStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    fun load(): List<CustomAnimation> {
        if (!file.exists()) return emptyList()
        return try {
            val root = JSONArray(file.readText())
            buildList {
                for (i in 0 until root.length()) {
                    val o = root.getJSONObject(i)
                    val framesJson = o.getJSONArray("frames")
                    val frames = buildList {
                        for (f in 0 until framesJson.length()) {
                            val arr = framesJson.getJSONArray(f)
                            add(IntArray(ZONE_COUNT) { z ->
                                arr.optInt(z, 0).coerceIn(0, MAX_LIGHT)
                            })
                        }
                    }
                    if (frames.isEmpty()) continue
                    add(
                        CustomAnimation(
                            name = o.optString("name", "Untitled"),
                            keys = Keyframes(
                                frames = frames,
                                stepMs = o.optInt("stepMs", 100).coerceIn(20, 2000),
                                loop = o.optBoolean("loop", true),
                            ),
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // A corrupt file shouldn't brick the app — surface nothing and move on.
            Log.e(TAG, "load failed", e)
            emptyList()
        }
    }

    fun save(list: List<CustomAnimation>) {
        try {
            val root = JSONArray()
            list.forEach { anim ->
                val frames = JSONArray()
                anim.keys.frames.forEach { f ->
                    val arr = JSONArray()
                    f.forEach { arr.put(it) }
                    frames.put(arr)
                }
                root.put(
                    JSONObject().apply {
                        put("name", anim.name)
                        put("stepMs", anim.keys.stepMs)
                        put("loop", anim.keys.loop)
                        put("frames", frames)
                    }
                )
            }
            file.writeText(root.toString())
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
        }
    }

    fun add(anim: CustomAnimation): List<CustomAnimation> {
        // Replace by name so re-saving an edited animation doesn't duplicate it.
        val next = load().filterNot { it.name.equals(anim.name, ignoreCase = true) } + anim
        save(next)
        return next
    }

    fun delete(name: String): List<CustomAnimation> {
        val next = load().filterNot { it.name == name }
        save(next)
        return next
    }
}
