package com.xyoye.poster_component.ui.detail

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.database.DatabaseManager
import com.xyoye.common_component.source.VideoSourceManager
import com.xyoye.common_component.source.factory.StorageVideoSourceFactory
import com.xyoye.common_component.storage.StorageFactory
import com.xyoye.poster_component.data.PosterEpisode
import com.xyoye.poster_component.data.PosterRepository
import com.xyoye.poster_component.data.PosterSeries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PosterDetailViewModel : BaseViewModel() {

    val seriesLiveData = MutableLiveData<PosterSeries?>()
    val playLiveData = MutableLiveData<Boolean>()
    val errorLiveData = MutableLiveData<String>()

    private var series: PosterSeries? = null

    fun load(seriesId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            var target = PosterRepository.fromMemory(seriesId)
            if (target == null) {
                // 进程被杀后从缓存恢复
                PosterRepository.loadCache()
                target = PosterRepository.fromMemory(seriesId)
            }
            if (target == null) {
                errorLiveData.postValue("找不到这部番的数据，请回到海报墙刷新")
                return@launch
            }
            series = target
            seriesLiveData.postValue(target)
        }
    }

    fun play(index: Int) {
        val current = series ?: return
        val episode = current.episodes.getOrNull(index) ?: return

        viewModelScope.launch(Dispatchers.IO) {
            val success = try {
                setupPlayback(current, episode)
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
            if (!success) {
                errorLiveData.postValue("播放失败：找不到视频文件")
            }
            playLiveData.postValue(success)
        }
    }

    /**
     * 复用 App 自己的播放链路：
     * 打开系列目录 → 命中当前集 → 构造 StorageVideoSource（自动带上整季列表、续播进度、同名字幕/弹幕）。
     */
    private suspend fun setupPlayback(series: PosterSeries, episode: PosterEpisode): Boolean {
        val library = DatabaseManager.instance.getMediaLibraryDao()
            .getById(series.storageId) ?: return false
        val storage = StorageFactory.createStorage(library) ?: return false

        val directory = storage.pathFile(series.folderPath, true) ?: return false
        storage.openDirectory(directory, true)

        val target = storage.directoryFiles.firstOrNull { it.filePath() == episode.path }
            ?: storage.pathFile(episode.path, false)
            ?: return false

        target.playHistory = DatabaseManager.instance.getPlayHistoryDao()
            .getPlayHistory(target.uniqueKey(), library.id)

        val mediaSource = StorageVideoSourceFactory.create(target) ?: return false
        VideoSourceManager.getInstance().setSource(mediaSource)
        return true
    }
}
