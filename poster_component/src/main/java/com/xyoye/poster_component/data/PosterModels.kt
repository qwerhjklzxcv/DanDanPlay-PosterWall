package com.xyoye.poster_component.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 海报墙数据模型。
 *
 * 一个 [PosterSeries] 对应「一个系列」：一个文件夹里的同一部番（可能含多个季度），
 * 或者一个明确按「标题 + 季度」聚合出来的文件集合。
 */
data class PosterSeries(
    /** 稳定 id，由 storageId + 文件夹路径 + 标题 + 季度 计算得出 */
    var id: String = "",
    var storageId: Int = 0,
    var storageName: String = "",
    /** 系列所在文件夹在媒体库中的绝对路径 */
    var folderPath: String = "",
    /** 从文件/文件夹名解析出的原始标题 */
    var rawTitle: String = "",
    var season: Int = 1,
    var year: Int = 0,
    var episodes: MutableList<PosterEpisode> = mutableListOf(),
    /** 刮削结果 */
    var animeId: Int = 0,
    var animeTitle: String? = null,
    var posterUrl: String? = null,
    var rating: Double = 0.0,
    var summary: String? = null,
    var matched: Boolean = false,
    var lastUpdate: Long = 0L,
    /** 运行时字段（不写缓存）：已看过的集数 / 最后看到的集号 */
    var watchedCount: Int = 0,
    var lastWatchedEpisode: Int = 0
) {

    val displayTitle: String
        get() = animeTitle?.takeIf { it.isNotBlank() } ?: rawTitle

    val episodeCount: Int
        get() = episodes.size

    val coverUrl: String?
        get() = posterUrl?.takeIf { it.isNotBlank() }

    val watchedText: String
        get() = if (watchedCount > 0) {
            "看到 $lastWatchedEpisode/$episodeCount"
        } else {
            ""
        }

    val infoText: String
        get() = buildList {
            if (year > 0) add(year.toString())
            if (season > 1) add("第${season}季")
            if (episodeCount > 0) add("$episodeCount 集")
            if (storageName.isNotBlank()) add(storageName)
        }.joinToString(" · ")

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("storageId", storageId)
        json.put("storageName", storageName)
        json.put("folderPath", folderPath)
        json.put("rawTitle", rawTitle)
        json.put("season", season)
        json.put("year", year)
        json.put("animeId", animeId)
        json.put("animeTitle", animeTitle ?: "")
        json.put("posterUrl", posterUrl ?: "")
        json.put("rating", rating)
        json.put("summary", summary ?: "")
        json.put("matched", matched)
        json.put("lastUpdate", lastUpdate)
        json.put("episodes", JSONArray().apply {
            episodes.forEach { episode ->
                put(JSONObject().apply {
                    put("name", episode.name)
                    put("path", episode.path)
                    put("episode", episode.episode)
                    put("size", episode.size)
                })
            }
        })
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): PosterSeries {
            val episodes = mutableListOf<PosterEpisode>()
            val array = json.optJSONArray("episodes") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                episodes.add(
                    PosterEpisode(
                        name = item.optString("name"),
                        path = item.optString("path"),
                        episode = item.optInt("episode"),
                        size = item.optLong("size")
                    )
                )
            }
            return PosterSeries(
                id = json.optString("id"),
                storageId = json.optInt("storageId"),
                storageName = json.optString("storageName"),
                folderPath = json.optString("folderPath"),
                rawTitle = json.optString("rawTitle"),
                season = json.optInt("season", 1),
                year = json.optInt("year"),
                episodes = episodes,
                animeId = json.optInt("animeId"),
                animeTitle = json.optString("animeTitle").takeIf { it.isNotBlank() },
                posterUrl = json.optString("posterUrl").takeIf { it.isNotBlank() },
                rating = json.optDouble("rating"),
                summary = json.optString("summary").takeIf { it.isNotBlank() },
                matched = json.optBoolean("matched"),
                lastUpdate = json.optLong("lastUpdate")
            )
        }
    }
}

data class PosterEpisode(
    var name: String = "",
    /** 文件在媒体库中的绝对路径 */
    var path: String = "",
    var episode: Int = 0,
    var size: Long = 0L
)
