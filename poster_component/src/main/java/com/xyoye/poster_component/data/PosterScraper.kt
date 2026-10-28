package com.xyoye.poster_component.data

import com.xyoye.common_component.network.repository.AnimeRepository
import com.xyoye.common_component.network.request.NetworkException
import com.xyoye.data_component.data.AnimeData

/**
 * 接口配额用尽（服务端返回 errorCode 429「已达到接口调用配额上限」）。
 *
 * 弹弹play 从 2026-06 起对应用做配额管理，新应用额度很小，
 * 撞上之后本轮必须停下来，否则只会继续报错。
 */
class ScrapeQuotaException(message: String) : Exception(message)

/**
 * 刮削器：把「扫描出来的系列」映射到弹弹play 的番剧库。
 *
 * 实测结论（用真实凭证验证过）：
 * - `/api/v2/match` 的 `fileNameOnly` 对我们这档应用一律返回「参数不符合规则」，**不可用**；
 * - `/api/v2/search/episodes`、`/api/v2/search/tmdb` 有独立配额且很小，**不用**；
 * - `/api/v2/search/anime` 可用，中文名基本都能命中，部分英文/罗马音也能命中；
 * - `/api/v2/bangumi/{id}` 提供封面、评分、简介和 `titles` 多语言标题。
 *
 * 所以策略是：**标题搜索 → 打分 → 详情补全**，并且三级缓存兜住重复请求。
 */
object PosterScraper {

    /** 低于这个相似度就认为没匹配上，宁可留空让用户手动改，也不乱贴海报 */
    private const val MIN_MATCH_SCORE = 55

    private const val MEMORY_SEARCH_CACHE_SIZE = 150

    /** 番剧搜索结果内存缓存（磁盘缓存之上再挡一层） */
    private val memorySearchCache = object :
        LinkedHashMap<String, List<AnimeData>>(MEMORY_SEARCH_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<AnimeData>>?
        ): Boolean {
            return size > MEMORY_SEARCH_CACHE_SIZE
        }
    }

    suspend fun scrape(series: PosterSeries): PosterSeries {
        return matchByTitle(series) ?: series
    }

    /**
     * 手动指定番剧后补全详情（供「重新匹配」弹窗调用）
     */
    suspend fun applyAnime(series: PosterSeries, animeId: Int, animeTitle: String?): PosterSeries {
        return enrich(series, animeId, animeTitle)
    }

    // ------------------------------------------------------------------ 标题搜索 + 打分

    private suspend fun matchByTitle(series: PosterSeries): PosterSeries? {
        // 1. 先用季度目录本地匹配：不消耗搜索配额
        AnimeCatalog.find(series.rawTitle, series.season)?.let { anime ->
            return enrich(series, anime.animeId, anime.animeTitle)
        }

        // 2. 目录里没有，才走搜索接口
        for (keyword in buildKeywords(series)) {
            val animes = searchAnimeCached(keyword)
            if (animes.isEmpty()) continue

            val best = animes.withIndex()
                .maxByOrNull { (index, anime) -> score(anime, series, index) }
                ?: continue

            if (score(best.value, series, best.index) < MIN_MATCH_SCORE) continue

            return enrich(series, best.value.animeId, best.value.animeTitle)
        }
        return null
    }

    private suspend fun searchAnimeCached(keyword: String): List<AnimeData> {
        memorySearchCache[keyword]?.let { return it }

        ScrapeCacheStore.keyword(keyword)?.let { cached ->
            if (cached.isNotEmpty()) {
                memorySearchCache[keyword] = cached
                return cached
            }
        }

        val animes = try {
            AnimeRepository.searchAnime(keyword, null).getOrNull()?.animes.orEmpty()
        } catch (e: NetworkException) {
            if (isQuotaError(e)) throw ScrapeQuotaException(e.message ?: "接口调用配额已用尽")
            e.printStackTrace()
            emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }

        if (animes.isNotEmpty()) {
            memorySearchCache[keyword] = animes
            ScrapeCacheStore.putKeyword(keyword, animes)
        }
        return animes
    }

    private fun isQuotaError(e: NetworkException): Boolean {
        return e.code == 429 || e.message?.contains("配额") == true
    }

    /**
     * 先精确再放宽：多季的番先带季度搜索，搜不到再退回纯标题。
     */
    private fun buildKeywords(series: PosterSeries): List<String> {
        val title = series.rawTitle.trim()
        if (title.isEmpty()) return emptyList()

        return if (series.season > 1) {
            listOf(
                "$title 第${series.season}季",
                "$title S${series.season}",
                title
            )
        } else {
            listOf(title)
        }
    }

    // ------------------------------------------------------------------ 详情补全（带缓存）

    private suspend fun enrich(series: PosterSeries, animeId: Int, animeTitle: String?): PosterSeries {
        if (animeId <= 0) return series

        // 详情命中缓存：直接拼装，跳过 /api/v2/bangumi 请求
        ScrapeCacheStore.detail(animeId)?.let { cached ->
            return series.copy(
                animeId = animeId,
                animeTitle = cached.title ?: animeTitle,
                posterUrl = cached.imageUrl,
                rating = cached.rating,
                summary = cached.summary,
                titles = cached.titles.toMutableList(),
                titlesChecked = true,
                matched = true,
                lastUpdate = System.currentTimeMillis()
            )
        }

        val bangumi = try {
            AnimeRepository.getAnimeDetail(animeId.toString()).getOrNull()?.bangumi
        } catch (e: NetworkException) {
            if (isQuotaError(e)) throw ScrapeQuotaException(e.message ?: "接口调用配额已用尽")
            e.printStackTrace()
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } ?: return series.copy(
            animeId = animeId,
            animeTitle = animeTitle,
            matched = true,
            lastUpdate = System.currentTimeMillis()
        )

        val titles = mutableListOf<PosterTitle>()
        bangumi.titles?.forEach { title ->
            val text = title.title
            if (!text.isNullOrBlank()) {
                titles.add(PosterTitle(title.language.orEmpty(), text))
            }
        }

        ScrapeCacheStore.putDetail(
            animeId = animeId,
            title = bangumi.animeTitle,
            imageUrl = bangumi.imageUrl,
            rating = bangumi.rating,
            summary = bangumi.summary,
            titles = titles
        )

        return series.copy(
            animeId = animeId,
            animeTitle = bangumi.animeTitle?.takeIf { it.isNotBlank() } ?: animeTitle,
            posterUrl = bangumi.imageUrl,
            rating = bangumi.rating,
            summary = bangumi.summary,
            titles = titles,
            titlesChecked = true,
            matched = true,
            lastUpdate = System.currentTimeMillis()
        )
    }

    // ------------------------------------------------------------------ 打分

    private fun score(anime: AnimeData, series: PosterSeries, rank: Int): Int {
        val animeTitle = anime.animeTitle.orEmpty()
        if (animeTitle.isBlank()) return 0

        val similarity = MediaTitleParser.similarity(series.rawTitle, animeTitle)
        val latinQuery = series.rawTitle.none { it.code in 0x4E00..0x9FFF }

        // 英文 / 罗马音标题搜出来的结果通常是中文名，相似度恒为 0，
        // 这时以「搜索接口的相关度排序」作为基准分（排名越靠前越可信）。
        var score = if (latinQuery) {
            maxOf(similarity, (70 - rank * 25).coerceAtLeast(20))
        } else {
            similarity
        }

        // 完全一致再加一档，避免被「续作 / 剧场版」的近似标题抢走
        if (MediaTitleParser.normalize(series.rawTitle) == MediaTitleParser.normalize(animeTitle)) {
            score += 15
        }

        // 多集番剧不该匹配到剧场版 / OVA
        if (series.episodeCount > 1 && isMovieLike(animeTitle)) {
            score -= 40
        }

        if (series.season > 1) {
            val hasSeasonMarker = matchSeasonMarker(animeTitle)
            when {
                hasSeasonMarker == series.season -> score += 20
                hasSeasonMarker > 0 && hasSeasonMarker != series.season -> score -= 30
                // 没有任何季度标记时，弱惩罚，避免「第二季」被「第一季」顶掉
                else -> score -= 10
            }
        } else {
            // 第一季不该匹配到明确标注了其它季度的条目
            val hasSeasonMarker = matchSeasonMarker(animeTitle)
            if (hasSeasonMarker > 1) score -= 25
        }

        return score.coerceIn(0, 100)
    }

    private fun isMovieLike(title: String): Boolean {
        return title.contains("剧场版") ||
                title.contains("劇場版") ||
                title.contains("Movie", ignoreCase = true) ||
                Regex("(?i)\\bova\\b|\\boad\\b").containsMatchIn(title)
    }

    private fun matchSeasonMarker(animeTitle: String): Int =
        MediaTitleParser.seasonMarker(animeTitle)
}
