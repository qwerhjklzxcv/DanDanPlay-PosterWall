package com.xyoye.poster_component.data

import com.xyoye.common_component.base.app.BaseApplication
import com.xyoye.common_component.network.repository.AnimeRepository
import com.xyoye.common_component.network.request.NetworkException
import com.xyoye.data_component.data.AnimeData
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * 季度番剧目录（本地索引）。
 *
 * 为什么要它：
 * - 开放平台的 `搜索` 配额最小（100/天），而 `番剧详情`（200/天）相对宽松；
 * - `秒/季度番剧` 接口 **一次返回一整季**（标题 / 海报 / 评分 / 首播日期），
 *   拉最近 8 个季度只要 8 次请求，却能覆盖绝大多数在追 / 补番的番剧；
 * - 拿到目录后**本地做标题相似度匹配**，命中就不必再调搜索接口。
 *
 * 失败即降级：接口被拒（403）或配额用尽（429）时目录为空，
 * 刮削会自动回到「标题搜索」的原有逻辑，不影响功能。
 */
object AnimeCatalog {

    private const val CACHE_FILE = "catalog.json"
    private const val TTL = 24L * 60 * 60 * 1000
    private const val QUARTER_COUNT = 8
    private const val MAX_ENTRIES = 4000
    private const val MIN_SCORE = 60

    private val entries = ArrayList<AnimeData>()
    private var loaded = false
    private var updateTime = 0L

    /** 目录里是否已经有数据（供 UI 判断是否需要提示） */
    val size: Int
        get() = entries.size

    /**
     * 确保目录可用：优先读磁盘缓存（24 小时内有效），过期则重新拉季度列表。
     */
    suspend fun ensureLoaded(onProgress: suspend (String) -> Unit): Boolean {
        if (loaded && System.currentTimeMillis() - updateTime < TTL) {
            return entries.isNotEmpty()
        }
        loaded = true
        readFromDisk()
        if (System.currentTimeMillis() - updateTime < TTL) {
            return entries.isNotEmpty()
        }

        onProgress("加载番剧目录…")
        val collected = LinkedHashMap<Int, AnimeData>()

        for ((year, month) in recentSeasons(QUARTER_COUNT)) {
            val data = try {
                AnimeRepository.getSeasonAnime(year.toString(), month.toString()).getOrNull()
            } catch (e: NetworkException) {
                // 配额用尽就别继续打了
                if (e.code == 429) break
                null
            } catch (e: Exception) {
                null
            } ?: continue

            data.bangumiList.forEach { anime ->
                if (anime.animeId > 0) collected[anime.animeId] = anime
            }
        }

        if (collected.isNotEmpty()) {
            entries.clear()
            entries.addAll(collected.values.take(MAX_ENTRIES))
            updateTime = System.currentTimeMillis()
            persist()
        }
        return entries.isNotEmpty()
    }

    /**
     * 在目录里找最像的一部。相似度不够返回 null，交给搜索接口兜底。
     */
    fun find(query: String, season: Int): AnimeData? {
        if (entries.isEmpty() || query.isBlank()) return null

        var best: AnimeData? = null
        var bestScore = 0

        entries.forEach { anime ->
            val title = anime.animeTitle.orEmpty()
            if (title.isBlank()) return@forEach

            var score = MediaTitleParser.similarity(query, title)
            if (score <= 0) return@forEach

            if (MediaTitleParser.normalize(query) == MediaTitleParser.normalize(title)) {
                score += 15
            }

            val marker = MediaTitleParser.seasonMarker(title)
            if (season > 1) {
                when {
                    marker == season -> score += 20
                    marker > 0 -> score -= 30
                    else -> score -= 10
                }
            } else if (marker > 1) {
                score -= 25
            }

            if (score > bestScore) {
                bestScore = score
                best = anime
            }
        }

        return if (bestScore >= MIN_SCORE) best else null
    }

    // ------------------------------------------------------------------ 存储

    private fun cacheFile(): File {
        val dir = File(BaseApplication.getAppContext().filesDir, "poster_wall")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, CACHE_FILE)
    }

    private fun readFromDisk() {
        if (entries.isNotEmpty()) return
        val file = cacheFile()
        if (!file.exists()) return

        try {
            val root = JSONObject(file.readText())
            updateTime = root.optLong("updateTime")
            val array = root.optJSONArray("items") ?: JSONArray()
            val list = ArrayList<AnimeData>(array.length())
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                list.add(
                    AnimeData(
                        animeId = item.optInt("animeId"),
                        animeTitle = item.optString("animeTitle"),
                        imageUrl = item.optString("imageUrl").takeIf { it.isNotBlank() },
                        rating = item.optDouble("rating"),
                        startDate = item.optString("startDate").takeIf { it.isNotBlank() }
                    )
                )
            }
            if (list.isNotEmpty()) {
                entries.clear()
                entries.addAll(list)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun persist() {
        try {
            val root = JSONObject()
            root.put("updateTime", updateTime)
            root.put("items", JSONArray().apply {
                entries.forEach { anime ->
                    put(JSONObject().apply {
                        put("animeId", anime.animeId)
                        put("animeTitle", anime.animeTitle ?: "")
                        put("imageUrl", anime.imageUrl ?: "")
                        put("rating", anime.rating)
                        put("startDate", anime.startDate ?: "")
                    })
                }
            })
            cacheFile().writeText(root.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 最近 [count] 个季度，从当前季度往回推 */
    private fun recentSeasons(count: Int): List<Pair<Int, Int>> {
        val calendar = Calendar.getInstance()
        var year = calendar.get(Calendar.YEAR)
        var month = ((calendar.get(Calendar.MONTH) + 1 - 1) / 3) * 3 + 1

        val seasons = ArrayList<Pair<Int, Int>>(count)
        repeat(count) {
            seasons.add(year to month)
            month -= 3
            if (month <= 0) {
                month += 12
                year -= 1
            }
        }
        return seasons
    }
}
