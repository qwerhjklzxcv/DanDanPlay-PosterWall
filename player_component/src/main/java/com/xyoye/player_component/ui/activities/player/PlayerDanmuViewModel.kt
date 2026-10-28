package com.xyoye.player_component.ui.activities.player

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.config.DevelopConfig
import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.common_component.utils.danmu.DanmuFinder
import com.xyoye.common_component.utils.danmu.source.DanmuSourceFactory
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.bean.LocalDanmuBean
import com.xyoye.data_component.data.DanmuEpisodeData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Created by xyoye on 2022/1/2.
 */

class PlayerDanmuViewModel : BaseViewModel() {

    companion object {
        /** 只提示一次，避免每集都弹 */
        @Volatile
        private var authHintShown = false
    }

    val loadDanmuLiveData = MutableLiveData<Pair<String, LocalDanmuBean>>()
    val danmuSearchLiveData = MutableLiveData<List<DanmuEpisodeData>>()
    val downloadDanmuLiveData = MutableLiveData<LocalDanmuBean>()

    fun matchDanmu(videoSource: BaseVideoSource) {
        viewModelScope.launch(Dispatchers.IO) {
            val danmuSource = DanmuSourceFactory.build(videoSource)
            val danmu = danmuSource?.let {
                DanmuFinder.instance.downloadMatched(it)
            }

            if (danmu != null) {
                loadDanmuLiveData.postValue(videoSource.getVideoUrl() to danmu)
                return@launch
            }

            // 匹配失败：如果根本没配置开发者凭证，明确告诉用户原因，而不是静默无弹幕
            notifyMatchFailed()
        }
    }

    private fun notifyMatchFailed() {
        if (authHintShown) return
        authHintShown = true

        val authenticated = DevelopConfig.getAppId().isNullOrEmpty().not() &&
                DevelopConfig.getAppSecret().isNullOrEmpty().not()
        if (!authenticated) {
            ToastCenter.showWarning("未完成开发者认证，无法自动匹配网络弹幕")
        }
    }

    fun searchDanmu(searchText: String) {
        if (searchText.isEmpty())
            return

        viewModelScope.launch {
            val result = DanmuFinder.instance.search(searchText).flatMap { it.episodes }
            danmuSearchLiveData.postValue(result)
        }
    }

    fun downloadDanmu(episode: DanmuEpisodeData) {
        viewModelScope.launch {
            showLoading()
            val result = DanmuFinder.instance.downloadEpisode(episode)
            hideLoading()

            if (result == null) {
                ToastCenter.showError("弹幕保存失败")
                return@launch
            }

            downloadDanmuLiveData.postValue(result)
        }
    }
}