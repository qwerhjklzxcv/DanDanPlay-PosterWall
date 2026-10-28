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
 * - 开放平台的 `搜索` 配额最小（100/天），而季度列表接口一次返回一整季（含海报/评分/首播日期）；
 * - 拿到目录后本地做标题相似度匹配，命中就不必再调搜索接口。
 *
 * 两级加载：
 * - [ensureLoaded]：最近 8 个季度（覆盖在追 / 补番的大部分）；
 * - [ensureYearLoaded]：某部番在目录里没命中、但解析出了年份时，按需只拉那一年的 4 个季度。
 *   老番很多的库不会一次性拉 20 年，只拉实际用到的那几年，且加载过的年份会落盘。
 *
 * 失败即降级：接口被拒（403）或配额用尽（429）时目录为空或部分为空，
 * 刮削会自动回到「标题搜索」的原有逻辑，不影响功能。
 */
object AnimeCatalog {

    private const val CACHE_FILE = "catalog.json"
    private const val RECENT_QUARTERS = 8
    private const val MAX_ENTRIES = 4000

    private const val RECENT_SEASON_TTL = 24L * 60 * 60 * 1000
    private const val OLD_SEASON_TTL = 30L * 24 * 60 * 60 * 1000

    private const val MIN_SCORE = 60

    /** animeId -> 番剧（按插入顺序保留，超出上限时淘汰最早的） */
    private val entries = LinkedHashMap<Int, AnimeData>()

    /** "2024-10" -> 拉取时间 */
    private val loadedSeasons = HashMap<String, Long>()

    private var diskLoaded = false

    val size: Int
        get() = entries.size

    /**
     * 最近 8 个季度（只补缺失/过期的）。
     */
    suspend fun ensureLoaded(onProgress: suspend (String) -> Unit): Boolean {
        return ensureSeasons(recentSeasons(RECENT_QUARTERS), onProgress)
    }

    /**
     * 按年份补拉：只拉没加载过的季度。返回目录是否可用。
     */
    suspend fun ensureYearLoaded(year: Int, onProgress: suspend (String) -> Unit): Boolean {
        if (year < 1950 || year > 2100) return entries.isNotEmpty()

        val seasons = listOf(year to 1, year to 4, year to 7, year to 10)
        if (seasons.all { isSeasonFresh(it.first, it.second) }) return entries.isNotEmpty()

        return ensureSeasons(seasons, onProgress)
    }

    /**
     * 在目录里找最像的一部。相似度不够返回 null，交给搜索接口兜底。
     */
    fun find(query: String, season: Int): AnimeData? {
        if (entries.isEmpty() || query.isBlank()) return null

        var best: AnimeData? = null
        var bestScore = 0

        entries.values.forEach { anime ->
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

    // ------------------------------------------------------------------ 拉取

    private suspend fun ensureSeasons(
        seasons: List<Pair<Int, Int>>,
        onProgress: suspend (String) -> Unit
    ): Boolean {
        readFromDisk()

        val pending = seasons.filter { !isSeasonFresh(it.first, it.second) }
        if (pending.isEmpty()) return entries.isNotEmpty()

        onProgress("加载番剧目录…")
        var fetched = false

        for ((year, month) in pending) {
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
                if (anime.animeId > 0) {
                    entries[anime.animeId] = anime
                    // 部分条目带 searchKeyword（常用罗马音/别名），直接进别名索引
                    ScrapeCacheStore.putAlias(anime.searchKeyword, anime.animeId)
                }
            }
            loadedSeasons[seasonKey(year, month)] = System.currentTimeMillis()
            fetched = true
        }

        if (fetched) {
            trim()
            persist()
        }
        return entries.isNotEmpty()
    }

    // ------------------------------------------------------------------ 存储

    private fun seasonKey(year: Int, month: Int) = "$year-$month"

    private fun isSeasonFresh(year: Int, month: Int): Boolean {
        val time = loadedSeasons[seasonKey(year, month)] ?: return false
        val ttl = if (year >= Calendar.getInstance().get(Calendar.YEAR) - 1) {
            RECENT_SEASON_TTL
        } else {
            OLD_SEASON_TTL
        }
        return System.currentTimeMillis() - time < ttl
    }

    private fun trim() {
        while (entries.size > MAX_ENTRIES) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
    }

    private fun cacheFile(): File {
        val dir = File(BaseApplication.getAppContext().filesDir, "poster_wall")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, CACHE_FILE)
    }

    private fun readFromDisk() {
        if (diskLoaded) return
        diskLoaded = true

        val file = cacheFile()
        if (!file.exists()) return

        try {
            val root = JSONObject(file.readText())

            val items = root.optJSONArray("items") ?: JSONArray()
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val anime = AnimeData(
                    animeId = item.optInt("animeId"),
                    animeTitle = item.optString("animeTitle"),
                    imageUrl = item.optString("imageUrl").takeIf { it.isNotBlank() },
                    searchKeyword = item.optString("searchKeyword").takeIf { it.isNotBlank() },
                    rating = item.optDouble("rating"),
                    startDate = item.optString("startDate").takeIf { it.isNotBlank() }
                )
                if (anime.animeId > 0) entries[anime.animeId] = anime
            }

            val seasons = root.optJSONObject("seasons") ?: JSONObject()
            seasons.keys().forEach { key ->
                val time = seasons.optLong(key)
                if (time > 0) loadedSeasons[key] = time
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun persist() {
        try {
            val root = JSONObject()

            root.put("items", JSONArray().apply {
                entries.values.forEach { anime ->
                    put(JSONObject().apply {
                        put("animeId", anime.animeId)
                        put("animeTitle", anime.animeTitle ?: "")
                        put("imageUrl", anime.imageUrl ?: "")
                        put("searchKeyword", anime.searchKeyword ?: "")
                        put("rating", anime.rating)
                        put("startDate", anime.startDate ?: "")
                    })
                }
            })

            root.put("seasons", JSONObject().apply {
                loadedSeasons.forEach { (key, time) -> put(key, time) }
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
