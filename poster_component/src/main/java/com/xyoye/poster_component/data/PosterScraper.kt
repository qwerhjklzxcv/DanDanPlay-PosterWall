package com.xyoye.poster_component.data

import com.xyoye.common_component.network.repository.AnimeRepository
import com.xyoye.data_component.data.AnimeData

/**
 * 刮削器：拿解析出来的标题去弹弹play 自己的番剧搜索接口捞海报/评分/番剧 id。
 *
 * 之所以不用 TMDB / Bangumi：
 * - 这里搜的是「中英日混排的番剧名」，弹弹play 的库本身就是中文优先，命中率最高；
 * - 不需要额外申请 key，走的还是 App 自带的鉴权与重试链路；
 * - 捞回来的 `animeId` 之后还能直接用于详情页、追番、弹幕匹配。
 */
object PosterScraper {

    /** 低于这个相似度就认为没匹配上，宁可留空让用户手动改，也不乱贴海报 */
    private const val MIN_MATCH_SCORE = 55

    suspend fun scrape(series: PosterSeries): PosterSeries {
        val keywords = buildKeywords(series)
        for (keyword in keywords) {
            val result = try {
                AnimeRepository.searchAnime(keyword, null)
            } catch (e: Exception) {
                e.printStackTrace()
                return series
            }
            val animes = result.getOrNull()?.animes.orEmpty()
            if (animes.isEmpty()) continue

            val best = animes.maxByOrNull { score(it, series) } ?: continue
            if (score(best, series) < MIN_MATCH_SCORE) continue

            return series.copy(
                animeId = best.animeId,
                animeTitle = best.animeTitle,
                posterUrl = best.imageUrl,
                rating = best.rating,
                year = best.startDate?.take(4)?.toIntOrNull() ?: series.year,
                matched = true,
                lastUpdate = System.currentTimeMillis()
            )
        }
        return series
    }

    /**
     * 先精确再放宽：多季的番先带季度搜索，搜不到再退回纯标题。
     */
    private fun buildKeywords(series: PosterSeries): List<String> {
        val title = series.rawTitle.trim()
        if (title.isEmpty()) return emptyList()
        return if (series.season > 1) {
            listOf("$title 第${series.season}季", "$title S${series.season}", title)
        } else {
            listOf(title)
        }
    }

    private fun score(anime: AnimeData, series: PosterSeries): Int {
        val animeTitle = anime.animeTitle.orEmpty()
        if (animeTitle.isBlank()) return 0

        var score = MediaTitleParser.similarity(series.rawTitle, animeTitle)

        // 完全一致再加一档，避免被「续作 / 剧场版」的近似标题抢走
        if (MediaTitleParser.normalize(series.rawTitle) == MediaTitleParser.normalize(animeTitle)) {
            score += 15
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

    private fun matchSeasonMarker(animeTitle: String): Int {
        Regex("第([一二三四五六七八九十\\d]{1,3})季").find(animeTitle)?.let { match ->
            val text = match.groupValues[1]
            text.toIntOrNull()?.let { return it }
            return chineseToInt(text)
        }
        Regex("(?i)season\\s*(\\d{1,2})").find(animeTitle)?.let { match ->
            match.groupValues[1].toIntOrNull()?.let { return it }
        }
        Regex("(?i)\\bs(\\d{1,2})\\b").find(animeTitle)?.let { match ->
            match.groupValues[1].toIntOrNull()?.let { return it }
        }
        return 0
    }

    private fun chineseToInt(text: String): Int {
        var result = 0
        var pending = 0
        text.forEach { char ->
            when (char) {
                '一' -> pending = 1
                '二' -> pending = 2
                '三' -> pending = 3
                '四' -> pending = 4
                '五' -> pending = 5
                '六' -> pending = 6
                '七' -> pending = 7
                '八' -> pending = 8
                '九' -> pending = 9
                '十' -> {
                    result += if (pending == 0) 10 else pending * 10
                    pending = 0
                }
            }
        }
        return result + pending
    }
}
