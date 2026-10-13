package com.omnifusion.mobile

import org.json.JSONArray
import org.json.JSONObject

/**
 * Capability-based, local task router.
 * Model capabilities come from the provider catalog, NOT from guessing by model name.
 * Only models proven zero-price by both text token price fields enter the free pool.
 */
internal object ModelRouter {
    data class Model(
        val id: String,
        val free: Boolean,
        val input: Set<String>,
        val output: Set<String>
    ) {
        val supportsVision get() = input.contains("image")
        val supportsImageGeneration get() = output.contains("image")
        val supportsText get() = output.contains("text") || output.isEmpty()
    }

    enum class Task { TEXT, CODE, VISION, IMAGE_GEN }

    fun taskOf(prompt: String, attachedImage: Boolean = false): Task {
        if(attachedImage) return Task.VISION
        val q=prompt.lowercase()
        return when {
            listOf("生成图片","生图","画一张","画一幅","生成插画","生成海报","draw an image","generate an image").any { q.contains(it) } -> Task.IMAGE_GEN
            listOf("代码","编程","kotlin","python","javascript","github","debug","修复bug","cad").any { q.contains(it) } -> Task.CODE
            else -> Task.TEXT
        }
    }

    fun fromCatalog(data: JSONArray): List<Model> {
        val result=ArrayList<Model>()
        for(i in 0 until data.length()) {
            val entry=data.optJSONObject(i) ?: continue
            val id=entry.optString("id")
            if(id.isBlank()) continue
            val pricing=entry.optJSONObject("pricing")
            val free=pricing?.optString("prompt")?.toDoubleOrNull()==0.0 &&
                pricing.optString("completion").toDoubleOrNull()==0.0
            val architecture=entry.optJSONObject("architecture")
            fun modalities(key:String):Set<String> {
                val arr=architecture?.optJSONArray(key) ?: return emptySet()
                return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }.toSet()
            }
            result.add(Model(id,free,modalities("input_modalities"),modalities("output_modalities")))
        }
        return result
    }

    /** Explicit model lock always wins. Auto routing never silently spends money. */
    fun choose(task:Task, catalog:List<Model>, locked:String?=null, excluded:Set<String> = emptySet()):String? {
        if(!locked.isNullOrBlank() && locked!="openrouter/free") return locked
        val candidates=catalog.filter { it.free && it.id !in excluded && when(task) {
            Task.IMAGE_GEN -> it.supportsImageGeneration
            Task.VISION -> it.supportsVision && it.supportsText
            else -> it.supportsText
        } }
        val priority=when(task) {
            Task.TEXT,Task.CODE -> listOf("deepseek/","qwen/","google/","meta-llama/")
            Task.VISION -> listOf("google/","qwen/","openai/")
            Task.IMAGE_GEN -> listOf("openai/","black-forest-labs/","google/")
        }
        return candidates.minByOrNull { candidate ->
            val rank=priority.indexOfFirst { candidate.id.startsWith(it) }
            if(rank<0) Int.MAX_VALUE else rank
        }?.id
    }

    fun imageModels(catalog:List<Model>):List<Model> = catalog.filter { it.supportsImageGeneration }
}
