package com.xyoye.poster_component.data

import com.xyoye.common_component.base.app.BaseApplication
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 刮削结果本地缓存。
 *
 * 用 JSON 文件而不是 Room：不引入数据库版本 / 迁移风险，删掉缓存目录即可复位。
 * 位置：`filesDir/poster_wall/series_{storageId}.json`
 */
object PosterCacheStore {

    private const val CACHE_DIR = "poster_wall"

    private fun cacheDirectory(): File {
        val dir = File(BaseApplication.getAppContext().filesDir, CACHE_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun cacheFile(storageId: Int): File {
        return File(cacheDirectory(), "series_$storageId.json")
    }

    fun read(storageId: Int): List<PosterSeries> {
        val file = cacheFile(storageId)
        if (!file.exists()) return emptyList()
        return try {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray("series") ?: JSONArray()
            val result = mutableListOf<PosterSeries>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val series = PosterSeries.fromJson(item)
                if (series.id.isBlank() || series.episodes.isEmpty()) continue
                result.add(series)
            }
            result
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun write(storageId: Int, seriesList: List<PosterSeries>) {
        try {
            val root = JSONObject()
            root.put("storageId", storageId)
            root.put("updateTime", System.currentTimeMillis())
            root.put("series", JSONArray().apply {
                seriesList.forEach { put(it.toJson()) }
            })
            cacheFile(storageId).writeText(root.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clear(storageId: Int) {
        try {
            cacheFile(storageId).delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
