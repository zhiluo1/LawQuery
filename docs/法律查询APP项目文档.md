# 法律查询 App「法条速查」项目文档(技术方案与工程说明)

| 项目 | 内容 |
|---|---|
| 文档版本 | v1.0(2026-10-04) |
| 配套文档 | [法律查询APP需求文档.md](./法律查询APP需求文档.md) v1.0(需求、合规红线、验收标准的唯一事实来源) |
| 产品代号 | 法条速查(LawQuery),包名 `com.lawquery`(占位,可改) |
| 文档用途 | 描述系统的技术架构、目录结构、交互约定、模块职责与环境启动方式,供 vibecoding AI 辅助开发直接引用 |

> **给 AI 编程工具的使用说明**:本文档回答"怎么建、怎么连、怎么跑";需求文档回答"做什么、做到什么程度算合格"。两份文档冲突时以需求文档为准。实施顺序遵循需求文档第 8 章 M0–M7;每个模块开工前先读本文档第 3、4、5 章对应小节。

---

## 1. 系统总体架构

### 1.1 前端与后端的界定(重要)

本项目 **不自建后端服务**。"前端 / 后端"在本项目中的含义:

| 角色 | 承载方 | 说明 |
|---|---|---|
| **前端(客户端)** | Android 原生 App(Kotlin + Jetpack Compose) | 全部 UI、业务逻辑、解析、限流、元数据存储都在端上完成 |
| **后端(数据层)** | 官方网站的 HTTP 服务(国家法律法规数据库、中国政府网政策文件库、最高人民法院官网等) | 唯一的数据来源。App 以普通 HTTP 客户端 / WebView 两种方式直连,**中间无任何代理层** |
| 本地持久层 | Room + DataStore(设备端) | 仅存元数据(收藏、最近浏览、源健康、设置),**不存任何条文正文** |

不自建后端的三个依据(均出自需求文档):

1. **合规**:flk.npc.gov.cn 的 robots.txt 禁止任何自动化采集;若经自建服务器聚合抓取再分发给 App,将从"用户触发单次获取"变为"服务器批量采集 + 再分发",触碰需求文档 7.2 合规红线第 4 条;
2. **实时性原则**:"所有条文实时取自官方"要求端上直接建立与官方源的连接,任何中间缓存层都会破坏这条信任链;
3. **零运维**:无服务器、无数据库、无 API Key、无环境变量,天然满足隐私"零收集"。

### 1.2 架构图

```
┌─────────────────────────── Android App(前端,唯一自研部分)───────────────────────────┐
│                                                                                      │
│  UI 层(Jetpack Compose + Material 3)                                                 │
│  Home │ Search │ Browse │ Detail │ OfficialWeb │ Favorites │ Settings │ Common(F7状态) │
│    │ UiState(StateFlow)· 事件上行                                                   │
│  ViewModel 层(每组界面一个 ViewModel,androidx.lifecycle)                             │
│    │                                                                                  │
│  Repository 层(SearchRepository / LawDetailRepository / FavoriteRepository)           │
│    │   ┌──────────────────────────┐   ┌──────────────────────────────┐               │
│    ├──▶│ 会话缓存(内存 LruCache,   │   │ 本地元数据(Room + DataStore) │               │
│    │   │ TTL 5min,≤20 文档)       │   │ 收藏/最近浏览/源健康/设置      │               │
│    │   └──────────────────────────┘   └──────────────────────────────┘               │
│    │ SourceRegistry(源注册/路由/降级,见需求文档 3.4)                                 │
│    │   implements LawSource 统一接口(见本文档 4.2)                                   │
│  │            │                       │                                              │
│  │   ┌────────┴────────┐    ┌─────────┴──────────┐                                   │
│  │   │ 原生解析通道      │    │ 官方直通通道(WebView)│                                  │
│  │   │ OkHttp+Retrofit │    │ 受控 WebView 容器    │                                  │
│  │   │ + Jsoup 解析     │    │ 域名白名单,不注入JS │                                   │
│  └───┼─────────────────┼────┼──────────────────┼──────────────────────────────────────┘
      │HTTPS(用户触发,限流) │    │WebView 页面资源    │
      ▼                     ▼    ▼                  ▼
┌──────────────┐   ┌──────────────────┐   ┌──────────────────┐
│ zhengce.www. │   │ www.court.gov.cn │   │ flk.npc.gov.cn   │
│ gov.cn 政策库 │   │ 最高人民法院       │   │ 国家法律法规数据库 │
│ (HTML,可解析) │   │ (HTML,可解析)    │   │ (JS渲染,仅浏览)  │
└──────────────┘   └──────────────────┘   └──────────────────┘
                    外部官方源("后端",不可控,只读)
```

### 1.3 两条核心运行时数据流

**搜索**:用户提交关键词 → SearchViewModel → SearchRepository 并行调用各 LawSource.search() → 可解析源返回 `LawRef` 列表(原生渲染);受限源(flk)由 WebOnlySource 返回"官方直通"跳转信息,UI 渲染为置顶卡片 → 用户点击 → WebView 打开官方搜索页。

**详情**:用户点击列表项 → LawDetailRepository 先查会话缓存(TTL 5 分钟,带 fetchedAt)→ 未命中则对应 Source.fetchDocument() 实时拉取解析 → `LawDocument` 渲染;受限源则直接进入 WebView 直通页。任何路径不读磁盘正文(磁盘无正文表)。

---

## 2. 技术栈

### 2.1 前端(Android 客户端)

| 维度 | 选型 | 用途 |
|---|---|---|
| 语言 | **Kotlin**(JVM 17) | 全部业务代码 |
| UI 框架 | **Jetpack Compose + Material 3**,单 Activity + Navigation Compose | 声明式 UI;F7 的加载/空/错/离线状态即 UiState 分支 |
| 架构模式 | MVVM;ViewModel + StateFlow + Coroutines | 单向数据流:事件上行 / UiState 下行 |
| 网络 | **OkHttp**(自定义拦截器:UA、限流、退避、禁缓存)+ **Retrofit**(gov.cn 若存在 JSON 接口则用,否则降级直取 HTML) | 原生解析通道唯一出网口 |
| 解析 | **Jsoup**(HTML → 领域模型)+ **kotlinx.serialization**(JSON 场景) | 解析规则集中在 Parser 层,页面改版只改 Parser |
| WebView | 系统 WebView + 自研受控容器 | 官方直通通道 |
| 本地存储 | **Room**(收藏/最近浏览/源健康,仅元数据表)+ **DataStore Preferences**(设置) | 禁建正文表 |
| 依赖注入 | 手动 DI(`AppContainer`) | 不引入 Hilt,降低 vibecoding 排错成本 |
| 构建 | Gradle(KTS)+ Version Catalog(`gradle/libs.versions.toml`)+ R8 | 单模块 `:app` |

**核心依赖清单**(`libs.versions.toml` 骨架,版本为 2026-10 编写时的基准占位;M0 建工程时以 Android Studio 新建工程给出的配套版本为准对齐锁定,**KSP/Kotlin/Compose 插件版本必须配对,不得单独升级**):

```toml
[versions]
agp = "8.x"                      # 跟随 Android Studio 版本
kotlin = "2.x"                   # 与 compose 插件、ksp 配对
ksp = "与 kotlin 配对"
coreKtx = "1.15+"
lifecycle = "2.8+"
activityCompose = "1.9+"
composeBom = "2025.xx.xx"        # BOM 统一管理 Compose 各库版本
navigationCompose = "2.8+"
room = "2.7+"
datastore = "1.1+"
okhttp = "4.12+"                 # 可升 5.x,升前跑全量单测
retrofit = "2.11+"
jsoup = "1.18+"
kotlinxSerialization = "1.7+"
coroutines = "1.9+"
junit = "4.13.2"
mockwebserver = "同 okhttp 版本"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-ui / ui-tooling-preview / material3   # 由 BOM 管理版本
androidx-navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigationCompose" }
androidx-room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
androidx-room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
androidx-room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
androidx-datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-logging = { module = "com.squareup.okhttp3:logging-interceptor", version.ref = "okhttp" }
retrofit = { module = "com.squareup.retrofit2:retrofit", version.ref = "retrofit" }
retrofit-kotlinx-serialization = { module = "com.squareup.retrofit2:converter-kotlinx-serialization", version.ref = "retrofit" }
jsoup = { module = "org.jsoup:jsoup", version.ref = "jsoup" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinxSerialization" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
# 测试
junit / mockwebserver / kotlinx-coroutines-test
androidx-compose-ui-test-junit4 / androidx-test-ext-junit   # androidTest

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

### 2.2 后端(外部官方数据层)技术特征

| 源 | 服务形态 | 接入要点(M0 固化细则) |
|---|---|---|
| zhengce.www.gov.cn(国务院政策文件库) | 静态/半静态 HTML(检索页 + 详情页);是否存在 JSON 接口待 M0 探明 | 关注:检索 URL 参数、分页参数、字符编码(历史页面存在非 UTF-8,需按响应头/页面 meta 解码)、列表项与正文的 DOM 选择器 |
| www.court.gov.cn(最高法) | HTML 栏目页 + 详情页 | 同上;司法解释栏目结构 |
| flk.npc.gov.cn(国家法律法规数据库) | JS 渲染单页应用(Vue),接口有动态签名 | **只走 WebView 浏览,不做任何程序化解析**;M0 验证搜索深链参数是否可用,结论写入《数据源接入说明》 |
| (预留)www.spp.gov.cn 最高检 | M0 核实 | 实现 `LawSource` 接入即可,不动其他层 |

这些源对本项目而言是**只读、不可控的外部依赖**:无 SLA、会改版、可能限流。因此 4.1 的 HTTP 约定和 4.3 的错误模型是架构核心,而非锦上添花。

---

## 3. 目录结构划分

目标仓库布局(M0/M1 建立骨架;当前仓库初期只有 `docs/` 下两份文档):

```
法律查询软件/                                # 仓库根
├── docs/
│   ├── 法律查询APP需求文档.md                # 已有(需求/合规/验收)
│   ├── 法律查询APP项目文档.md                # 本文档(架构/约定/启动)
│   └── 数据源接入说明.md                    # M0 产出并持续维护:各源请求方式、
│                                           #   URL 参数、DOM 选择器、robots 复核记录
├── app/
│   ├── build.gradle.kts                     # 唯一模块;minSdk 26 / target&compile 36(基准)
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml          # 仅 INTERNET 权限;cleartext=false;单 Activity
│       │   ├── java/com/lawquery/
│       │   │   ├── LawQueryApplication.kt   # Application;创建 AppContainer
│       │   │   ├── MainActivity.kt          # 单 Activity,承载所有 Compose 页面
│       │   │   ├── di/AppContainer.kt       # 手动 DI:OkHttp、Room、各 Source、各 Repository
│       │   │   ├── core/
│       │   │   │   ├── net/                 # HttpClientFactory / ThrottleInterceptor
│       │   │   │   │                        # BackoffPolicy / SourceHealthTracker / UaProvider
│       │   │   │   ├── parse/               # GovCnParser / CourtParser / ParseResult
│       │   │   │   │                        # 解析器只做 HTML→模型,不做 IO(便于单测)
│       │   │   │   └── sessioncache/        # SessionCache(LruCache,TTL 5min)/ SessionEntry
│       │   │   ├── data/
│       │   │   │   ├── source/              # LawSource 接口 / SourceId / SourceCapability
│       │   │   │   │                        # GovCnSource / CourtSource / WebOnlySource(flk)
│       │   │   │   │                        # SourceRegistry(路由与降级,需求文档 3.4)
│       │   │   │   ├── repo/                # SearchRepository / LawDetailRepository
│       │   │   │   │                        # FavoriteRepository / UpdateCheckService(指纹校验)
│       │   │   │   └── local/               # AppDatabase / entities(FavoriteEntity,
│       │   │   │                            #   RecentEntity, SourceHealthEntity) / 各 Dao
│       │   │   │                            # SettingsStore(DataStore)
│       │   │   ├── domain/
│       │   │   │   └── model/               # LawRef / LawStatus / LawDocument / Chapter
│       │   │   │                            # Article / VersionFingerprint / SearchQuery
│       │   │   └── ui/
│       │   │       ├── theme/               # Material3 主题、深色模式、字号档位
│       │   │       ├── common/              # 骨架屏/离线页/错误页/来源徽标/时效性徽标/
│       │   │       │                        # "获取于"时间戳条(F7 通用组件)
│       │   │       ├── home/  search/  browse/  detail/
│       │   │       ├── web/                 # OfficialWebScreen / UrlWhitelist
│       │   │       └── favorites/  settings/
│       │   └── res/                         # strings(全部文案集中)/ themes / 图标
│       ├── test/                            # JVM 单测:解析器、限流退避、会话缓存、指纹
│       └── androidTest/                     # Compose UI 测试:搜索→详情→复制 等核心路径
├── gradle/libs.versions.toml                # 依赖唯一锁定处(见 2.1)
├── build.gradle.kts / settings.gradle.kts / gradle.properties
└── README.md                                # M7 产出:构建/运行/数据源配置速览
```

分层规则(给 AI 的硬约束):`ui/` 不得直接 import `core/net` 与 OkHttp 类型;`data/source` 不得 import 任何 `ui/`;`domain/model` 不依赖任何框架;网络、解析、存储各自可独立单测。

---

## 4. 前后端交互方式与接口约定

### 4.1 通用 HTTP 约定(原生解析通道)

| 项 | 约定 |
|---|---|
| 触发方式 | **仅用户主动操作**发起;无定时任务、无预取、无后台轮询(唯一例外:冷启动收藏元数据校验,串行、间隔 ≥2s,失败静默) |
| 并发/频率 | 全局并发 ≤ 2;同一源两次请求间隔 ≥ 2s(ThrottleInterceptor 保证,单测覆盖) |
| UA | 如实标识:`LawQuery/<version> (Android; <model>)` 需与浏览器 UA 兼容时在《数据源接入说明》中逐源注明并说明理由,不伪造 Cookie、不破解签名 |
| 超时 | connect 10s / read 20s / write 20s |
| 缓存 | **不配置 OkHttp Cache**,请求头携带 `Cache-Control: no-cache`,确保实时 |
| 重试 | 5xx 与网络超时:退避重试至多 1 次(2s);4xx **不重试** |
| 限流响应 | 收到 403/429:立即放弃,该源标记 `COOLDOWN`(10 分钟),期间请求直接走降级链路,不发网络请求 |
| 编码 | 以响应头 Content-Type 为准,缺失时读页面 `<meta charset>`,再缺省 UTF-8(gov.cn 历史页面存在非 UTF-8) |
| HTTPS | 仅允许 https,`usesCleartextTraffic=false` |

### 4.2 LawSource 统一接口(端内"后端访问层"契约)

所有官方源实现同一接口;新增源 = 新增一个实现类 + 注册,不改动上层:

```kotlin
enum class SourceId { GOV_CN, COURT, FLK_WEB /*, SPP, ...*/ }
enum class SourceCapability { NATIVE, WEB_ONLY }   // 决定走哪条通道

interface LawSource {
    val id: SourceId
    val capability: SourceCapability

    /** 原生搜索。WEB_ONLY 源返回带关键词的直通跳转信息,由 UI 渲染为官方直通卡片 */
    suspend fun search(query: SearchQuery): SourceResult<SearchPage>

    /** 实时拉取全文(仅 NATIVE 源实现) */
    suspend fun fetchDocument(ref: LawRef): SourceResult<LawDocument>

    /** 轻量元数据拉取,用于更新校验(仅 NATIVE 源) */
    suspend fun fetchMeta(ref: LawRef): SourceResult<VersionFingerprint>

    fun healthSnapshot(): SourceHealth          // 由 SourceHealthTracker 驱动
}
```

`SearchQuery` = 关键词 + 筛选(效力位阶/机关/年份/时效性,可空)+ 分页游标;`SearchPage` = `List<LawRef> + 下一页游标 + 官方直通信息(可空)`。

### 4.3 错误模型与 UiState 映射(跨层约定)

```kotlin
sealed interface SourceResult<out T> {
    data class Success<T>(val data: T) : SourceResult<T>
    data class SourceUnavailable(val source: SourceId, val reason: Reason) : SourceResult<Nothing>
    // Reason: RATE_LIMITED(403/429) / PARSE_FAILED(改版) / NETWORK / TIMEOUT / OFFLINE
}
```

| SourceResult | UI 表现(F7) |
|---|---|
| Success | 正常渲染,详情页附 `fetchedAt` 时间戳条 |
| SourceUnavailable(OFFLINE) | 统一离线页 + 重试 |
| SourceUnavailable(其他) | 该源结果剔除 + 内嵌提示条"××来源暂不可用";多源搜索时其余源结果正常展示 |
| 单源且失败 | 错误页 + 重试(+ 官方直通兜底入口,如有) |

ViewModel 只暴露 `StateFlow<UiState>`,`UiState = Loading / Content(data) / Empty / Offline / Error(retry)`,与 F7 状态一一对应。

### 4.4 WebView 官方直通约定

- 域名白名单:`flk.npc.gov.cn`、`*.www.gov.cn`、`www.gov.cn`、`www.court.gov.cn`;白名单外跳转一律拦截,仅提供"在系统浏览器打开";
- 配置:`javascriptEnabled=true`(官网需要),`allowFileAccess=false`,`allowContentAccess=false`;
- **不注入任何修改内容的 JS,不读取 DOM,不拦截响应体**;
- 原生壳仅提供:来源域名标题栏、进度条、刷新、分享原文链接、系统浏览器打开、错误页;
- 搜索深链参数格式(M0 验证后)固化在《数据源接入说明》,代码中集中定义于 `web/UrlWhitelist.kt` 同级的 `DeepLinks.kt`,禁止散落硬编码。

### 4.5 本地存储契约(Room/DataStore)

| 表/存储 | 字段要点 | 禁止 |
|---|---|---|
| FavoriteEntity | sourceId、源内 id、标题、原文链接、**收藏时指纹**(发文字号/公布日/施行日/时效性)、收藏时间 | 任何正文字段 |
| RecentEntity | 同上(去掉指纹),≤50 条滚动淘汰 | 同上 |
| SourceHealthEntity | sourceId、状态(OK/COOLDOWN/DEGRADED)、最近一次成功/失败时间与原因 | — |
| DataStore | 字号档位、免责声明已读标记 | — |

数据库版本升级只允许加列/加表,迁移脚本随 PR 提交;验收 D6(库文件中检索不到正文)每次发版前抽查。

---

## 5. 关键模块职责

| 模块 | 职责 | 关键类 | 对应需求文档 |
|---|---|---|---|
| `core/net` | 全部出网:UA、限流(并发≤2/同源≥2s)、退避重试、403/429 冷却、源健康记录 | HttpClientFactory、ThrottleInterceptor、SourceHealthTracker | 3.4、7.2 |
| `core/parse` | HTML/JSON → 领域模型;纯函数无 IO;结构缺失降级为纯文本 | GovCnParser、CourtParser | F3、M4 |
| `core/sessioncache` | 唯一允许的缓存:内存、TTL 5min、≤20 文档、携带 fetchedAt | SessionCache | 6.1 |
| `data/source` | LawSource 实现与注册;路由(哪个源原生/哪个直通)与降级链路 | GovCnSource、CourtSource、WebOnlySource、SourceRegistry | 3.2–3.4 |
| `data/repo` | 业务编排:搜索聚合、详情(缓存→网络)、更新指纹比对、冷启动串行校验 | SearchRepository、LawDetailRepository、UpdateCheckService | F1/F3/F4、6.2 |
| `data/local` | 元数据持久化与设置 | AppDatabase、各 Dao、SettingsStore | F5/F6、6.1 |
| `ui/*` | 各页面状态渲染与事件上抛;**不含业务规则** | 各 Screen/ViewModel | 第 4 章 |
| `ui/common` | F7 状态组件、徽标、时间戳条、骨架屏 | OfflineScreen、SourceBadge | F7 |
| `ui/web` | 官方直通容器与白名单 | OfficialWebScreen、UrlWhitelist | F3、7.4 |
| `di` | 装配一切依赖(唯一 new 的地方) | AppContainer | — |

---

## 6. 环境配置与启动说明

### 6.1 开发环境要求

| 项 | 要求 |
|---|---|
| OS | Windows 10/11(当前开发机)、macOS、Linux 均可 |
| IDE | Android Studio 最新稳定版(自带配对的 AGP/Gradle 建议) |
| JDK | 17(Android Studio 自带的 JetBrains Runtime 即可,无需单独安装) |
| Android SDK | compileSdk 36(基准);SDK Platform + Platform-Tools + Build-Tools |
| 运行目标 | 真机(推荐,API 26+)或模拟器;WebView 相关验证必须真机 |
| 网络 | 能直连 gov.cn / court.gov.cn / flk.npc.gov.cn |

**零后端配置**:无服务器、无数据库实例、无 API Key、无环境变量、无 `local.properties` 之外的机密(`local.properties` 仅含 SDK 路径,由 Android Studio 自动生成,不入库)。

### 6.2 工程初始化(M0 执行)

1. 在仓库根 `git init`(当前尚未纳入版本管理);
2. Android Studio → New Project → Empty Activity(Compose)→ 包名 `com.lawquery` → minSdk 26;
3. 以 2.1 的 toml 骨架替换默认依赖,对齐 Android Studio 给出的配套版本;
4. `AndroidManifest.xml` 只保留 `<uses-permission android:name="android.permission.INTERNET"/>`,`application` 加 `android:usesCleartextTraffic="false"`;
5. 建立 `docs/数据源接入说明.md` 模板(M0 的探源结论写入此文档,而非散在代码注释)。

### 6.3 构建与运行命令(Windows:Git Bash 用 `./gradlew`,CMD 用 `gradlew.bat`)

```bash
./gradlew assembleDebug              # 构建 Debug APK → app/build/outputs/apk/debug/
./gradlew installDebug               # 安装到已连接设备
./gradlew testDebugUnitTest          # JVM 单测(解析器/限流/缓存/指纹)——每阶段收尾必跑
./gradlew connectedDebugAndroidTest  # Compose UI 测试(需设备/模拟器)
./gradlew lint                       # 静态检查
./gradlew assembleRelease            # 签名 Release(R8),M7 配置签名后可用
```

### 6.4 配置文件清单

| 文件 | 内容与注意点 |
|---|---|
| `gradle/libs.versions.toml` | 依赖唯一来源;禁止在 build.gradle.kts 里写裸版本号 |
| `gradle.properties` | `android.useAndroidX=true`、`org.gradle.jvmargs=-Xmx4g`、`org.gradle.caching=true` |
| `app/build.gradle.kts` | release 开启 `isMinifyEnabled=true` + `proguard-rules.pro`(Jsoup/serialization 需 keep 规则) |
| `app/src/main/AndroidManifest.xml` | 见 6.2 第 4 条 |
| `docs/数据源接入说明.md` | 各源 URL 参数/DOM 选择器/robots 复核记录;**每次发版前更新复核日期** |

### 6.5 调试与日志

- 网络层通过 OkHttp `logging-interceptor` 输出(仅 Debug 构建启用,Release 关闭);
- `SourceHealthTracker` 暴露调试页(Settings → 数据源状态)查看各源冷却/降级状态;
- 常见问题排查顺序:源健康状态 → 抓包确认请求形态是否与《数据源接入说明》一致 → Parser 单测复现 → WebView 直通兜底验证。

---

## 7. 测试与质量保障

| 层 | 方式 |
|---|---|
| 解析器 | Jsoup 解析 `src/test/resources` 中保存的官方页面快照(快照文件仅用于测试,不随 APK 分发);改版回归 = 更新快照 |
| 限流/退避/冷却 | ThrottleInterceptor 单测(MockWebServer + 虚拟时钟):并发≤2、间隔≥2s、403 后 10 分钟无请求 |
| 会话缓存/指纹 | JVM 单测:TTL 过期、容量淘汰、指纹不一致触发更新提示 |
| UI | Compose UI Test:搜索→结果→详情→复制单条 全路径(用假 Source 注入) |
| 真机矩阵 | M6:至少 3 档机型 × Android 12/14/16;WebView 验证必含 |
| 发版前 | 验收清单(需求文档第 9 章)逐项执行 + D6 正文抽查 + robots 复核 |

---

## 8. vibecoding 迭代工作流建议

1. **首次会话**:把需求文档 + 本文档一并投喂;让 AI 先执行 M0 并产出《数据源接入说明》,不要直接写 UI;
2. **模块级迭代**:每个模块开新会话时,提供本文档第 3–5 章对应小节 + 需求文档对应功能号(如"F1 搜索"),并要求改动不越过分层规则(第 3 章末);
3. **每阶段收尾**:运行 `./gradlew testDebugUnitTest lint`,对照需求文档第 9 章本阶段验收条目自检,通过才进入下一阶段;
4. **解析失效(官网改版)时**:只允许改 `core/parse` + 更新《数据源接入说明》,禁止顺手重构其他层;
5. **任何会话都不应**:引入后端/代理、引入正文持久化、放宽 4.1 约定——遇到此类建议,要求 AI 引用本文档 1.1 的三条依据后再评估。

---

## 9. 附录

### 9.1 需求 → 架构对照速查

| 需求文档条目 | 落点(本文档) |
|---|---|
| 3.3 双通道架构 | 1.2 架构图、`SourceCapability` |
| 3.4 降级链路 | `SourceRegistry` + `SourceHealthTracker`(4.1/4.3) |
| F7 通用状态 | `ui/common` + `UiState`(4.3) |
| 6.1 缓存策略 | `core/sessioncache` + 4.1 禁缓存 + 4.5 存储契约 |
| 6.2 版本指纹 | `VersionFingerprint` + `UpdateCheckService` |
| 7.2 合规红线 | 4.1 全表、4.4 WebView 约定、6.4 复核机制 |
| M0–M7 | 6.2 初始化、8 工作流 |

### 9.2 若未来引入自建后端(演进前提,当前不做)

仅在同时满足以下条件时评估:① 任一官方源提供**公开授权的 API/数据集**;② 官方书面许可聚合分发;③ 端侧直连方案被证明不可行。形态预想:Kotlin(Ktor)或 Node.js 的无状态配置/版本检查服务,仍不缓存条文正文。在满足前提前,该方向与合规红线冲突,禁止实施。

### 9.3 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| v1.0 | 2026-10-04 | 首版;与需求文档 v1.0 对齐 |
