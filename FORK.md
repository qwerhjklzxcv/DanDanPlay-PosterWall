# DanDanPlay-PosterWall（弹弹play 海报墙版）

> **非官方项目**。本仓库是 [xyoye/DanDanPlayForAndroid](https://github.com/xyoye/DanDanPlayForAndroid)
> （Apache-2.0）的个人二次开发分支，**与弹弹play 官方无关**，不代表官方立场，不用于商业用途。

## 这个分支做了什么

在原版 4.1.4 基础上，新增「WebDAV 网盘自动刮削 + 海报墙」：

| 改动 | 说明 |
|---|---|
| 新增模块 `poster_component` | 海报墙、详情页、扫描/解析/刮削流水线 |
| 底栏新增「海报墙」Tab | 顺序：首页 / **海报墙** / 媒体库 / 我的 |
| 默认落地页改为海报墙 | 原版默认进入媒体库 |
| 新增路由 | `/poster/wall_fragment`、`/poster/detail` |

功能链路：

```
WebDAV 媒体库（复用 App 已保存的账号，不重复鉴权）
  → Storage 抽象层递归列目录
  → 文件名解析（标题 / 季度 / 集数，支持中英混排、字幕组前缀、SxxExx、第二季…）
  → 按「标题 + 季度」聚合成系列
  → 调用 /api/v2/search/anime 刮削海报、评分
  → 本地 JSON 缓存 → 海报墙网格 → 详情页 → 复用原版播放器（续播/上下集/弹幕）
```

## 与原版的差异（技术细节）

| 文件 | 改动 |
|---|---|
| `poster_component/**` | 新增模块（数据层 + 海报墙 + 详情页 + 重新匹配弹窗 + 单元测试） |
| `app/src/main/res/menu/main_bottom_navigation.xml` | 新增「海报墙」菜单项，调整顺序 |
| `app/src/main/res/drawable/ic_main_poster.xml` | 新增底栏图标 |
| `app/.../ui/main/MainActivity.kt` | 新增第 4 个 Fragment 分支；默认页改为海报墙 |
| `common_component/.../config/RouteTable.kt` | 新增 `Poster` 路由常量 |
| `app/build.gradle.kts`、`settings.gradle.kts` | 引入新模块 |
| **`buildSrc/.../Versions.kt`** | **`applicationId` 改为 `com.xyoye.dandanplay.posterwall`（独立包名，可与官方版共存）** |
| **`app/.../values/strings.xml`** | **应用名改为 `DanDanPlay-PosterWall`** |
| `build.gradle.kts` | 增加阿里云 Maven 镜像（国内构建可用） |
| `buildSrc/.../ProjectExtension.kt` | `currentCommit()` 容错：无 `.git` 时返回 `unknown` |
| `gradle.properties` | 移除 `-XX:MaxPermSize`（JDK 17 下该参数致命） |

完整说明见 [`poster_component/README.md`](poster_component/README.md)。

## 构建

```bash
# 需要 JDK 17、Android SDK 33
./gradlew :app:assembleDebug
```

> 自编译包使用仓库自带的 debug 签名（`CN=xyoye`）。包名已与官方区分
> （`com.xyoye.dandanplay.posterwall`），因此可以和官方版同时安装。
> 非官方签名必须配置开放平台凭证：App 内「⋮ → 开发者认证」，填写自己的 AppId / AppSecret，
> 否则弹幕匹配与刮削会被服务端拒绝（403）。凭证申请见 <https://dev.dandanplay.com/>。

## 许可证与声明

- 原项目采用 **Apache License 2.0**，本分支同样遵循该许可证，保留原始 `LICENSE` 与版权声明。
- 本分支对原代码的修改均已在上文与提交记录中标注（Apache-2.0 §4(b)）。
- 本项目仅用于个人学习与自用，不提供任何收费服务，不用于商业用途。
- 弹幕数据来源为「弹弹play 开放弹幕网络」，使用遵循其[使用约定](https://doc.dandanplay.com/open/)。
- 名称中的 “DanDanPlay / 弹弹play” 仅用于说明来源，非官方发布。
