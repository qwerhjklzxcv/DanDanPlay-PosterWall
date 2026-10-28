package com.xyoye.poster_component.ui.dialog

import android.app.Activity
import android.view.View
import coil.load
import com.xyoye.common_component.adapter.addItem
import com.xyoye.common_component.adapter.buildAdapter
import com.xyoye.common_component.extension.setData
import com.xyoye.common_component.extension.vertical
import com.xyoye.common_component.network.repository.AnimeRepository
import com.xyoye.common_component.weight.dialog.BaseBottomDialog
import com.xyoye.data_component.data.AnimeData
import com.xyoye.poster_component.R
import com.xyoye.poster_component.data.PosterRepository
import com.xyoye.poster_component.data.PosterScraper
import com.xyoye.poster_component.data.PosterSeries
import com.xyoye.poster_component.databinding.DialogPosterMatchBinding
import com.xyoye.poster_component.databinding.ItemMatchAnimeBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 重新匹配弹窗：手动搜索番剧，覆盖自动刮削的结果。
 */
class PosterMatchDialog(
    private val hostActivity: Activity,
    private val series: PosterSeries,
    private val onMatched: (PosterSeries) -> Unit
) : BaseBottomDialog<DialogPosterMatchBinding>(hostActivity) {

    private lateinit var binding: DialogPosterMatchBinding

    // 用弹窗自己的 scope，关闭时取消，避免回调到已关闭的界面
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun getChildLayoutId() = R.layout.dialog_poster_match

    override fun initView(binding: DialogPosterMatchBinding) {
        this.binding = binding

        setTitle("重新匹配 · ${series.rawTitle}")
        setPositiveVisible(false)
        setNegativeText("关闭")
        setNegativeListener { dismiss() }
        disableSheetDrag()

        initRecyclerView()

        binding.matchKeywordEt.setText(series.rawTitle)
        binding.matchSearchBt.setOnClickListener { search() }

        search()
    }

    override fun onStop() {
        scope.cancel()
        super.onStop()
    }

    private fun initRecyclerView() {
        binding.matchRv.apply {
            layoutManager = vertical()
            adapter = buildAdapter {
                addItem<AnimeData, ItemMatchAnimeBinding>(R.layout.item_match_anime) {
                    initView { data, _, _ ->
                        itemBinding.matchTitleTv.text = data.animeTitle
                        itemBinding.matchSubtitleTv.text = buildString {
                            val year = data.startDate?.take(4).orEmpty()
                            if (year.isNotEmpty()) append(year)
                            if (data.rating > 0) {
                                if (isNotEmpty()) append(" · ")
                                append(String.format(Locale.US, "%.1f 分", data.rating))
                            }
                        }

                        val cover = data.imageUrl
                        if (cover.isNullOrBlank()) {
                            itemBinding.matchCoverIv.setImageResource(R.drawable.pw_poster_placeholder)
                        } else {
                            itemBinding.matchCoverIv.load(cover)
                        }

                        itemBinding.root.setOnClickListener {
                            applyMatch(data)
                        }
                    }
                }
            }
        }
    }

    private fun search() {
        val keyword = binding.matchKeywordEt.text?.toString()?.trim().orEmpty()
        if (keyword.isEmpty()) return

        binding.matchLoadingPb.visibility = View.VISIBLE
        binding.matchTipTv.text = "搜索中…"

        scope.launch {
            val result = try {
                AnimeRepository.searchAnime(keyword, null)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
            val animes = result?.getOrNull()?.animes.orEmpty()

            withContext(Dispatchers.Main) {
                if (isShowing.not()) return@withContext
                binding.matchLoadingPb.visibility = View.GONE
                binding.matchTipTv.text = if (animes.isEmpty()) {
                    "没有搜到，换个关键词试试"
                } else {
                    "共 ${animes.size} 个结果，点一下即可绑定"
                }
                binding.matchRv.setData(animes)
            }
        }
    }

    private fun applyMatch(anime: AnimeData) {
        // 补全详情（封面/评分/多语言标题），再落盘
        scope.launch {
            val updated = try {
                PosterScraper.applyAnime(series, anime.animeId, anime.animeTitle)
            } catch (e: Exception) {
                e.printStackTrace()
                series.copy(
                    animeId = anime.animeId,
                    animeTitle = anime.animeTitle,
                    posterUrl = anime.imageUrl,
                    rating = anime.rating,
                    matched = true,
                    lastUpdate = System.currentTimeMillis()
                )
            }

            PosterRepository.updateMatched(updated)

            withContext(Dispatchers.Main) {
                onMatched.invoke(updated)
                dismiss()
            }
        }
    }
}
