package com.xyoye.poster_component.data

/**
 * 番剧文件名 / 文件夹名解析。
 *
 * 目标：从下面这类典型命名里，稳定地拆出「标题 / 季度 / 集数」——
 *
 * ```
 * [Lilith-Raws] 葬送的芙莉莲 - 01 [Baha][WEB-DL][1080p][AVC AAC][CHT][MP4].mp4
 * [Nekomoe kissaten][Sousou no Frieren][02][1080p][JPSC].mp4
 * 进击的巨人 第二季 第05话.mkv
 * 孤独摇滚/S01/01.mp4
 * 孤独摇滚/Season 2/E03.mp4
 * ```
 *
 * 解析不了时不会抛异常，只会退化为「用文件夹名做标题 + 集数按顺序补」。
 */
object MediaTitleParser {

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "avi", "rmvb", "rm", "wmv", "flv", "mov", "ts", "m2ts", "mts",
        "webm", "m4v", "mpg", "mpeg", "iso", "3gp", "divx", "asf", "vob"
    )

    private val BRACKET_REGEXES = listOf(
        Regex("\\[[^]]*]"),
        Regex("【[^】]*】"),
        Regex("（[^）]*）"),
        Regex("\\([^)]*\\)")
    )

    /**
     * 括号里的内容。
     *
     * DBD-Raws / KTXP 这类命名会把**番名本身**放进括号：
     * `[DBD-Raws][异世界舅舅][01-13TV全集+特典映像][1080P]…`
     * 直接删括号会把标题一起删掉，所以要先留一份候选。
     */
    private val BRACKET_CONTENT_REGEX = Regex("\\[([^]]+)]|【([^】]+)】")

    /** 出现即判定为字幕组 / 压制组标签 */
    private val GROUP_HINTS = listOf(
        "raws", "labs", "sub", "team", "字幕组", "压制", "搬运", "&"
    )

    /** 出现即判定为标签而非标题 */
    private val NOISE_SUBSTRINGS = listOf(
        "全集", "特典", "映像", "外挂", "内封", "内嵌", "双语", "简繁", "字幕",
        "1080", "720", "2160", "4k", "hevc", "avc", "x264", "x265", "flac", "opus", "aac"
    )

    /** 技术规格 / 字幕组标签，命中后直接丢弃 */
    private val NOISE_WORDS = listOf(
        "1080p", "1080i", "720p", "2160p", "1440p", "480p", "360p", "4k", "8k",
        "hdr", "hdr10", "sdr", "dolby vision", "dv", "hlg",
        "bluray", "blu-ray", "bdrip", "bd", "webrip", "web-dl", "webdl", "web", "hdtv",
        "dvdrip", "dvd", "remux", "hdrip", "tvrip", "hdtc", "tc",
        "x264", "x265", "h264", "h265", "hevc", "avc", "av1", "vp9", "10bit", "8bit",
        "aac", "ac3", "eac3", "dts", "flac", "truehd", "atmos", "opus", "mp3",
        "chs", "cht", "gb", "gbk", "big5", "jpsc", "jptc", "sc", "tc",
        "简体", "繁体", "简繁", "中字", "中文", "日语", "双语", "中日双语", "内封", "内嵌",
        "外挂", "字幕", "全集", "已完结", "完结", "连载", "高清", "超清", "蓝光",
        "mp4", "mkv", "avi", "hi10p", "ma10p", "avc-10bit"
    )

    private val SEASON_REGEXES = listOf(
        Regex("(?i)(?:^|[\\s._\\-\\[【])s(\\d{1,2})(?:[\\s._\\-\\]】eE]|$)"),
        Regex("(?i)season\\s*(\\d{1,2})"),
        Regex("(?i)(\\d{1,2})(?:st|nd|rd|th)\\s*season"),
        Regex("第([一二三四五六七八九十\\d]{1,3})季"),
        Regex("第([一二三四五六七八九十\\d]{1,3})部")
    )

    /** 只描述季度、不含番名的文件夹名 */
    private val SEASON_FOLDER_REGEXES = listOf(
        Regex("(?i)^\\s*season\\s*(\\d{1,2})\\s*$"),
        Regex("(?i)^\\s*s(\\d{1,2})\\s*$"),
        Regex("(?i)^\\s*(\\d{1,2})(?:st|nd|rd|th)\\s*season\\s*$"),
        Regex("^\\s*第([一二三四五六七八九十\\d]{1,3})季\\s*$"),
        Regex("(?i)^\\s*part\\s*(\\d{1,2})\\s*$")
    )

    data class ParsedTitle(
        val title: String,
        val season: Int,
        val episode: Int
    )

    fun isVideoFile(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return extension.isNotEmpty() && extension in VIDEO_EXTENSIONS
    }

    fun removeExtension(fileName: String): String {
        val index = fileName.lastIndexOf('.')
        if (index <= 0) return fileName
        val extension = fileName.substring(index + 1).lowercase()
        return if (extension in VIDEO_EXTENSIONS) fileName.substring(0, index) else fileName
    }

    /**
     * 解析单个视频文件名。
     *
     * @param fileName 文件名（含扩展名）
     * @param fallbackTitle 解析不出标题时使用的兜底标题（通常是文件夹名，可能带一堆方括号）
     */
    fun parseFileName(fileName: String, fallbackTitle: String): ParsedTitle {
        val baseName = removeExtension(fileName)

        // 季度和集数要在清洗之前提取，因为 "[S02]"、"【第05话】" 这类信息在括号里
        var season = parseSeason(baseName)
        val episode = parseEpisode(baseName)

        val title = cleanTitleText(baseName)

        // 只剩纯数字或者太短，认为不是标题
        val useFallback = title.length < 2 || title.all { it.isDigit() }
        var finalTitle = title

        if (useFallback) {
            finalTitle = titleFromFallback(fallbackTitle)
            if (season == 0) {
                season = parseSeason(fallbackTitle)
            }
        }

        return ParsedTitle(
            title = finalTitle,
            season = season,
            episode = episode
        )
    }

    /**
     * 兜底标题清洗：先按常规规则洗，洗不出来再从括号里挑番名。
     *
     * `[DBD-Raws][异世界舅舅][01-13TV全集+特典映像][1080P]…` 这种命名，
     * 番名本身就在括号里，直接删括号会连标题一起删掉。
     */
    private fun titleFromFallback(fallbackTitle: String): String {
        val cleaned = cleanTitleText(fallbackTitle)
        if (cleaned.length >= 2 && cleaned.all { it.isDigit() }.not()) {
            return cleaned
        }

        return bestBracketTitle(fallbackTitle)?.let { cleanCandidate(it) }
            ?: fallbackTitle.trim()
    }

    /**
     * 把任意原始名清洗成「标题」：去括号、去字幕组/技术标签、去季度与集数标记。
     */
    private fun cleanTitleText(raw: String): String {
        var title = raw
        // 字幕组的「★10月新番★」这类装饰先整块干掉
        title = title.replace(Regex("★[^★]*★"), " ")
        title = title.replace(Regex("[★☆]"), " ")
        BRACKET_REGEXES.forEach { regex -> title = regex.replace(title, " ") }
        title = title.replace('_', ' ')
        title = title.replace(Regex("[\\[\\]【】（）()]"), " ")
        title = title.replace('.', ' ')

        NOISE_WORDS.forEach { word ->
            title = title.replace(
                Regex("(?i)(?<![a-z0-9])" + Regex.escape(word) + "(?![a-z0-9])"),
                " "
            )
        }

        title = title.replace(Regex("\\d{1,2}月新番"), " ")
        title = title.replace(Regex("(?i)\\bs\\d{1,2}e\\d{1,4}\\b"), " ")

        // 去掉残留的季度 / 集数标记
        title = title.replace(Regex("(?i)第\\s*[一二三四五六七八九十\\d]{1,3}\\s*[季部]"), " ")
        title = title.replace(Regex("(?i)\\bseason\\s*\\d{1,2}\\b"), " ")
        title = title.replace(Regex("(?i)\\b\\d{1,2}(?:st|nd|rd|th)\\s*season\\b"), " ")
        title = title.replace(Regex("(?i)\\bs\\d{1,2}\\b"), " ")
        title = title.replace(Regex("(?i)\\bep?\\s*\\d{1,4}\\b"), " ")
        title = title.replace(Regex("第\\s*\\d{1,4}\\s*[话話集]"), " ")
        // 结尾残留的集数（"番名 03" / "番名 - 12v2"）
        title = title.replace(Regex("(?:^|[\\s._\\-])v?\\d{1,4}(?:v\\d)?\\s*$"), " ")
        title = title.replace(Regex("[-–—~:：|/\\\\]+"), " ")
        return title.replace(Regex("\\s+"), " ").trim(' ', '-', '–', '—', '.')
    }

    /**
     * 从括号内容里挑出最像番名的一段（用于 `[组名][番名][01-13][1080P]` 这类命名）。
     */
    private fun bestBracketTitle(rawName: String): String? {
        return BRACKET_CONTENT_REGEX.findAll(rawName)
            .map { match -> match.groupValues[1].ifBlank { match.groupValues[2] }.trim() }
            .filter { isTitleLike(it) }
            .maxByOrNull { candidateScore(it) }
    }

    private fun isTitleLike(text: String): Boolean {
        if (text.length < 2) return false

        val normalized = normalize(text)
        if (normalized.isEmpty() || normalized.all { it.isDigit() }) return false

        val lower = text.lowercase()
        if (GROUP_HINTS.any { lower.contains(it) }) return false
        if (NOISE_SUBSTRINGS.any { lower.contains(it) }) return false

        // 01-13 / 01~12 这类集数区间
        if (Regex("^\\d{1,3}\\s*[-~～]\\s*\\d{1,3}").containsMatchIn(text)) return false

        // GB_CN / CHS / JPN 这类语言标记
        if (Regex("^[A-Za-z]{2,3}(_[A-Za-z]{2,3})?$").matches(text)) return false

        // 每个词都是技术标签（HEVC-10bit / AVC_AAC）
        val tokens = text.split(Regex("[^A-Za-z0-9\\u4e00-\\u9fff]+")).filter { it.isNotBlank() }
        if (tokens.isNotEmpty() && tokens.all { token ->
                NOISE_WORDS.any { normalize(it) == normalize(token) }
            }) {
            return false
        }

        return true
    }

    private fun candidateScore(text: String): Int {
        var score = 0
        val cjkCount = text.count { it.code in 0x4E00..0x9FFF }
        if (cjkCount >= 2) score += 30 + minOf(cjkCount, 10)

        val words = text.split(Regex("[\\s._\\-]+")).filter { it.isNotBlank() }
        if (words.size >= 2) score += 15
        if (text.length in 3..40) score += 5
        if (Regex("[A-Za-z]").containsMatchIn(text)) score += 3

        return score
    }

    private fun cleanCandidate(text: String): String {
        var title = text.replace('_', ' ')
        title = title.replace(Regex("(?i)第\\s*[一二三四五六七八九十\\d]{1,3}\\s*[季部]"), " ")
        title = title.replace(Regex("(?i)\\bseason\\s*\\d{1,2}\\b"), " ")
        title = title.replace(Regex("(?i)\\bs\\d{1,2}\\b"), " ")
        title = title.replace(Regex("[-–—~:：|/\\\\]+"), " ")
        return title.replace(Regex("\\s+"), " ").trim(' ', '-', '–', '—', '.')
    }

    /**
     * 如果文件夹名只表示季度（"Season 2" / "S02" / "第二季" / "Part 2"），返回季度号，否则返回 0。
     */
    fun parseSeasonFolder(folderName: String): Int {
        val name = folderName.trim()
        SEASON_FOLDER_REGEXES.forEach { regex ->
            val match = regex.find(name) ?: return@forEach
            val group = match.groupValues.getOrNull(1).orEmpty()
            val value = chineseNumberToInt(group)
            if (value in 1..30) return value
        }
        return 0
    }

    /** 文件夹名是否只是分辨率 / 编码 / "BD" 这类噪音目录 */
    fun isNoiseFolder(folderName: String): Boolean {
        val name = normalize(folderName)
        if (name.isEmpty()) return true
        if (name.all { it.isDigit() }) return true
        return NOISE_WORDS.any { normalize(it) == name }
    }

    fun normalize(title: String): String {
        val builder = StringBuilder()
        title.lowercase().forEach { char ->
            if (char.isLetterOrDigit()) {
                builder.append(char)
            }
        }
        return builder.toString()
    }

    /**
     * 标题相似度，0..100。用于在搜索接口的结果里挑最像的一部。
     */
    fun similarity(source: String, target: String): Int {
        val a = normalize(source)
        val b = normalize(target)
        if (a.isEmpty() || b.isEmpty()) return 0
        if (a == b) return 100
        if (a.contains(b) || b.contains(a)) {
            val shorter = minOf(a.length, b.length).toDouble()
            val longer = maxOf(a.length, b.length).toDouble()
            return (60 + 40 * shorter / longer).toInt()
        }
        // Dice 系数（双字组）
        val bigramsA = bigrams(a)
        val bigramsB = bigrams(b)
        if (bigramsA.isEmpty() || bigramsB.isEmpty()) return 0
        var overlap = 0
        val remaining = bigramsB.toMutableList()
        bigramsA.forEach { gram ->
            if (remaining.remove(gram)) {
                overlap++
            }
        }
        val dice = 2.0 * overlap / (bigramsA.size + bigramsB.size)
        return (dice * 100).toInt()
    }

    private fun bigrams(text: String): List<String> {
        if (text.length < 2) return listOf(text)
        val list = ArrayList<String>(text.length)
        for (index in 0 until text.length - 1) {
            list.add(text.substring(index, index + 2))
        }
        return list
    }

    /**
     * 从标题里解析季度标记（`第X季` / `Season X` / `S0X`），没有则返回 0。
     */
    fun seasonMarker(title: String): Int {
        Regex("第([一二三四五六七八九十\\d]{1,3})季").find(title)?.let { match ->
            val text = match.groupValues[1]
            text.toIntOrNull()?.let { return it }
            return chineseNumberToInt(text)
        }
        Regex("(?i)season\\s*(\\d{1,2})").find(title)?.let { match ->
            match.groupValues[1].toIntOrNull()?.let { return it }
        }
        Regex("(?i)\\bs(\\d{1,2})\\b").find(title)?.let { match ->
            match.groupValues[1].toIntOrNull()?.let { return it }
        }
        return 0
    }

    /**
     * 标题是否「纯拉丁」（罗马音 / 英文）。
     * 含汉字 / 假名 / 韩文就不算，避免把中文番名当罗马音处理。
     */
    fun isLatinTitle(title: String): Boolean {
        if (title.isBlank()) return false

        var hasLetter = false
        title.forEach { char ->
            when {
                char.code in 0x4E00..0x9FFF -> return false
                char.code in 0x3400..0x4DBF -> return false
                char.code in 0x3040..0x30FF -> return false
                char.code in 0xAC00..0xD7AF -> return false
                char.isLetter() && char.code < 0x0250 -> hasLetter = true
            }
        }
        return hasLetter
    }

    private fun parseSeason(name: String): Int {
        SEASON_REGEXES.forEach { regex ->
            val match = regex.find(name) ?: return@forEach
            val group = match.groupValues.getOrNull(1).orEmpty()
            val value = chineseNumberToInt(group)
            if (value in 1..30) return value
        }
        return 0
    }

    private fun parseEpisode(name: String): Int {
        // 1. 明确的集数标记
        Regex("第\\s*(\\d{1,4})\\s*[话話集]").find(name)?.let {
            return it.groupValues[1].toIntOrNull() ?: 0
        }
        // 2. SxxExx
        Regex("(?i)\\bs\\d{1,2}e(\\d{1,4})\\b").find(name)?.let {
            return it.groupValues[1].toIntOrNull() ?: 0
        }
        // 3. EPxx / Exx
        Regex("(?i)\\bep?\\s*(\\d{1,4})\\b").find(name)?.let {
            return it.groupValues[1].toIntOrNull() ?: 0
        }
        Regex("(?i)\\be(\\d{1,4})\\b").find(name)?.let {
            return it.groupValues[1].toIntOrNull() ?: 0
        }

        // 4. 方括号里只有数字：`[02]` / `【05】` 这类是标准集数写法
        val bracketEpisodes = Regex("(?i)[\\[【]\\s*(\\d{1,4})(?:v\\d)?\\s*[\\]】]").findAll(name)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 1..1999 && it !in RESOLUTIONS }
            .filter { it < 1900 || it > 2099 }
            .toList()
        if (bracketEpisodes.isNotEmpty()) return bracketEpisodes.last()

        // 括号里的内容（字幕组标签，如 "[Sakurato&343-Labs]"）不参与集数判断，
        // 否则标签里的 343 会被当成第 343 集
        val noBrackets = name.replace(
            Regex("\\[[^]]*]|【[^】]*】|（[^）]*）|\\([^)]*\\)"), " "
        ).replace(Regex("\\s+"), " ").trim()

        // 5. 末尾 " - 01" / " 01" / "01v2"
        Regex("(?:^|[\\s._\\-\\]])(\\d{1,4})(?:v\\d)?\\s*$").find(noBrackets)?.let {
            val value = it.groupValues[1].toIntOrNull() ?: 0
            if (value in 1..1999) return value
        }

        // 5. 任何位置最后一个「独立」数字（排除 MP4 / H264 / 1080p 这类粘连字母的数字）
        val candidates = Regex("(?i)(?<![a-z0-9])(\\d{1,4})(?![a-z0-9])").findAll(noBrackets)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 1..1999 && it !in RESOLUTIONS }
            .filter { it < 1900 || it > 2099 }
            .toList()
        return candidates.lastOrNull() ?: 0
    }

    private val RESOLUTIONS = setOf(360, 480, 576, 720, 1080, 1440, 2160, 3840, 4320)

    private fun chineseNumberToInt(text: String): Int {
        if (text.isEmpty()) return 0
        text.toIntOrNull()?.let { return it }
        var result = 0
        var pending = 0
        text.forEach { char ->
            when (char) {
                '一' -> pending = 1
                '二' -> pending = 2
                '三' -> pending = 3
                '四' -> pending = 4
                '五' -> pending = 5
                '六' -> pending = 6
                '七' -> pending = 7
                '八' -> pending = 8
                '九' -> pending = 9
                '十' -> {
                    result += if (pending == 0) 10 else pending * 10
                    pending = 0
                }
                else -> {}
            }
        }
        return result + pending
    }
}
