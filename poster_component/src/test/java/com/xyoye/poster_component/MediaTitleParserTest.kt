package com.xyoye.poster_component

import com.xyoye.poster_component.data.MediaTitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命名解析的回归测试。跑法：
 *
 * ```
 * ./gradlew :poster_component:testDebugUnitTest
 * ```
 *
 * 这些用例就是「刮削准确率」的地基，改动解析规则后先跑一遍。
 */
class MediaTitleParserTest {

    @Test
    fun `subgroup prefix with quality tags`() {
        val parsed = MediaTitleParser.parseFileName(
            "[Lilith-Raws] 葬送的芙莉莲 - 01 [Baha][WEB-DL][1080p][AVC AAC][CHT][MP4].mp4",
            "葬送的芙莉莲"
        )
        assertEquals("葬送的芙莉莲", parsed.title)
        assertEquals(1, parsed.episode)
    }

    @Test
    fun `english name with bracket episode`() {
        val parsed = MediaTitleParser.parseFileName(
            "[Nekomoe kissaten][Sousou no Frieren][02][1080p][JPSC].mp4",
            "Sousou no Frieren"
        )
        assertEquals("Sousou no Frieren", parsed.title)
        assertEquals(2, parsed.episode)
    }

    @Test
    fun `chinese season marker`() {
        val parsed = MediaTitleParser.parseFileName("进击的巨人 第二季 第05话.mkv", "进击的巨人")
        assertEquals("进击的巨人", parsed.title)
        assertEquals(2, parsed.season)
        assertEquals(5, parsed.episode)
    }

    @Test
    fun `bare episode falls back to folder name`() {
        val parsed = MediaTitleParser.parseFileName("01.mp4", "孤独摇滚")
        assertEquals("孤独摇滚", parsed.title)
        assertEquals(1, parsed.episode)
    }

    @Test
    fun `sxxexx naming`() {
        val parsed = MediaTitleParser.parseFileName("Show.S02E05.1080p.mkv", "Show")
        assertEquals(2, parsed.season)
        assertEquals(5, parsed.episode)
        assertEquals("Show", parsed.title)
    }

    @Test
    fun `version suffix`() {
        val parsed = MediaTitleParser.parseFileName("葬送的芙莉莲 - 12v2 [1080p].mkv", "葬送的芙莉莲")
        assertEquals("葬送的芙莉莲", parsed.title)
        assertEquals(12, parsed.episode)
    }

    @Test
    fun `seasonal promo decoration`() {
        val parsed = MediaTitleParser.parseFileName(
            "【喵萌奶茶屋】★10月新番★[药屋少女的呢喃][01][1080p][简日双语].mp4",
            "药屋少女的呢喃"
        )
        assertEquals("药屋少女的呢喃", parsed.title)
        assertEquals(1, parsed.episode)
    }

    @Test
    fun `chinese and english mixed name`() {
        val parsed = MediaTitleParser.parseFileName(
            "[ANi] SPY×FAMILY 间谍过家家 - 03 [1080P][Baha][WEB-DL][AAC AVC][CHT].mp4",
            "SPY×FAMILY"
        )
        assertTrue(parsed.title.contains("SPY"))
        assertTrue(parsed.title.contains("间谍过家家"))
        assertEquals(3, parsed.episode)
    }

    @Test
    fun `season folder detection`() {
        assertEquals(2, MediaTitleParser.parseSeasonFolder("Season 2"))
        assertEquals(3, MediaTitleParser.parseSeasonFolder("S03"))
        assertEquals(2, MediaTitleParser.parseSeasonFolder("第二季"))
        assertEquals(2, MediaTitleParser.parseSeasonFolder("Part 2"))
        assertEquals(0, MediaTitleParser.parseSeasonFolder("1080p"))
        assertEquals(0, MediaTitleParser.parseSeasonFolder("葬送的芙莉莲"))
    }

    @Test
    fun `noise folder detection`() {
        assertTrue(MediaTitleParser.isNoiseFolder("1080p"))
        assertTrue(MediaTitleParser.isNoiseFolder("BD"))
        assertFalse(MediaTitleParser.isNoiseFolder("我的番剧收藏"))
    }

    @Test
    fun `similarity scoring`() {
        assertEquals(100, MediaTitleParser.similarity("葬送的芙莉莲", "葬送的芙莉莲"))
        assertTrue(MediaTitleParser.similarity("葬送的芙莉莲", "葬送的芙莉莲 第二季") >= 60)
        assertTrue(MediaTitleParser.similarity("药屋少女的呢喃", "孤独摇滚") < 40)
    }

    @Test
    fun `title inside bracket folder name`() {
        // DBD-Raws 这类命名：番名本身在括号里
        val folder = "[DBD-Raws][异世界舅舅][01-13TV全集+特典映像][1080P][BDRip][HEVC-10bit][简繁日双语外挂][FLAC][MKV]"
        val parsed = MediaTitleParser.parseFileName("01.mkv", folder)
        assertEquals("异世界舅舅", parsed.title)
    }

    @Test
    fun `season inside bracket folder name`() {
        val folder = "[KTXP][Grand_Blue_Dreaming_S2][GB_CN][HEVC_opus][1080p]"
        val parsed = MediaTitleParser.parseFileName("01.mkv", folder)
        assertEquals("Grand Blue Dreaming", parsed.title)
        assertEquals(2, parsed.season)
    }

    @Test
    fun `subgroup tag number is not an episode`() {
        // [Sakurato&343-Labs] 里的 343 曾经被当成第 343 集
        val folder = "[BDrip] Dan Da Dan S01 [Sakurato&343-Labs]"
        val parsed = MediaTitleParser.parseFileName(folder, folder)
        assertEquals("Dan Da Dan", parsed.title)
        assertEquals(1, parsed.season)
        assertEquals(0, parsed.episode)
    }

    @Test
    fun `romaji name with subgroup prefix`() {
        val parsed = MediaTitleParser.parseFileName(
            "[Sakurato&343-Labs] Dan Da Dan - 01 [1080p][CHS].mkv",
            "Dan Da Dan"
        )
        assertEquals("Dan Da Dan", parsed.title)
        assertEquals(1, parsed.episode)
    }

    @Test
    fun `romaji title is recognized as latin`() {
        assertTrue(MediaTitleParser.isLatinTitle("Dan Da Dan"))
        assertTrue(MediaTitleParser.isLatinTitle("Sono Bisque Doll wa Koi wo Suru"))
        assertFalse(MediaTitleParser.isLatinTitle("胆大党"))
        assertFalse(MediaTitleParser.isLatinTitle("ダンダダン"))
    }
}
