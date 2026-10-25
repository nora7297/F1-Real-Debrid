package com.f1debrid.core.reddit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

@Serializable
data class RedditPost(
    val id: String = "",
    val title: String = "",
    val selftext: String = "",
    val url: String? = null,
    @SerialName("link_flair_text") val linkFlairText: String? = null,
    val author: String? = null,
    @SerialName("created_utc") val createdUtc: Double = 0.0,
    val permalink: String = "",
    /** -1 when unknown (RSS feeds do not say). */
    @SerialName("num_comments") val numComments: Int = -1,
    val stickied: Boolean = false,
) {
    val createdMillis: Long get() = (createdUtc * 1000).toLong()
    val fullUrl: String get() = "https://www.reddit.com$permalink"
}

data class RedditPage(val posts: List<RedditPost>, val after: String?)

/** A comment on a post; uploaders often post their magnet links this way. */
data class RedditComment(val author: String?, val body: String)

object RedditJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun parseListing(body: String): RedditPage {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return RedditPage(emptyList(), null)
        val data = root["data"] as? JsonObject ?: return RedditPage(emptyList(), null)
        val after = (data["after"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        val children = data["children"] as? JsonArray ?: JsonArray(emptyList())
        val posts = children.mapNotNull { child ->
            val obj = child as? JsonObject ?: return@mapNotNull null
            if ((obj["kind"] as? JsonPrimitive)?.contentOrNull != "t3") return@mapNotNull null
            val postData = obj["data"] as? JsonObject ?: return@mapNotNull null
            runCatching { json.decodeFromJsonElement(RedditPost.serializer(), postData) }.getOrNull()
        }
        return RedditPage(posts, after)
    }

    /** Every comment body in a Reddit comments response (any nesting depth). */
    fun extractCommentBodies(body: String): List<String> = extractComments(body).map { it.body }

    /** Every comment (author and body) in a Reddit comments response, at any nesting depth. */
    fun extractComments(body: String): List<RedditComment> {
        val out = ArrayList<RedditComment>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> {
                    if ((e["kind"] as? JsonPrimitive)?.contentOrNull == "t1") {
                        val data = e["data"]?.jsonObject
                        val text = (data?.get("body") as? JsonPrimitive)?.contentOrNull
                        val author = (data?.get("author") as? JsonPrimitive)?.contentOrNull
                        if (!text.isNullOrBlank()) out += RedditComment(author, text)
                    }
                    e.values.forEach(::walk)
                }
                is JsonArray -> e.forEach(::walk)
                else -> Unit
            }
        }
        runCatching { walk(json.parseToJsonElement(body)) }
        return out
    }
}
