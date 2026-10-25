package com.xyoye.poster_component.data

import com.xyoye.common_component.extension.toMd5String
import com.xyoye.common_component.storage.Storage
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.data_component.entity.MediaLibraryEntity

/**
 * 把一个媒体库（WebDAV / SMB / FTP / Alist）递归扫一遍，按「标题 + 季度」聚合成系列。
 *
 * 解析策略：
 * - 标题优先从文件名解析；文件名只有集数（如 `01.mp4`）时退化到文件夹名；
 * - 遇到 `Season 2` / `S2` / `第二季` 这类纯季度文件夹，季度号来自文件夹名，标题沿用上一级；
 * - 遇到 `1080p` / `BD` 这类噪音文件夹，直接跳过并沿用上一级标题；
 * - 同一目录里如果混了多部番，会按解析出的标题拆成多张卡片。
 */
class PosterScanner(
    private val storage: Storage,
    private val library: MediaLibraryEntity,
    private val maxDepth: Int = DEFAULT_MAX_DEPTH
) {

    companion object {
        const val DEFAULT_MAX_DEPTH = 3
        private const val MAX_DIRECTORIES = 600
        private const val MAX_VIDEOS = 20000
    }

    private val visitedPaths = HashSet<String>()
    private val seriesList = mutableListOf<PosterSeries>()

    private var directoryCount = 0
    private var videoCount = 0

    suspend fun scan(onProgress: suspend (String) -> Unit): List<PosterSeries> {
        val root = storage.getRootFile() ?: return emptyList()
        walk(root, null, 0, onProgress)
        return seriesList.sortedBy { it.rawTitle }
    }

    private suspend fun walk(
        directory: StorageFile,
        parentTitle: String?,
        depth: Int,
        onProgress: suspend (String) -> Unit
    ) {
        if (depth > maxDepth) return
        if (directoryCount >= MAX_DIRECTORIES || videoCount >= MAX_VIDEOS) return

        val path = directory.filePath()
        if (path.isBlank() || !visitedPaths.add(path)) return
        directoryCount++

        val children = try {
            storage.openDirectory(directory, true)
        } catch (e: Exception) {
            emptyList()
        }
        if (children.isEmpty()) return

        val directoryName = directory.fileName().ifBlank { library.displayName }
        val folderSeason = MediaTitleParser.parseSeasonFolder(directoryName)
        val contextTitle = when {
            folderSeason > 0 -> parentTitle ?: directoryName
            MediaTitleParser.isNoiseFolder(directoryName) -> parentTitle ?: directoryName
            else -> directoryName
        }

        val videos = children.filter { it.isFile() && isVideoFile(it) }
        if (videos.isNotEmpty()) {
            onProgress("识别「$contextTitle」")
            videoCount += videos.size
            collectSeries(directory, contextTitle, folderSeason, videos)
        }

        val childrenDirectories = children.filter { it.isDirectory() }.sortedBy { it.fileName() }
        for (child in childrenDirectories) {
            walk(child, contextTitle, depth + 1, onProgress)
        }
    }

    private fun isVideoFile(file: StorageFile): Boolean {
        return file.isVideoFile() || MediaTitleParser.isVideoFile(file.fileName())
    }

    private fun collectSeries(
        directory: StorageFile,
        contextTitle: String,
        folderSeason: Int,
        videos: List<StorageFile>
    ) {
        // key = 归一化标题 + 季度，同目录下的不同番会自动拆开
        val groups = LinkedHashMap<String, MutableList<Pair<MediaTitleParser.ParsedTitle, StorageFile>>>()

        videos.sortedBy { it.fileName() }.forEach { file ->
            val parsed = MediaTitleParser.parseFileName(file.fileName(), contextTitle)
            val title = parsed.title.ifBlank { contextTitle }.trim()
            val season = when {
                parsed.season > 0 -> parsed.season
                folderSeason > 0 -> folderSeason
                else -> 1
            }
            val key = MediaTitleParser.normalize(title) + "#" + season
            groups.getOrPut(key) { mutableListOf() }.add(parsed.copy(title = title, season = season) to file)
        }

        val directoryPath = directory.filePath()
        groups.forEach { (_, items) ->
            if (items.isEmpty()) return@forEach

            val head = items.first().first
            val episodes = items.mapIndexed { index, pair ->
                PosterEpisode(
                    name = pair.second.fileName(),
                    path = pair.second.filePath(),
                    episode = if (pair.first.episode > 0) pair.first.episode else index + 1,
                    size = pair.second.fileLength()
                )
            }.sortedBy { it.episode }

            val year = parseYear(head.title) ?: parseYear(directoryPath) ?: 0

            seriesList.add(
                PosterSeries(
                    id = "${library.id}|$directoryPath|${head.title}|${head.season}".toMd5String(),
                    storageId = library.id,
                    storageName = library.displayName,
                    folderPath = directoryPath,
                    rawTitle = head.title,
                    season = head.season,
                    year = year,
                    episodes = episodes.toMutableList(),
                    lastUpdate = System.currentTimeMillis()
                )
            )
        }
    }

    private fun parseYear(text: String): Int? {
        val match = Regex("(?<!\\d)(19\\d{2}|20\\d{2})(?!\\d)").find(text) ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        return if (year in 1950..2100) year else null
    }
}
