package com.xyoye.poster_component.data

import com.xyoye.common_component.base.app.BaseApplication
import com.xyoye.data_component.data.AnimeData
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 刮削结果本地缓存（磁盘）。
 *
 * 三张表：
 * - `matches`：文件名 → 匹配到的 animeId（`/api/v2/match` 的结果），避免同一文件反复识别
 * - `keywords`：搜索关键词 → 番剧列表，避免同一关键词反复搜索
 * - `details`：animeId → 封面/评分/简介/多语言标题，**命中即跳过详情接口**
 *
 * 位置：`filesDir/poster_wall/scrape_cache.json`，删掉即复位。
 */
object ScrapeCacheStore {

    private const val CACHE_DIR = "poster_wall"
    private const val CACHE_FILE = "scrape_cache.json"

    private const val MATCH_TTL = 7L * 24 * 60 * 60 * 1000
    private const val KEYWORD_TTL = 3L * 24 * 60 * 60 * 1000
    private const val DETAIL_TTL = 14L * 24 * 60 * 60 * 1000

    private const val MAX_MATCHES = 800
    private const val MAX_KEYWORDS = 300
    private const val MAX_DETAILS = 400
    private const val MAX_ALIASES = 1500

    data class CachedMatch(
        val animeId: Int,
        val animeTitle: String?,
        val updateTime: Long
    )

    data class CachedDetail(
        val title: String?,
        val imageUrl: String?,
        val rating: Double,
        val summary: String?,
        val titles: List<PosterTitle>,
        val updateTime: Long
    )

    private val lock = Any()
    private var loaded = false

    private val matches = LinkedHashMap<String, CachedMatch>()
    private val keywords = LinkedHashMap<String, Pair<Long, List<AnimeData>>>()
    private val details = LinkedHashMap<Int, CachedDetail>()

    /** 归一化别名（罗马音 / 英文 / 中文别名）-> animeId */
    private val aliases = LinkedHashMap<String, Int>()

    private fun cacheFile(): File {
        val dir = File(BaseApplication.getAppContext().filesDir, CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, CACHE_FILE)
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true

        val file = cacheFile()
        if (!file.exists()) return

        try {
            val root = JSONObject(file.readText())
            val now = System.currentTimeMillis()

            root.optJSONObject("matches")?.let { node ->
                node.keys().forEach { key ->
                    val item = node.optJSONObject(key) ?: return@forEach
                    val time = item.optLong("time")
                    if (now - time > MATCH_TTL) return@forEach
                    matches[key] = CachedMatch(
                        animeId = item.optInt("animeId"),
                        animeTitle = item.optString("animeTitle").takeIf { it.isNotBlank() },
                        updateTime = time
                    )
                }
            }

            root.optJSONObject("keywords")?.let { node ->
                node.keys().forEach { key ->
                    val item = node.optJSONObject(key) ?: return@forEach
                    val time = item.optLong("time")
                    if (now - time > KEYWORD_TTL) return@forEach
                    val list = mutableListOf<AnimeData>()
                    val array = item.optJSONArray("items") ?: JSONArray()
                    for (index in 0 until array.length()) {
                        val anime = array.optJSONObject(index) ?: continue
                        list.add(
                            AnimeData(
                                animeId = anime.optInt("animeId"),
                                animeTitle = anime.optString("animeTitle"),
                                imageUrl = anime.optString("imageUrl").takeIf { it.isNotBlank() },
                                rating = anime.optDouble("rating"),
                                startDate = anime.optString("startDate").takeIf { it.isNotBlank() }
                            )
                        )
                    }
                    keywords[key] = time to list
                }
            }

            root.optJSONObject("details")?.let { node ->
                node.keys().forEach { key ->
                    val item = node.optJSONObject(key) ?: return@forEach
                    val time = item.optLong("time")
                    if (now - time > DETAIL_TTL) return@forEach

                    val titles = mutableListOf<PosterTitle>()
                    val array = item.optJSONArray("titles") ?: JSONArray()
                    for (index in 0 until array.length()) {
                        val title = array.optJSONObject(index) ?: continue
                        val text = title.optString("title")
                        if (text.isNotBlank()) {
                            titles.add(PosterTitle(title.optString("language"), text))
                        }
                    }

                    details[key.toIntOrNull() ?: return@forEach] = CachedDetail(
                        title = item.optString("title").takeIf { it.isNotBlank() },
                        imageUrl = item.optString("imageUrl").takeIf { it.isNotBlank() },
                        rating = item.optDouble("rating"),
                        summary = item.optString("summary").takeIf { it.isNotBlank() },
                        titles = titles,
                        updateTime = time
                    )
                }
            }

            root.optJSONObject("aliases")?.let { node ->
                node.keys().forEach { key ->
                    val animeId = node.optInt(key)
                    if (key.isNotBlank() && animeId > 0) aliases[key] = animeId
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun match(fileName: String): CachedMatch? = synchronized(lock) {
        ensureLoaded()
        val item = matches[fileName] ?: return@synchronized null
        if (System.currentTimeMillis() - item.updateTime > MATCH_TTL) {
            matches.remove(fileName)
            return@synchronized null
        }
        item
    }

    fun putMatch(fileName: String, animeId: Int, animeTitle: String?) = synchronized(lock) {
        ensureLoaded()
        matches[fileName] = CachedMatch(animeId, animeTitle, System.currentTimeMillis())
        trim(matches, MAX_MATCHES)
        persistLocked()
    }

    fun keyword(keyword: String): List<AnimeData>? = synchronized(lock) {
        ensureLoaded()
        val item = keywords[keyword] ?: return@synchronized null
        if (System.currentTimeMillis() - item.first > KEYWORD_TTL) {
            keywords.remove(keyword)
            return@synchronized null
        }
        item.second
    }

    fun putKeyword(keyword: String, animes: List<AnimeData>) = synchronized(lock) {
        if (animes.isEmpty()) return@synchronized
        ensureLoaded()
        keywords[keyword] = System.currentTimeMillis() to animes
        trim(keywords, MAX_KEYWORDS)
        persistLocked()
    }

    fun detail(animeId: Int): CachedDetail? = synchronized(lock) {
        ensureLoaded()
        val item = details[animeId] ?: return@synchronized null
        if (System.currentTimeMillis() - item.updateTime > DETAIL_TTL) {
            details.remove(animeId)
            return@synchronized null
        }
        item
    }

    fun putDetail(
        animeId: Int,
        title: String?,
        imageUrl: String?,
        rating: Double,
        summary: String?,
        titles: List<PosterTitle>
    ) = synchronized(lock) {
        if (animeId <= 0) return@synchronized
        ensureLoaded()
        details[animeId] = CachedDetail(
            title = title,
            imageUrl = imageUrl,
            rating = rating,
            summary = summary,
            titles = titles,
            updateTime = System.currentTimeMillis()
        )
        // 详情里的多语言标题（含罗马字）顺手进别名索引，下次同系列的罗马音文件名可离线命中
        putAliasLocked(title, animeId)
        titles.forEach { putAliasLocked(it.title, animeId) }
        trim(details, MAX_DETAILS)
        persistLocked()
    }

    // ------------------------------------------------------------------ 别名索引（罗马音 / 英文 / 别名）

    /**
     * 查别名：入参请先经过 [MediaTitleParser.normalize] 归一化。
     */
    fun aliasAnimeId(normalized: String): Int? = synchronized(lock) {
        if (normalized.isBlank()) return@synchronized null
        ensureLoaded()
        aliases[normalized]
    }

    fun putAlias(text: String?, animeId: Int) = synchronized(lock) {
        if (animeId <= 0) return@synchronized
        ensureLoaded()
        if (putAliasLocked(text, animeId)) {
            trim(aliases, MAX_ALIASES)
            persistLocked()
        }
    }

    private fun putAliasLocked(text: String?, animeId: Int): Boolean {
        if (text.isNullOrBlank() || animeId <= 0) return false
        val key = MediaTitleParser.normalize(text)
        if (key.length < 3) return false
        // 纯数字（集数、年份）没有区分度，不收录
        if (key.all { it.isDigit() }) return false
        aliases[key] = animeId
        return true
    }

    private fun <K, V> trim(map: LinkedHashMap<K, V>, max: Int) {
        while (map.size > max) {
            val oldest = map.keys.firstOrNull() ?: break
            map.remove(oldest)
        }
    }

    private fun persistLocked() {
        try {
            val root = JSONObject()

            root.put("matches", JSONObject().apply {
                matches.forEach { (key, value) ->
                    put(key, JSONObject().apply {
                        put("time", value.updateTime)
                        put("animeId", value.animeId)
                        put("animeTitle", value.animeTitle ?: "")
                    })
                }
            })

            root.put("keywords", JSONObject().apply {
                keywords.forEach { (key, value) ->
                    put(key, JSONObject().apply {
                        put("time", value.first)
                        put("items", JSONArray().apply {
                            value.second.forEach { anime ->
                                put(JSONObject().apply {
                                    put("animeId", anime.animeId)
                                    put("animeTitle", anime.animeTitle ?: "")
                                    put("imageUrl", anime.imageUrl ?: "")
                                    put("rating", anime.rating)
                                    put("startDate", anime.startDate ?: "")
                                })
                            }
                        })
                    })
                }
            })

            root.put("details", JSONObject().apply {
                details.forEach { (key, value) ->
                    put(key.toString(), JSONObject().apply {
                        put("time", value.updateTime)
                        put("title", value.title ?: "")
                        put("imageUrl", value.imageUrl ?: "")
                        put("rating", value.rating)
                        put("summary", value.summary ?: "")
                        put("titles", JSONArray().apply {
                            value.titles.forEach { title ->
                                put(JSONObject().apply {
                                    put("language", title.language)
                                    put("title", title.title)
                                })
                            }
                        })
                    })
                }
            })

            root.put("aliases", JSONObject().apply {
                aliases.forEach { (key, animeId) -> put(key, animeId) }
            })

            cacheFile().writeText(root.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
