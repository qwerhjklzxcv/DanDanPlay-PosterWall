package com.xyoye.poster_component.ui.wall

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.xyoye.poster_component.data.PosterFilter
import com.xyoye.poster_component.data.PosterRepository
import com.xyoye.poster_component.data.PosterSeries
import com.xyoye.poster_component.data.PosterSort
import com.xyoye.common_component.base.BaseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PosterWallViewModel : BaseViewModel() {

    companion object {
        private const val CACHE_EXPIRE_MS = 6 * 60 * 60 * 1000L
    }

    val seriesLiveData = MutableLiveData<List<PosterSeries>>()
    val progressLiveData = MutableLiveData<String>()
    val refreshingLiveData = MutableLiveData(false)
    val emptyLiveData = MutableLiveData(false)
    val countLiveData = MutableLiveData(0)
    val sortLiveData = MutableLiveData(PosterSort.RECENT)
    val filterLiveData = MutableLiveData(PosterFilter.ALL)
    val noLibraryLiveData = MutableLiveData(false)

    private var allSeries: List<PosterSeries> = emptyList()
    private var started = false
    private var refreshing = false
    private var sort = PosterSort.RECENT
    private var filter = PosterFilter.ALL
    private var keyword = ""

    fun start() {
        if (started) return
        started = true

        viewModelScope.launch(Dispatchers.IO) {
            // 先展示缓存，网盘慢的时候也不至于白屏
            val cached = PosterRepository.applyWatchProgress(PosterRepository.loadCache())
            if (cached.isNotEmpty()) {
                allSeries = cached
                emit()
            }
            noLibraryLiveData.postValue(PosterRepository.hasAnyLibrary().not())

            // 没有缓存，或者缓存超过 6 小时，自动刷新一次
            val newest = cached.maxOfOrNull { it.lastUpdate } ?: 0L
            val expired = System.currentTimeMillis() - newest > CACHE_EXPIRE_MS
            if (cached.isEmpty() || expired) {
                doRefresh(forceScrape = false)
            }
        }
    }

    fun refresh(forceScrape: Boolean = false) {
        if (refreshing) return
        viewModelScope.launch(Dispatchers.IO) { doRefresh(forceScrape) }
    }

    fun setKeyword(value: String) {
        val trimmed = value.trim()
        if (trimmed == keyword) return
        keyword = trimmed
        emit()
    }

    fun setSort(value: PosterSort) {
        sort = value
        sortLiveData.postValue(value)
        emit()
    }

    fun setFilter(value: PosterFilter) {
        filter = value
        filterLiveData.postValue(value)
        emit()
    }

    fun replaceSeries(series: PosterSeries) {
        allSeries = allSeries.map { if (it.id == series.id) series else it }
        emit()
    }

    fun clearMatch(series: PosterSeries) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = PosterRepository.clearMatch(series)
            replaceSeries(updated)
        }
    }

    fun isFiltering(): Boolean = filter != PosterFilter.ALL

    fun hasKeyword(): Boolean = keyword.isNotEmpty()

    private suspend fun doRefresh(forceScrape: Boolean) {
        refreshing = true
        refreshingLiveData.postValue(true)
        progressLiveData.postValue("正在扫描网盘…")

        val result = try {
            PosterRepository.refresh(forceScrape) { message ->
                progressLiveData.postValue(message)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            progressLiveData.postValue("扫描失败：${e.message}")
            emptyList()
        }

        val withProgress = PosterRepository.applyWatchProgress(result)
        refreshing = false
        refreshingLiveData.postValue(false)
        progressLiveData.postValue("")
        noLibraryLiveData.postValue(PosterRepository.hasAnyLibrary().not())

        allSeries = withProgress
        emit()
    }

    private fun emit() {
        var list = allSeries

        if (keyword.isNotEmpty()) {
            list = list.filter {
                it.displayTitle.contains(keyword, ignoreCase = true) ||
                        it.rawTitle.contains(keyword, ignoreCase = true)
            }
        }

        list = when (filter) {
            PosterFilter.ALL -> list
            PosterFilter.MATCHED -> list.filter { it.matched }
            PosterFilter.UNMATCHED -> list.filter { it.matched.not() }
            PosterFilter.WATCHED -> list.filter { it.watchedCount > 0 }
        }

        list = when (sort) {
            PosterSort.RECENT -> list.sortedByDescending { it.lastUpdate }
            PosterSort.NAME -> list.sortedBy { it.displayTitle }
            PosterSort.RATING -> list.sortedByDescending { it.rating }
            PosterSort.EPISODES -> list.sortedByDescending { it.episodeCount }
            PosterSort.YEAR -> list.sortedByDescending { it.year }
        }

        countLiveData.postValue(list.size)
        emptyLiveData.postValue(list.isEmpty())
        seriesLiveData.postValue(list)
    }
}
