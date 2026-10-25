package com.xyoye.poster_component.ui.wall

import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import coil.load
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.adapter.addItem
import com.xyoye.common_component.adapter.buildAdapter
import com.xyoye.common_component.base.BaseFragment
import com.xyoye.common_component.config.DevelopConfig
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.extension.grid
import com.xyoye.common_component.extension.setData
import com.xyoye.common_component.extension.startUrlActivity
import com.xyoye.common_component.weight.BottomActionDialog
import com.xyoye.data_component.bean.SheetActionBean
import com.xyoye.data_component.enums.MediaType
import com.xyoye.poster_component.BR
import com.xyoye.poster_component.R
import com.xyoye.poster_component.data.PosterFilter
import com.xyoye.poster_component.data.PosterSeries
import com.xyoye.poster_component.data.PosterSort
import com.xyoye.poster_component.databinding.FragmentPosterWallBinding
import com.xyoye.poster_component.databinding.ItemPosterSeriesBinding
import com.xyoye.poster_component.ui.dialog.PosterMatchDialog
import java.util.Locale

/**
 * 底部导航「海报墙」：把已连接的 WebDAV 网盘里的番剧刮削成海报墙。
 *
 * 支持：搜索、排序、筛选、下拉刷新、长按重新匹配。
 */
@Route(path = RouteTable.Poster.WallFragment)
class PosterWallFragment : BaseFragment<PosterWallViewModel, FragmentPosterWallBinding>() {

    companion object {
        private const val DEVELOPER_CENTER_URL = "https://dev.dandanplay.com/"
    }

    override fun initViewModel() = ViewModelInit(
        BR.viewModel,
        PosterWallViewModel::class.java
    )

    override fun getLayoutId() = R.layout.fragment_poster_wall

    override fun initView() {
        ARouter.getInstance().inject(this)

        initRecyclerView()
        initHeader()

        observeData()

        viewModel.start()
    }

    override fun onResume() {
        super.onResume()
        refreshAuthTips()
    }

    private fun initHeader() {
        dataBinding.refreshBt.setOnClickListener {
            viewModel.refresh(forceScrape = false)
        }

        dataBinding.searchEt.doAfterTextChanged { text ->
            viewModel.setKeyword(text?.toString().orEmpty())
            refreshEmptyText()
        }

        dataBinding.sortTv.setOnClickListener { showSortDialog() }
        dataBinding.filterTv.setOnClickListener { showFilterDialog() }

        dataBinding.swipeRefresh.setColorSchemeResources(R.color.pw_theme)
        dataBinding.swipeRefresh.setOnRefreshListener {
            viewModel.refresh(forceScrape = false)
        }

        dataBinding.authTipsTv.setOnClickListener {
            requireContext().startUrlActivity(DEVELOPER_CENTER_URL)
        }

        dataBinding.addStorageBt.setOnClickListener {
            ARouter.getInstance()
                .build(RouteTable.Stream.StoragePlus)
                .withSerializable("mediaType", MediaType.WEBDAV_SERVER)
                .navigation()
        }
    }

    private fun initRecyclerView() {
        dataBinding.seriesRv.apply {
            layoutManager = grid(spanCount())
            adapter = buildAdapter {
                addItem<PosterSeries, ItemPosterSeriesBinding>(R.layout.item_poster_series) {
                    initView { data, _, _ ->
                        itemBinding.seriesTitleTv.text = data.displayTitle
                        itemBinding.seriesInfoTv.text = data.infoText
                        itemBinding.seriesSeasonTv.isVisible = data.season > 1
                        itemBinding.seriesSeasonTv.text = "第${data.season}季"

                        if (data.rating > 0) {
                            itemBinding.seriesRatingTv.isVisible = true
                            itemBinding.seriesRatingTv.text =
                                String.format(Locale.US, "%.1f", data.rating)
                        } else {
                            itemBinding.seriesRatingTv.isVisible = false
                        }

                        itemBinding.seriesEpisodeTv.isVisible = data.episodeCount > 0
                        itemBinding.seriesEpisodeTv.text = "${data.episodeCount}集"

                        val watched = data.watchedText
                        itemBinding.seriesProgressTv.isVisible = watched.isNotEmpty()
                        itemBinding.seriesProgressTv.text = watched

                        val cover = data.coverUrl
                        if (cover.isNullOrBlank()) {
                            itemBinding.seriesPosterIv.setImageResource(R.drawable.pw_poster_placeholder)
                        } else {
                            itemBinding.seriesPosterIv.load(cover)
                        }

                        itemBinding.root.setOnClickListener {
                            openDetail(data)
                        }
                        itemBinding.root.setOnLongClickListener {
                            showSeriesActions(data)
                            true
                        }
                    }
                }
            }
        }
    }

    private fun observeData() {
        viewModel.seriesLiveData.observe(viewLifecycleOwner) { list ->
            dataBinding.seriesRv.setData(list)
        }

        viewModel.progressLiveData.observe(viewLifecycleOwner) { message ->
            val scanning = message.isNotBlank()
            dataBinding.progressGroup.isVisible = scanning
            dataBinding.progressTv.text = message
            dataBinding.refreshBt.isEnabled = scanning.not()
        }

        viewModel.refreshingLiveData.observe(viewLifecycleOwner) { refreshing ->
            dataBinding.refreshBt.isEnabled = refreshing.not()
            dataBinding.swipeRefresh.isRefreshing = refreshing
            if (refreshing) {
                dataBinding.progressGroup.visibility = android.view.View.VISIBLE
                dataBinding.progressTv.text = "正在扫描网盘…"
            } else if (viewModel.progressLiveData.value.isNullOrBlank()) {
                dataBinding.progressGroup.visibility = android.view.View.GONE
            }
        }

        viewModel.emptyLiveData.observe(viewLifecycleOwner) { empty ->
            dataBinding.emptyGroup.isVisible = empty
            if (empty) refreshEmptyText()
        }

        viewModel.noLibraryLiveData.observe(viewLifecycleOwner) { noLibrary ->
            dataBinding.addStorageBt.isVisible = noLibrary
        }

        viewModel.countLiveData.observe(viewLifecycleOwner) { count ->
            dataBinding.countTv.text = "共 $count 部"
        }

        viewModel.sortLiveData.observe(viewLifecycleOwner) { sort ->
            dataBinding.sortTv.text = "${sort.label} ▾"
        }

        viewModel.filterLiveData.observe(viewLifecycleOwner) { filter ->
            dataBinding.filterTv.text = "${filter.label} ▾"
        }
    }

    private fun showSortDialog() {
        val actions = PosterSort.values().map { SheetActionBean(it, it.label) }
        BottomActionDialog(requireActivity(), actions, "排序方式") { bean ->
            (bean.actionId as? PosterSort)?.let { viewModel.setSort(it) }
            true
        }.show()
    }

    private fun showFilterDialog() {
        val actions = PosterFilter.values().map { SheetActionBean(it, it.label) }
        BottomActionDialog(requireActivity(), actions, "筛选") { bean ->
            (bean.actionId as? PosterFilter)?.let { viewModel.setFilter(it) }
            true
        }.show()
    }

    private fun showSeriesActions(series: PosterSeries) {
        val actions = listOf(
            SheetActionBean(
                SeriesAction.Match,
                "重新匹配",
                R.drawable.pw_ic_match,
                "手动搜索番剧，修正海报和评分"
            ),
            SheetActionBean(
                SeriesAction.Clear,
                "恢复自动匹配",
                R.drawable.pw_ic_reset,
                "清掉当前结果，下次刷新重新刮削"
            )
        )

        BottomActionDialog(requireActivity(), actions, series.displayTitle) { bean ->
            when (bean.actionId) {
                SeriesAction.Match -> PosterMatchDialog(requireActivity(), series) { updated ->
                    viewModel.replaceSeries(updated)
                }.show()

                SeriesAction.Clear -> viewModel.clearMatch(series)
            }
            true
        }.show()
    }

    private fun refreshEmptyText() {
        val keyword = dataBinding.searchEt.text?.toString().orEmpty().trim()
        dataBinding.emptyTv.text = when {
            keyword.isNotEmpty() -> "没有找到包含「$keyword」的番剧"
            viewModel.isFiltering() -> "当前筛选条件下没有番剧"
            else -> "还没有刮削到番剧\n下拉可重新扫描，或先在「媒体库」添加 WebDAV 网盘"
        }
    }

    /**
     * 非官方签名构建必须配置 AppId / AppSecret，否则开放平台会拒绝请求（刮削、弹幕都拿不到数据）
     */
    private fun refreshAuthTips() {
        val configured = DevelopConfig.getAppId().isNullOrEmpty().not() &&
            DevelopConfig.getAppSecret().isNullOrEmpty().not()
        dataBinding.authTipsTv.isVisible = configured.not()
    }

    private fun spanCount(): Int {
        val widthDp = resources.configuration.screenWidthDp
        return (widthDp / 112).coerceIn(3, 8)
    }

    private fun openDetail(series: PosterSeries) {
        ARouter.getInstance()
            .build(RouteTable.Poster.Detail)
            .withString("seriesId", series.id)
            .navigation()
    }

    private enum class SeriesAction {
        Match,
        Clear
    }
}
