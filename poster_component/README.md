# poster_component — 海报墙

给 DanDanPlay 4.1.4 新增的**底部导航第 4 个页面**：自动扫描你已连接的 WebDAV 网盘，
按「番剧 + 季度」聚合成系列，再去弹弹play 自己的番剧库刮削海报/评分，最终呈现成海报墙。

对标网易爆米花的资源库体验，但复用 App 现有能力，不引入新的账号体系。

---

## 1. 它是怎么工作的

```
WebDAV 网盘
   │  ① Storage 抽象层列目录（复用 App 已存好的账号，不重新鉴权）
   ▼
PosterScanner ── 递归遍历 ──► 按「标题 + 季度」聚合
   │  ② MediaTitleParser 解析文件名 / 文件夹名
   ▼
PosterSeries（folderPath + 集数列表）
   │  ③ PosterScraper 调 /api/v2/search/anime
   ▼
海报 / 评分 / animeId  ──►  JSON 缓存（filesDir/poster_wall）
   │
   ▼
海报墙（网格）── 点击 ──► 详情页（简介 + 选集）── 点击 ──► 走 App 原生播放器
```

关键点：

- **不重做 WebDAV 连接**。`StorageFactory.createStorage(library)` 直接拿到 App 已经配好的 WebDAV 实例，
  账号密码从 `media_library` 表里来。
- **播放走原链路**。详情页点集数 → `storage.openDirectory()` 打开该系列目录 →
  `StorageVideoSourceFactory.create(file)` → `RouteTable.Player.Player`。
  因此续播进度、同名字幕、同目录弹幕、播放器一切既有能力都保留。
- **缓存用 JSON 文件而不是 Room**，不碰数据库版本号，删目录即复位。

## 2. 目录结构

```
poster_component/src/main/java/com/xyoye/poster_component/
├─ data/
│  ├─ PosterModels.kt        PosterSeries / PosterEpisode（含 JSON 序列化）
│  ├─ MediaTitleParser.kt    文件名/文件夹名 → 标题 + 季度 + 集数
│  ├─ PosterScanner.kt       递归扫描 + 聚合（深度/目录数/视频数都有上限）
│  ├─ PosterScraper.kt       搜索接口 + 相似度打分 + 季度消歧
│  ├─ PosterCacheStore.kt    本地 JSON 缓存
│  └─ PosterRepository.kt    串起「扫描 → 刮削 → 缓存」，带互斥锁
└─ ui/
   ├─ wall/PosterWallFragment.kt     海报墙（底部导航第 4 页）
   ├─ wall/PosterWallViewModel.kt
   ├─ detail/PosterDetailActivity.kt 详情页（海报/评分/简介/选集/播放）
   └─ detail/PosterDetailViewModel.kt
```

## 3. 对原工程的改动清单

新增（全部在新目录里，除了图标）：

| 文件 | 说明 |
|---|---|
| `poster_component/**` | 新模块 |
| `app/src/main/res/drawable/ic_main_poster.xml` | 底栏图标 |

修改（4 处，都很小）：

| 文件 | 改动 |
|---|---|
| `settings.gradle.kts` | `include(":poster_component")` |
| `app/build.gradle.kts` | `implementation(project(":poster_component"))` |
| `common_component/.../config/RouteTable.kt` | 新增 `Poster.WallFragment` / `Poster.Detail` 路由 |
| `app/.../ui/main/MainActivity.kt` | 新增 `TAG_FRAGMENT_POSTER` 与第 4 个分支 |
| `app/src/main/res/menu/main_bottom_navigation.xml` | 新增「海报墙」item |
| `buildSrc/.../ProjectExtension.kt` | `currentCommit()` 容错：没有 `.git` 时返回 `unknown`，不再导致配置失败 |

## 4. 编译

环境要求同原工程：JDK 11+、Android SDK 33。

```bash
# 1) 源码包解压后一般没有 .git，先初始化一次（buildSrc 会读 commit，已做容错，但建议有 git）
git init && git add . && git commit -m "init"

# 2) 编译
./gradlew :app:assembleDebug          # 或 Android Studio 直接 Run
```

产物：`app/build/outputs/apk/`。

> 因为签名变了，手机上要先卸载官方版再安装自编译版；应用内更新也会失效（可关掉自动更新）。

## 5. 验证清单

> 底栏顺序：首页 / **海报墙** / 媒体库 / 我的；App 启动默认落在**海报墙**。

0. 先完成「开发者认证」（⋮ 菜单 → 未认证 → 填 AppId / AppSecret）。
   非官方签名的包不带凭证时，`api.dandanplay.net` 会直接 403，**刮削和弹幕都会失效**。
   没有认证时，海报墙顶部会显示橙色提示条，点击跳转开发者中心。
1. 打开 App → 切到「媒体库」→ 添加一个 WebDAV 网盘（如果还没加）。
2. 回到「海报墙」→ 首次会自动扫描，顶部显示「扫描 / 识别 / 刮削」进度。
3. 扫完应看到海报网格；没有匹配到海报的会显示灰底占位 + 解析出的标题。
4. 点任意一部 → 详情页 → 点集数 → 应直接进入播放器，并且上一集/下一集可用。
5. 杀掉进程再进「海报墙」，应直接出现上次的结果（读缓存），不会白屏。

## 6. 海报墙能力

| 功能 | 实现位置 |
|---|---|
| 搜索（边打边过滤） | `PosterWallViewModel.emit()` + 顶部 `search_et` |
| 排序：最近更新 / 名称 / 评分 / 集数 / 年份 | `PosterSort` + `sort_tv` 弹窗 |
| 筛选：全部 / 已匹配 / 未匹配 / 有进度 | `PosterFilter` + `filter_tv` 弹窗 |
| 卡片观看进度「看到 5/12」 | `PosterRepository.applyWatchProgress()` 读 `play_history` |
| 长按重新匹配（搜索 + 点选绑定） | `PosterMatchDialog` + `PosterScraper.applyAnime()` |
| 恢复自动匹配 | `PosterRepository.clearMatch()` |
| 空态引导添加网盘 | `addStorageBt` → `RouteTable.Stream.StoragePlus` |
| 刮削三级缓存（文件名/关键词/详情） | `ScrapeCacheStore` |
| 番剧名按系统语言切换 | `TitleLocalizer` + `BangumiData.titles` |
| 扫描范围：全部网络媒体库 | `PosterRepository.SCAN_MEDIA_TYPES` |

## 7. 现在的能力边界（重要）

已经能用，但下面几条是刻意留的"下一轮"：

| 限制 | 影响 | 建议 |
|---|---|---|
| 扫描是顺序列目录 | 大库（几千文件）首次扫描慢，可能几分钟 | 下一版改成按目录并发 + 断点续扫 |
| 中文标题按 Unicode 排序 | 「名称」排序不是拼音序 | 要拼音序就引 pinyin4j |
| 不生成视频缩略图 | 没刮到的显示灰底 | 可用 `coil-video` 取首帧，或用 App 已有的封面缓存机制 |
| 详情页还没有"继续观看"定位 | 总是从第一集入口进 | 可读 `lastWatchedEpisode` 直接定位那一集 |


## 8. 解析规则改哪里

`MediaTitleParser` 是准确率的核心，规则集中在这几处：

- `NOISE_WORDS`：1080p / x265 / CHT / 简体…… 命中的词从标题里剔除
- `SEASON_REGEXES` / `SEASON_FOLDER_REGEXES`：`S02`、`Season 2`、`第二季`、`Part 2`
- `parseEpisode()`：`第01话` → `S02E05` → `EP05` → 结尾数字 → 最后一个独立数字
- 改完跑 `./gradlew :poster_component:testDebugUnitTest`，`MediaTitleParserTest` 里是回归用例
