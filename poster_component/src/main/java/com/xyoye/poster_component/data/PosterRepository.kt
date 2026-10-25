package com.xyoye.poster_component.data

import com.xyoye.common_component.storage.StorageFactory
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 海报墙数据仓库：扫描 → 刮削 → 缓存。
 *
 * 默认只扫网络媒体库，其中 WebDAV 是主要目标；想放开 SMB / FTP / Alist
 * 只要改 [SCAN_MEDIA_TYPES] 即可，底层 Storage 抽象是同一套。
 */
object PosterRepository {

    /**
     * 需要纳入海报墙的媒体库类型。默认只有 WebDAV；
     * 想同时扫 SMB / FTP / Alist，直接往这里加（底层是同一套 Storage 抽象）。
     */
    val SCAN_MEDIA_TYPES = listOf(
        MediaType.WEBDAV_SERVER
    )

    private const val MAX_SCRAPE_PER_REFRESH = 200
    private const val SCRAPE_INTERVAL_MS = 150L

    private val memory = LinkedHashMap<String, PosterSeries>()
    private val mutex = Mutex()

    fun fromMemory(seriesId: String): PosterSeries? = memory[seriesId]

    fun memoryByStorage(storageId: Int): List<PosterSeries> {
        return memory.values.filter { it.storageId == storageId }
    }

    /** 读取本地缓存，用于「先秒开、再刷新」 */
    suspend fun loadCache(): List<PosterSeries> = mutex.withLock {
        val result = mutableListOf<PosterSeries>()
        SCAN_MEDIA_TYPES.forEach { mediaType ->
            librariesOf(mediaType).forEach { library ->
                val cached = PosterCacheStore.read(library.id)
                cached.forEach { memory[it.id] = it }
                result += cached
            }
        }
        result.sortedWith(SERIES_COMPARATOR)
    }

    /**
     * 重新扫描 + 刮削。
     *
     * @param forceScrape true 时已匹配的系列也会重新刮削（标题识别不准、换季时用）
     */
    suspend fun refresh(
        forceScrape: Boolean,
        onProgress: suspend (String) -> Unit
    ): List<PosterSeries> = mutex.withLock {
        val result = mutableListOf<PosterSeries>()
        val libraries = SCAN_MEDIA_TYPES.flatMap { librariesOf(it) }
        if (libraries.isEmpty()) {
            onProgress("还没有连接 WebDAV 网盘")
            return@withLock emptyList()
        }

        libraries.forEach { library ->
            onProgress("扫描「${library.displayName}」")
            val storage = StorageFactory.createStorage(library)
            if (storage == null) {
                onProgress("「${library.displayName}」类型不支持")
                return@forEach
            }

            val scanned = try {
                PosterScanner(storage, library).scan { message -> onProgress(message) }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            } finally {
                storage.close()
            }

            if (scanned.isEmpty()) {
                // 扫描失败（网盘掉线 / 鉴权失效）时保留旧缓存，别把用户的海报清空
                val cached = PosterCacheStore.read(library.id)
                if (cached.isNotEmpty()) {
                    cached.forEach { memory[it.id] = it }
                    result += cached
                    onProgress("「${library.displayName}」本次扫描为空，已保留上次结果")
                }
                return@forEach
            }

            val merged = scanned.map { series -> mergeMatched(series) }
            onProgress("刮削 ${merged.size} 部番剧…")
            val scraped = scrapeSeries(merged, forceScrape, onProgress)

            PosterCacheStore.write(library.id, scraped)
            scraped.forEach { memory[it.id] = it }
            result += scraped
        }

        result.sortedWith(SERIES_COMPARATOR)
    }

    fun updateMatched(series: PosterSeries) {
        memory[series.id] = series
        PosterCacheStore.write(series.storageId, memoryByStorage(series.storageId))
    }

    /**
     * 手动指定番剧（重新匹配）：覆盖刮削结果并落盘
     */
    fun applyMatch(series: PosterSeries, anime: com.xyoye.data_component.data.AnimeData): PosterSeries {
        val updated = series.copy(
            animeId = anime.animeId,
            animeTitle = anime.animeTitle,
            posterUrl = anime.imageUrl,
            rating = anime.rating,
            year = anime.startDate?.take(4)?.toIntOrNull() ?: series.year,
            matched = true,
            lastUpdate = System.currentTimeMillis()
        )
        memory[updated.id] = updated
        PosterCacheStore.write(updated.storageId, memoryByStorage(updated.storageId))
        return updated
    }

    /**
     * 恢复到「解析出的原始标题」，等待下次自动重新刮削
     */
    fun clearMatch(series: PosterSeries): PosterSeries {
        val updated = series.copy(
            animeId = 0,
            animeTitle = null,
            posterUrl = null,
            rating = 0.0,
            summary = null,
            matched = false,
            lastUpdate = System.currentTimeMillis()
        )
        memory[updated.id] = updated
        PosterCacheStore.write(updated.storageId, memoryByStorage(updated.storageId))
        return updated
    }

    /**
     * 把播放记录里的进度贴到系列上，用于卡片「看到 X/Y」和「有进度」筛选
     */
    suspend fun applyWatchProgress(seriesList: List<PosterSeries>): List<PosterSeries> {
        if (seriesList.isEmpty()) return seriesList
        val histories = try {
            com.xyoye.common_component.database.DatabaseManager.instance
                .getPlayHistoryDao()
                .getAll()
        } catch (e: Exception) {
            e.printStackTrace()
            return seriesList
        }
        if (histories.isEmpty()) return seriesList

        val byStorage = histories
            .filter { it.storageId != null && it.videoPosition > 0 }
            .groupBy { it.storageId!! }

        return seriesList.map { series ->
            val rows = byStorage[series.storageId].orEmpty()
            if (rows.isEmpty()) return@map series

            val watchedNames = rows.map { it.videoName }.toHashSet()
            val watchedEpisodes = series.episodes.filter { watchedNames.contains(it.name) }
            if (watchedEpisodes.isEmpty()) return@map series

            series.copy(
                watchedCount = watchedEpisodes.size,
                lastWatchedEpisode = watchedEpisodes.maxOf { it.episode }
            )
        }
    }

    /** 是否已经添加过需要扫描的网盘 */
    suspend fun hasAnyLibrary(): Boolean {
        return SCAN_MEDIA_TYPES.any { librariesOf(it).isNotEmpty() }
    }

    private suspend fun scrapeSeries(
        seriesList: List<PosterSeries>,
        forceScrape: Boolean,
        onProgress: suspend (String) -> Unit
    ): List<PosterSeries> {
        var scrapedCount = 0
        var index = 0
        val result = mutableListOf<PosterSeries>()

        seriesList.forEach { series ->
            val needScrape = forceScrape || series.matched.not()
            if (!needScrape || scrapedCount >= MAX_SCRAPE_PER_REFRESH) {
                result += series
                return@forEach
            }

            index++
            onProgress("刮削 ${series.rawTitle}（$index/${seriesList.size}）")
            val scraped = try {
                PosterScraper.scrape(series)
            } catch (e: Exception) {
                e.printStackTrace()
                series
            }
            scrapedCount++
            result += scraped
            delay(SCRAPE_INTERVAL_MS)
        }

        return result
    }

    /** 保留上一次的刮削结果，避免每次刷新都重新打接口 */
    private fun mergeMatched(series: PosterSeries): PosterSeries {
        val old = memory[series.id] ?: return series
        if (old.matched.not()) return series
        return series.copy(
            animeId = old.animeId,
            animeTitle = old.animeTitle,
            posterUrl = old.posterUrl,
            rating = old.rating,
            summary = old.summary,
            year = if (old.year > 0) old.year else series.year,
            matched = true
        )
    }

    private suspend fun librariesOf(mediaType: MediaType): List<MediaLibraryEntity> {
        return try {
            com.xyoye.common_component.database.DatabaseManager.instance
                .getMediaLibraryDao()
                .getByMediaTypeSuspend(mediaType)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private val SERIES_COMPARATOR = compareByDescending<PosterSeries> { it.lastUpdate }
        .thenByDescending { it.episodeCount }
}
