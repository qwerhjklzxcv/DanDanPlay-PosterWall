package com.xyoye.poster_component.data

import java.util.Locale

/**
 * 按系统语言挑选官方番剧名。
 *
 * 注意：弹弹play 的 `titles[].language` **不是** BCP-47 标签，而是中文描述，实测有：
 *
 * ```
 * 主标题
 * 主标题，日语罗马字
 * 别名，英语
 * 官方标题，日语
 * 官方标题，英语
 * 官方标题，简体中文
 * 官方标题，繁体中文
 * ```
 *
 * 所以这里先按「语言 + 质量」解析每条标题，再按系统语言偏好挑：
 * 官方标题 > 别名 > 主标题；同语言取质量最高的那条。
 * 目标语言缺标题时按 日文 → 中文 → 英文 回退，最后退回刮削标题。
 */
object TitleLocalizer {

    private data class Entry(val language: String, val quality: Int, val index: Int)

    fun pick(titles: List<PosterTitle>, fallback: String?): String {
        val entries = titles.mapIndexedNotNull { index, title -> parse(title, index) }
        if (entries.isEmpty()) return fallback.orEmpty()

        preferenceChain().forEach { language ->
            entries.filter { it.language == language }
                .maxByOrNull { it.quality }
                ?.let { return titles[it.index].title }
        }

        return fallback.orEmpty().ifBlank { titles.first().title }
    }

    private fun parse(title: PosterTitle, index: Int): Entry? {
        if (title.title.isBlank()) return null

        val label = title.language
        val quality = when {
            label.contains("官方标题") -> 100
            label.contains("别名") -> 80
            label.contains("主标题") -> 70
            else -> 50
        }

        val language = when {
            label.contains("简体") -> "zh-Hans"
            label.contains("繁体") -> "zh-Hant"
            label.contains("罗马字") -> "romaji"
            label.contains("英语") || label.contains("英文") -> "en"
            label.contains("日语") || label.contains("日文") -> "ja"
            else -> "primary"
        }

        return Entry(language, quality, index)
    }

    /**
     * 系统语言偏好链
     */
    private fun preferenceChain(): List<String> {
        val locale = Locale.getDefault()
        val language = locale.language.lowercase(Locale.ROOT)
        val country = locale.country.uppercase(Locale.ROOT)
        val traditional = country in setOf("TW", "HK", "MO") ||
                locale.script.contains("Hant", ignoreCase = true)

        return when (language) {
            "zh" -> if (traditional) {
                listOf("zh-Hant", "zh-Hans", "primary", "ja", "en", "romaji")
            } else {
                listOf("zh-Hans", "zh-Hant", "primary", "ja", "en", "romaji")
            }

            "ja" -> listOf("ja", "zh-Hans", "zh-Hant", "primary", "en", "romaji")
            "en" -> listOf("en", "romaji", "zh-Hans", "zh-Hant", "primary", "ja")
            else -> listOf(language, "en", "romaji", "zh-Hans", "zh-Hant", "primary", "ja")
        }
    }
}
