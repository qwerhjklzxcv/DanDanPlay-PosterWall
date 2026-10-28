package com.xyoye.common_component.utils.danmu

import com.xyoye.common_component.utils.danmu.helper.DanmuContentGenerator
import com.xyoye.common_component.utils.danmu.helper.DanmuFileCreator
import com.xyoye.common_component.utils.danmu.query.DanmuQuery
import com.xyoye.common_component.utils.danmu.source.DanmuSource
import com.xyoye.data_component.bean.LocalDanmuBean
import com.xyoye.data_component.data.DanmuAnimeData
import com.xyoye.data_component.data.DanmuEpisodeData
import com.xyoye.data_component.data.DanmuRelatedUrlData
import org.apache.commons.io.FileUtils
import java.io.InputStream

/**
 * Created by xyoye on 2024/1/14.
 */

class DanmuFinderImpl(
    private val danmuQuery: DanmuQuery
) : DanmuFinder {

    override suspend fun getMatched(source: DanmuSource): DanmuAnimeData? {
        val episode = matchEpisode(source)
            ?: return null

        return DanmuAnimeData(episode.animeId, episode.animeTitle, listOf(episode))
    }

    override suspend fun downloadMatched(source: DanmuSource): LocalDanmuBean? {
        val episode = matchEpisode(source)
            ?: return null

        return downloadEpisode(episode)
    }

    /**
     * 匹配剧集：走文件 hash（前 16MB 的 MD5）。
     *
     * 文件名匹配（fileNameOnly）实测对所有参数组合都返回「参数不符合规则」，
     * 服务端不对这档应用开放，所以这里只保留 hash 这一条可靠路径；
     * 网络文件在 NetworkDanmuSource 里用 Range 请求只拉前 16MB。
     */
    private suspend fun matchEpisode(source: DanmuSource): DanmuEpisodeData? {
        val hash = source.hash() ?: return null
        return danmuQuery.match(hash)
    }

    override suspend fun downloadEpisode(episode: DanmuEpisodeData, withRelated: Boolean): LocalDanmuBean? {
        val contents = danmuQuery.getContentByEpisodeId(episode.episodeId, withRelated)
        val xmlContent = DanmuContentGenerator.generate(contents)
            ?: return null

        val file = DanmuFileCreator.create(episode.animeTitle, episode.episodeTitle)
            ?: return null

        try {
            FileUtils.write(file, xmlContent, Charsets.UTF_8)
            return LocalDanmuBean(file.absolutePath, episode.episodeId)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return null
    }

    override suspend fun downloadRelated(episode: DanmuEpisodeData, related: List<DanmuRelatedUrlData>): LocalDanmuBean? {
        val contents = related.flatMap {
            if (it.url == episode.episodeId) {
                danmuQuery.getContentByEpisodeId(it.url)
            } else {
                danmuQuery.getContentByUrl(it.url)
            }
        }
        val xmlContent = DanmuContentGenerator.generate(contents)
            ?: return null

        val file = DanmuFileCreator.create(episode.animeTitle, episode.episodeTitle)
            ?: return null

        try {
            FileUtils.write(file, xmlContent, Charsets.UTF_8)
            return LocalDanmuBean(file.absolutePath, episode.episodeId)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return null
    }

    override suspend fun search(text: String): List<DanmuAnimeData> {
        return danmuQuery.search(text)
    }

    override suspend fun getRelated(episodeId: String): List<DanmuRelatedUrlData> {
        return danmuQuery.source(episodeId)
    }

    override suspend fun saveStream(episode: DanmuEpisodeData, inputStream: InputStream): LocalDanmuBean? {
        val file = DanmuFileCreator.create(episode.animeTitle, episode.episodeTitle)
            ?: return null

        try {
            FileUtils.copyToFile(inputStream, file)
            return LocalDanmuBean(file.absolutePath, episode.episodeId)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}