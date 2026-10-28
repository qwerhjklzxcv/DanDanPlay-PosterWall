package com.xyoye.poster_component

import com.xyoye.poster_component.data.PosterTitle
import com.xyoye.poster_component.data.TitleLocalizer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * 官方标题按系统语言选择的回归测试。
 *
 * 注意 language 字段是弹弹play 实际返回的中文描述，不是 BCP-47。
 */
class TitleLocalizerTest {

    private val titles = mutableListOf(
        PosterTitle("主标题", "超自然武装当哒当"),
        PosterTitle("主标题，日语罗马字", "Dan Da Dan"),
        PosterTitle("别名，英语", "Dandadan"),
        PosterTitle("官方标题，日语", "ダンダダン"),
        PosterTitle("官方标题，英语", "Dan Da Dan"),
        PosterTitle("官方标题，繁体中文", "膽大黨"),
        PosterTitle("官方标题，简体中文", "胆大党")
    )

    private lateinit var origin: Locale

    @Before
    fun setUp() {
        origin = Locale.getDefault()
    }

    @After
    fun tearDown() {
        Locale.setDefault(origin)
    }

    @Test
    fun `chinese simplified system picks simplified official title`() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        assertEquals("胆大党", TitleLocalizer.pick(titles, "fallback"))
    }

    @Test
    fun `chinese traditional system picks traditional official title`() {
        Locale.setDefault(Locale.TRADITIONAL_CHINESE)
        assertEquals("膽大黨", TitleLocalizer.pick(titles, "fallback"))
    }

    @Test
    fun `english system picks english official title, not alias`() {
        Locale.setDefault(Locale.ENGLISH)
        assertEquals("Dan Da Dan", TitleLocalizer.pick(titles, "fallback"))
    }

    @Test
    fun `japanese system picks japanese official title, not romaji`() {
        Locale.setDefault(Locale.JAPANESE)
        assertEquals("ダンダダン", TitleLocalizer.pick(titles, "fallback"))
    }

    @Test
    fun `missing language falls back down the chain`() {
        Locale.setDefault(Locale.KOREAN)
        // 韩语没有对应标题 → 回退到英文官方标题
        assertEquals("Dan Da Dan", TitleLocalizer.pick(titles, "fallback"))

        // 只剩中文主标题时，仍能拿到中文
        val onlyPrimary = mutableListOf(PosterTitle("主标题", "碧蓝之海"))
        assertEquals("碧蓝之海", TitleLocalizer.pick(onlyPrimary, "fallback"))
    }

    @Test
    fun `no titles uses fallback`() {
        Locale.setDefault(Locale.ENGLISH)
        assertEquals("文件夹名", TitleLocalizer.pick(emptyList(), "文件夹名"))
    }
}
