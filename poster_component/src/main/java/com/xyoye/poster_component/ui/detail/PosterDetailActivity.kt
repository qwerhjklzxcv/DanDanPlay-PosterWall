package com.xyoye.poster_component.ui.detail

import androidx.core.view.isVisible
import coil.load
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.adapter.addItem
import com.xyoye.common_component.adapter.buildAdapter
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.extension.grid
import com.xyoye.common_component.extension.setData
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.poster_component.BR
import com.xyoye.poster_component.R
import com.xyoye.poster_component.data.PosterEpisode
import com.xyoye.poster_component.data.PosterSeries
import com.xyoye.poster_component.databinding.ActivityPosterDetailBinding
import com.xyoye.poster_component.databinding.ItemPosterEpisodeBinding
import java.util.Locale

@Route(path = RouteTable.Poster.Detail)
class PosterDetailActivity : BaseActivity<PosterDetailViewModel, ActivityPosterDetailBinding>() {

    @Autowired
    @JvmField
    var seriesId: String? = null

    override fun initViewModel() = ViewModelInit(
        BR.viewModel,
        PosterDetailViewModel::class.java
    )

    override fun getLayoutId() = R.layout.activity_poster_detail

    override fun initView() {
        ARouter.getInstance().inject(this)

        supportActionBar?.setDisplayShowTitleEnabled(true)

        initEpisodeRecyclerView()
        initListener()

        val id = seriesId
        if (id.isNullOrBlank()) {
            ToastCenter.showError("参数错误，无法打开详情")
            finish()
            return
        }
        viewModel.load(id)
    }

    private fun initListener() {
        viewModel.seriesLiveData.observe(this) { series ->
            series ?: return@observe
            renderSeries(series)
        }

        viewModel.playLiveData.observe(this) { success ->
            if (success) {
                ARouter.getInstance()
                    .build(RouteTable.Player.Player)
                    .navigation()
            }
        }

        viewModel.errorLiveData.observe(this) { message ->
            ToastCenter.showError(message)
        }
    }

    private fun initEpisodeRecyclerView() {
        dataBinding.episodeRv.apply {
            layoutManager = grid(5)
            adapter = buildAdapter {
                addItem<PosterEpisode, ItemPosterEpisodeBinding>(R.layout.item_poster_episode) {
                    initView { data, position, _ ->
                        itemBinding.episodeTitleTv.text = when {
                            data.episode > 0 -> "第${data.episode}话"
                            else -> "${position + 1}"
                        }
                        itemBinding.root.setOnClickListener {
                            viewModel.play(position)
                        }
                    }
                }
            }
        }
    }

    private fun renderSeries(series: PosterSeries) {
        title = series.displayTitle

        dataBinding.seriesTitleTv.text = series.displayTitle
        dataBinding.seriesSubtitleTv.text = series.infoText

        val rating = series.rating
        if (rating > 0) {
            dataBinding.seriesRatingTv.isVisible = true
            dataBinding.seriesRatingTv.text = String.format(Locale.US, "%.1f 分", rating)
        } else {
            dataBinding.seriesRatingTv.isVisible = false
        }

        dataBinding.seriesSummaryTv.text = series.summary
            ?.takeIf { it.isNotBlank() }
            ?: "暂无简介（未匹配到番剧信息，可在海报墙刷新重新刮削）"
        dataBinding.seriesPathTv.text = series.folderPath
        dataBinding.seriesCountTv.text = "共 ${series.episodeCount} 集"

        val cover = series.coverUrl
        if (cover.isNullOrBlank()) {
            dataBinding.seriesPosterIv.setImageResource(R.drawable.pw_poster_placeholder)
        } else {
            dataBinding.seriesPosterIv.load(cover)
        }

        dataBinding.playBt.setOnClickListener {
            if (series.episodes.isEmpty()) {
                ToastCenter.showError("这个系列里没有视频文件")
                return@setOnClickListener
            }
            // 播放入口固定从第一集开始；单集续播在播放器里靠播放记录自动恢复
            val index = series.episodes.indexOfFirst { it.path.isNotBlank() }.coerceAtLeast(0)
            viewModel.play(index)
        }

        dataBinding.episodeRv.setData(series.episodes)
    }
}
