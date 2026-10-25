package com.xyoye.poster_component.data

/**
 * 海报墙排序方式
 */
enum class PosterSort(val label: String) {
    RECENT("最近更新"),
    NAME("名称"),
    RATING("评分"),
    EPISODES("集数"),
    YEAR("年份")
}

/**
 * 海报墙筛选条件
 */
enum class PosterFilter(val label: String) {
    ALL("全部"),
    MATCHED("已匹配"),
    UNMATCHED("未匹配"),
    WATCHED("有进度")
}
