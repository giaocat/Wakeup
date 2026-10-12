# Wakeup 课表

沈阳工业大学教务通课表（`sutjwt.sut.edu.cn`）的 Android 客户端。
用 **Jetpack**（Compose / ViewModel / DataStore / Retrofit+OkHttp）与 **Material 3 Expressive（M3E）** 实现，
应用包名为 `com.wakeup`。

数据来源：教务通的课表分享页，形如 `https://sutjwt.sut.edu.cn/user/kb/table/token/<token>`。
**App 不绑定任何一份具体课表**，token 在「设置」里填写。

---

## 一、快速开始

```powershell
# 用 Android Studio 打开本目录即可（推荐）
# 命令行构建：
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

- **JDK**：17 及以上（Android Studio 自带的 JBR 25 可用）
- **Gradle**：9.6.0（由 `gradle/wrapper` 指定，首次构建自动下载）
- **AGP**：9.4.1，**Kotlin**：2.2.10（AGP 9 自带 Kotlin 支持，无需再声明 `kotlin.android` 插件）
- **compileSdk / targetSdk**：36，**minSdk**：26
- **包名**：`com.wakeup`（debug 构建为 `com.wakeup.debug`）

> **为什么是 compileSdk 36 而不是 37？**
> Google 公开的 SDK 仓库（`repository2-3.xml`）目前最高只提供 `platforms;android-36`，
> `android-37` 仍在 preview 频道，CI 上无法用 `sdkmanager` 安装。
> 因此依赖版本也统一控制在 `minCompileSdk <= 36`：
> `material3 1.5.0-alpha01`（仍是带 Material 3 Expressive API 且 `minCompileSdk=35` 的版本，
> `1.5.0-alpha29` 起升到 37）、`compose-bom 2026.06.01`、`navigation-compose 2.9.8`、
> `lifecycle 2.10.0`、`core-ktx 1.18.0`。
> 等 android-37 进入稳定仓库后，把这些版本一起提上去即可。

安装包产物：`app/build/outputs/apk/debug/app-debug.apk`

已实测通过的构建命令：

```powershell
.\gradlew.bat clean assembleDebug testDebugUnitTest lintDebug
# => BUILD SUCCESSFUL，单元测试 21/21 通过，lint 0 error
```

### 自动构建（GitHub Actions）

仓库内置两个工作流，无需额外配置：

| 工作流 | 触发条件 | 行为 |
| --- | --- | --- |
| `.github/workflows/android-ci.yml` | push / PR 到 `main`\|`master`，或手动触发 | 安装 JDK 17 与 Android SDK 组件 → `assembleDebug` → `testDebugUnitTest` → `lintDebug`，并把 debug APK、测试报告、lint 报告作为 artifact 上传 |
| `.github/workflows/release.yml` | 推送 `v*` 标签，或手动触发 | `assembleRelease` 并把所有 APK 发布到 GitHub Release |

发布一个版本：

1. **先在 [`CHANGELOG.md`](CHANGELOG.md) 里写好本版段落** —— 新增一个 `## X.Y.Z`（不带 `v`，
   与 `versionName` 一致），只写用户能感知的变化；
2. 升 `app/build.gradle.kts` 里的 `versionCode` / `versionName`；
3. 打标签并推送：

```bash
git tag v1.0.6
git push origin v1.0.6
```

> **发布说明自动取自 `CHANGELOG.md`**：`release.yml` 会按标签名切出对应的 `## X.Y.Z` 段落，
> 作为 Release 的说明文本（找不到段落时退回「自动生成的提交列表」，不会让发布失败）。
> 所以每次 release 都是手写好的更新日志，而不是提交标题堆砌。

> 发布需要**固定签名密钥**，配置见「十、发布与签名」。缺配置时 CI 会直接失败，
> 而不是静默产出一个用临时调试密钥签名的包（那种包无法覆盖安装）。

---

## 二、这个课表接口是怎么回事

课表页面 **没有独立的 JSON 接口**。服务端把整份数据直接内联在 HTML 的 Vue 初始化脚本里：

```html
<script>
  var vm = new Vue({
    data: { ... },
    created: function () {
      this.init({"code":200,"table":{"term":{...},"list":[...]},"user":"测试同学"});
    }
  })
</script>
```

所以取数链路是：

1. `WakeupApi.timetableHtml(url)` —— 用 Retrofit + ScalarsConverter 把页面当纯文本抓下来；
2. `TimetablePayloadParser.extractPayload(html)` —— 扫描 `this.init(` 之后的第一个 `{`，
   用括号配平（并跳过字符串字面量里的 `{}`）截出完整的 JSON 实参；
3. `TimetablePayloadParser.parsePayload(json)` —— kotlinx.serialization 反序列化成 `TimetableResponse`；
4. `TimetableMapper.toDomain(...)` —— 映射成 `Timetable` / `TimetableCourse` / `Course`。

### 字段含义

| 字段 | 含义 |
| --- | --- |
| `term.total` | 学期总周数（`"20"`） |
| `term.starttime` | 开学第一周的时间戳（秒） |
| `list[].KCMC` / `JSXM` | 课程名 / 教师姓名 |
| `list[].JX0404ID` | 课程号（同一门课的多个时段共用） |
| `list[].INFO` | 上课时段数组；**为空表示本学期未排课** |
| `INFO[].KCSJ` | 节次编码，如 `30102` = 周三 + 第 1、2 节 |
| `INFO[].KCSJMX` | 节次明细，如 `["301","302"]` |
| `INFO[].KKZC` / `KKZCMX` | 周次表达式 `4-5,7-17` / 展开后的周次数组 |
| `INFO[].JX0601MC` | 上课地点（可能为 `null`，如体育课） |

**节次编码规则**：首位是星期（1=周一 … 7=周日），其后每两位表示一个节次。
例如 `50708` → 周五、第 7、8 节；`30102` → 周三、第 1、2 节。

**周次表达式规则**：`7-13`、`4-5,7-17`、`4,7-9,11-13` 都支持，
`WeekExpression` 同时提供 `parse` 与 `format`，两者互为逆运算（有单元测试覆盖）。

---

## 三、功能

| 页面 | 说明 |
| --- | --- |
| **课表** | 单行标题栏（上一周 / 周次 / 周次选择 / 下一周 / 刷新）；左侧作息时间栏固定；一屏放得下七天就铺满，放不下则保持可读列宽并**左右滚动**，上下也能滚动；显示今日高亮、当前时刻线；同一格多门课自动合并并标注冲突；底部列出未排课课程 |
| **今日** | 按时间顺序列出当天课程，正在上的那节标「正在上课」，已结束的自动淡化 |
| **设置** | 课表链接（完整链接或 token）、服务地址、同步状态、本地缓存占用与清空、主题模式（跟随系统·浅色·深色）、动态取色、隐藏未排课课程 |
| **课程详情** | 点击任意课程格子或今日课程卡片，弹出底部面板查看时间、地点、教师、周次 |
| **Smartspacer** | 把「下一节课」提供给桌面/锁屏的「一览」（[详见第九节](#九smartspacer-集成)），点一下打开 App |

顶部只有**一行**标题栏（`CompactHeader`）：‹ 上一周、周次标题（当前周会显示「· 本周」）、周次选择、下一周 ›、刷新。
原先的 `TopAppBar` 与周次切换行已经合并，少占约 60 dp 纵向空间。设置入口仍在底部导航。

> 周次选择菜单用可滚动的 `Column` 实现，**不能用 `LazyColumn`**：`DropdownMenu` 的弹窗给的是无限高度约束，
> 惰性布局在无限约束下测量会直接抛异常（表现为点开即闪退）。

### 课表网格密度

布局参数集中在 `ui/schedule/ScheduleScreen.kt` 顶部：

| 参数 | 值 | 说明 |
| --- | --- | --- |
| `GridRowHeight` | 54 dp | 每节课行高（12 节共 648 dp，可上下滚动） |
| `GridPeriodColumnWidth` | 44 dp | 左侧节次/时间栏宽度 |
| `GridMinDayWidth` | 60 dp | 单列可读下限；低于它就改为左右滚动 |
| `GridMaxDayWidth` | 96 dp | 平板/横屏的单列上限 |
| `GridHeaderHeight` | 44 dp | 星期/日期表头高度 |

列宽由纯函数 `computeGridLayout()` 计算（有单元测试覆盖）：

- 可用宽度 ≥ `7×60 + 44 = 464 dp` 时，七天**铺满屏幕**，不需要左右滚动；
- 更窄时取 60 dp 保底并**开启左右滚动**，而不是把课程名挤成一条；
- 表头与网格共用同一个 `ScrollState`，横向滚动时星期栏与课程格始终对齐。

Android Studio 里可直接查看 `SchedulePreview.kt` 中的两个 `@Preview`（360×640 与 412×915）来检查密度。

### 课表链接可自行更改

App 不绑定某一份课表，**任何人都可以通过「设置 → 课表链接」填自己的课表**。支持两种写法：

| 输入 | 解析结果 |
| --- | --- |
| `https://sutjwt.sut.edu.cn/user/kb/table/token/100000` | 原样请求（换成其它学校/域名也可用） |
| `100000` | 用「服务地址」补全成 `…/user/kb/table/token/100000` |

（示例里的 `100000` 只是占位数字，不是真实课表。）

- 只填 token 时，可在设置里展开「服务地址」改成自己学校的域名。
- 课表链接**默认为空**：App 不预设任何人的 token。首次安装没有缓存时，课表页会提示「去设置课表链接」。
- 修改链接后会立即同步；同步成功才会覆盖缓存，失败不会破坏旧数据。
- 旧版本安装升级时，原来保存的 token 会自动迁移成完整链接（`token` → `timetable_url`）。

### 本地缓存

同步成功后，**抽取出来的原始 JSON** 会写到 `filesDir/wakeup/timetable_cache.json`：

- 启动时先用缓存把界面铺满，再后台联网同步，所以**离线或链接临时失效时依然能看到课表**。
- 缓存里保存的是原始 JSON，只会走「重新解析 → 成功覆盖 / 失败丢弃」这一条路径，不需要为领域模型维护序列化。
- 缓存带有来源地址；换了课表链接后，上一个来源的缓存会被忽略，不会先闪一下别人的课表。
- 写入采用「先写临时文件再改名」，避免写一半被中断留下坏文件。
- 数据来源变动时（同步失败）课表页会显示提示条和「重试」按钮。

> 注意：App 本身**不再内置任何示例课表**，首次使用必须联网同步一次。

### 关于 M3E

`ui/theme/Theme.kt` 使用 Material 3 Expressive 的 `MaterialExpressiveTheme`：

```kotlin
MaterialExpressiveTheme(
    colorScheme = wakeupColorScheme(darkTheme, dynamicColor),
    motionScheme = MotionScheme.expressive(),
    content = content,
)
```

`material3` 固定在 **1.5.0-beta01**（高于 Compose BOM 的稳定版本），
因为 `MaterialExpressiveTheme`、`MotionScheme.expressive()`、`MaterialShapes`
以及 `LoadingIndicator` / `ContainedLoadingIndicator` 这些 expressive 组件目前还在该版本线上。
Android 12 及以上默认跟随系统动态取色，低版本回落到以校色蓝为种子的手工配色。

---

## 四、工程结构

```
app/src/main/java/com/wakeup/
├── WakeupApplication.kt          # Application + 手写依赖容器（AppContainer）
├── MainActivity.kt               # 唯一 Activity，edge-to-edge
├── data/
│   ├── cache/TimetableCache.kt   # 本地磁盘缓存（原始 JSON + 来源地址 + 同步时间）
│   ├── model/TimetableDto.kt     # 接口 JSON 的结构映射（kotlinx.serialization）
│   ├── remote/
│   │   ├── WakeupApi.kt          # Retrofit 接口与 OkHttp 配置
│   │   ├── TimetablePayloadParser.kt  # 从 HTML 抽取内联 JSON
│   │   ├── TimetableMapper.kt    # DTO → 领域模型
│   │   └── TimetableRemoteDataSource.kt  # 链接解析 + 取数
│   └── repository/
│       ├── SettingsRepository.kt # DataStore 设置读写（含 token → 链接迁移）
│       └── TimetableRepository.kt# 取数 + 内存缓存 + 本地缓存读写
├── domain/
│   ├── Course.kt                 # Course / TimetableCourse / Timetable
│   ├── Timetable.kt              # WeekExpression / PeriodTimes / Term
│   └── NextClass.kt              # 「下一节课」推导 + 展示文案（Smartspacer 的数据来源）
├── smartspacer/
│   ├── NextClassTarget.kt        # Smartspacer Target（ContentProvider）
│   └── NextClassUpdateReceiver.kt# 接收 Smartspacer 的定时刷新请求
├── ui/
│   ├── WakeupApp.kt              # Scaffold + 底部导航
│   ├── WakeupViewModel.kt        # 课表状态、周次切换、缓存恢复、设置项
│   ├── schedule/ScheduleScreen.kt
│   ├── today/TodayScreen.kt
│   ├── settings/SettingsScreen.kt
│   ├── components/CourseDetailSheet.kt
│   │   └── UpdateDialog.kt       # 「发现新版本」弹窗与设置页的检查更新行
│   └── theme/                    # Color.kt / Theme.kt
└── util/TimeUtils.kt             # 时刻线定位、相对时间等
```

---

## 五、测试

```powershell
.\gradlew.bat testDebugUnitTest
```

`TimetableParsingTest` 用**构造的**示例数据（`app/src/test/resources/timetable_payload.json`，仅测试用）覆盖：

- 从页面脚本中抽取 `this.init(...)` 的 JSON，以及无数据时返回 `null`
- 反序列化 + 领域映射（14 条课程 → 12 门已排课 / 2 门未排课）
- 节次编码解析（`10102` → 周一第 1、2 节、`30910` → 周三第 9、10 节，含地点与教师）
- 周次表达式的解析与格式化互逆
- 按周次取课、排序，周次 ↔ 日期换算
- 同一格多门课的归并（周三第 7、8 节的两门课）
- **隐藏未排课开关**：关闭时列出未排课课程，打开时列表为空且不影响已排课课程
- **课表链接解析**：完整链接原样使用（支持任意域名）、只填 token 时补全、自定义服务地址、空输入
- **缓存往返**：序列化/反序列化、缓存 payload 能还原出完整课表、损坏内容返回 null
- **网格列宽**：铺满、464dp 分界、窄屏改横向滚动、宽屏封顶、宽度未知

示例数据里的课程、教师、学生（甲/乙/丙… 老师、测试同学）全部是虚构的，
字段名与真实页面一致。要换成自己的数据，直接替换该文件中的课程条目即可。

另外三个测试文件：

| 文件 | 覆盖内容 |
| --- | --- |
| `CourseVisualsTest.kt` | 课程配色（相邻槽位距离/色相）、G/F 座 3、4 节提前、其余楼栋不受影响、侧栏与时刻线随 G/F 座切换 |
| `UpdateAndSchoolTest.kt` | 沈工大域名识别与反例、检查更新的版本比较、atom feed 解析与实体还原 |
| `NextClassTest.kt` | 「下一节课」推导：正在上课、当天已上完顺延、跨周次与假期、G/F 座提前时间与外校来源、展示文案 |

当前共 **68 个测试**，`.\gradlew.bat testDebugUnitTest` 全部通过。

### 安装到设备

```powershell
.\gradlew.bat installDebug           # 需要已连接设备或运行中的模拟器
# 或直接安装产物：
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

首次启动没有缓存，会提示去「设置」填课表链接；同步成功一次之后即可离线查看。

---

## 六、作息时间

时间取自教务处官网的「作息时间」页：<https://jwzx.sut.edu.cn/index/zxsj.htm>
（`PeriodTimes.all`，第 1–11 节与官网**逐条一致**）：

| 节次 | 时间 | 节次 | 时间 |
| --- | --- | --- | --- |
| 1 | 08:30–09:15 | 7 | 15:25–16:10 |
| 2 | 09:20–10:05 | 8 | 16:15–17:00 |
| 3 | 10:25–11:10 | 9 | 18:00–18:45 |
| 4 | 11:15–12:00 | 10 | 18:50–19:35 |
| 5 | 13:30–14:15 | 11 | 19:40–20:25 |
| 6 | 14:20–15:05 | 12 | 20:30–21:15 ⚠️ |

> ⚠️ **第 12 节不在官网列表里**（官网只列到第 11 节），但课表数据中确实会出现
> （`KCSJ` 形如 `11112`、`KCSJMX` 形如 `["111","112"]`），所以保留该行，
> 时间按每节 45 分钟顺延**推算**，不是官网数据。如学校后续公布，请以官网为准。

### 左侧时间轴固定用官方作息

左侧时间轴**始终**显示上表的官方时间，不随课表内容变化。

> **踩过的坑**：曾经为了"让时刻线更准"，让时间轴跟着当周的 G/F 座课程走
> （当周有 G/F 座的课就把第 3、4 节显示成 10:15 / 11:05）。但右侧每门课有**各自的**时间，
> 非 G/F 座的课仍在 10:25 开始 —— 轴一旦跟着某门课走，就和大多数课程对不上。
> 现在轴固定为官方作息，**G/F 座的提前时间只体现在格子内部**（格子上标「提前 10:15」）。

### G 座 / F 座 3、4 节提前 10 分钟

**沈工大 G 座和 F 座**在上第 3、4 节时提前 10 分钟上下课：

| 节次 | 官方作息 | G 座 / F 座 |
| --- | --- | --- |
| 3 | 10:25–11:10 | **10:15–11:00** |
| 4 | 11:15–12:00 | **11:05–11:50** |

规则必须同时满足两个条件：

1. **课表来源是沈工大域名** —— host 为 `sut.edu.cn` 或 `*.sut.edu.cn`（如 `sutjwt.sut.edu.cn`）；
2. **教室名以 `G` 或 `F` 开头**（去空格、忽略大小写，如 `G-101`、`F-105`、`G101`）。

其余教学楼（`A-101`、`B-109`、`C-303`、`D-105`、`E-201`、`H-301`…）以及 3、4 节之外的节次都保持官方时间。
**别的学校的课表即使有间教室叫 `G-101` 也不会被改时间** —— 域名不匹配。

> **曾经的实现错误**：最初按字面量 `GF` 前缀匹配，而真实教室名是 `G-101` / `F-105`，
> **一个都命中不了**，表现为"GF 座时间没变"。现已改为识别 `G` 或 `F` 前缀。

实现要点：

- `isSutDomain(url)` 判断来源域名；`Timetable.sourceUrl` 由数据层写入（联网时取实际请求地址，读缓存时取缓存里记录的地址）；
- `Course.usesGfSchedule(timetable)` = 沈工大域名 **且** 教室名匹配；
- `Course.effectiveStartAt/effectiveEndAt(timetable)` 是**唯一**的"该用哪个时间"入口：
  按来源学校 + 教室决定用 `startAtWithGf` 还是 `standardStartAt`。
  **课表格子、"今日"页面、课程详情、Smartspacer 的 Target** 都走它，
  所以「正在上课」的判断对 G/F 座的课也是准确的；
- G/F 座的课在格子上会标出「提前 10:15」，详情面板也会注明；
- **左侧节次栏与当前时刻线固定用官方作息**，不随课程变化（原因见上）。

改作息只需动 `domain/Timetable.kt` 里的 `PeriodTimes.all`（官方作息）、`gfTimes`（G/F 座）
与 `EARLY_BUILDING_PREFIXES`（哪些楼提前）。

相关校验见 `app/src/test/java/com/wakeup/UpdateAndSchoolTest.kt` 与 `CourseVisualsTest.kt`：
覆盖 **左侧时间轴与官网逐条一致**、**轴不随课程变化**、G/F 座 3、4 节偏移、
其余节次不受影响、其它楼栋不受影响、前缀识别容错、
**外校域名不触发提前作息**、沈工大域名识别（含 `notsut.edu.cn`、`evil.com/?u=…` 等反例）。

---

## 七、课程配色

课程颜色取自 `ui/theme/Color.kt` 的 `CourseColorSlots`（7 个色相 × 4 档明暗 = 28 个槽位）。
槽位**按档位交错排列**，且颜色**按课表顺序分配**（`courseColorsFor`），
因此相邻课程一定落在不同色相组，不会出现"挨着的两门课颜色几乎一样"。
格子额外带一圈同色系描边，进一步拉开相邻色块的边界。

配色输出为 ARGB 十六进制后再构造 `Color`（而非直接 `Color.hsl`），
这样在纯 JVM 单元测试里也能校验 —— 测试会断言
「相邻槽位 RGB 距离 > 64」「相邻槽位色相相差 ≥ 25°」「28 个槽位互不重复」。

---

## 八、检查更新

应用启动时会自动查一次 GitHub Releases，发现新版本就弹窗提示；设置页也有「检查更新」入口。

### 数据源：优先 atom，而不是 API

| | 地址 | 限流 |
| --- | --- | --- |
| **首选** | `https://github.com/giaocat/Wakeup/releases.atom` | **无限流**（网页资源） |
| 后备 | `https://api.github.com/repos/giaocat/Wakeup/releases/latest` | 匿名 **60 次/小时/IP** |

**为什么不用 API 做首选**：REST API 的匿名配额只有 60 次/小时/IP，而且是按**出口 IP** 算的 ——
同一个 NAT、校园网、运营商 CGNAT 后面的用户会共享这个额度。一旦撞满，接口返回 403，
更新检测就彻底失效（这种故障还很难排查）。atom feed 走网页通道，没有这个问题，
实测连续请求多次均正常返回。

两者都在 `app/build.gradle.kts` 的 `buildConfigField` 里配置：
`GITHUB_REPO` / `RELEASES_ATOM_URL` / `RELEASES_API_URL` / `RELEASES_PAGE_URL`。
atom 解析不出任何版本时才退回 API。

### 其它要点

- **自动检查带一天冷却**（`AUTO_CHECK_COOLDOWN_MS`，存在 DataStore）：频繁启动不会反复请求，
  进一步降低撞限流的概率；用户手动点「检查」不受限制。
- 版本比较见 `VersionCompare`：按 `major.minor.patch` 逐段比，段数不同时缺的段按 0；
  带 `-beta1` 之类后缀的视为**预发布**，同号下低于正式版。atom 里可能混有预发布 tag，
  代码取「版本最高的一个」而不是「最新的一个」，所以预发布不会顶掉正式版。
- 采取「提示 + 打开发布页」的方式，**不做应用内下载安装** —— 后者需要 `REQUEST_INSTALL_PACKAGES`
  权限，也会被系统的未知来源限制拦住。因此应用不需要任何新权限。
- 发布说明从 atom 的 `<content>` 取，经「先还原 HTML 实体、再去掉标签」转成纯文本。
  **已知取舍**：`&lt;` 解码后与真标签无法区分，这里优先保证发布说明干净可读
  （`&lt;p&gt;X&lt;/p&gt;` → `X`），代价是文本里的字面量尖括号会被一并去掉。
  该字段仅用于弹窗展示、不做解析，所以安全。

相关校验见 `UpdateAndSchoolTest.kt`：版本号解析（含 `v` 前缀、`-beta`、`+build`、非法输入）、
新版本判定、预发布语义、整型溢出、**atom feed 解析**（喂真实 feed 片段）、
实体还原/去标签顺序、以及残缺 feed 的容错。

---

## 九、Smartspacer 集成

把「下一节课」提供给 [Smartspacer](https://github.com/KieronQuinn/Smartspacer)，这样桌面/锁屏的
「一览」里可以直接显示下一节课的名称、时间和教室，点一下打开 App。

### 怎么用

1. 安装 [Smartspacer](https://github.com/KieronQuinn/Smartspacer/releases)（需要 **Android 10+**）；
2. 在 Smartspacer 里添加 Target，选 **「下一节课」**；
3. 需要先把课表链接配好并同步过一次 —— Target 读的是本地缓存的课表。

### 实现

| 文件 | 作用 |
| --- | --- |
| `smartspacer/NextClassTarget.kt` | Target 本体（`SmartspacerTargetProvider`，实际是 ContentProvider） |
| `smartspacer/NextClassUpdateReceiver.kt` | 接收定时刷新广播，回头通知 Smartspacer 重新取内容 |
| `domain/NextClass.kt` | `Timetable.nextOccurrence()` 推导下一节 + 副标题文案 |

要点：

- 用 `TargetTemplate.Basic`（标题=课程名，副标题=时间与教室，图标=学士帽，点击=打开 App）；
- 显示**绝对时间**（`今天 第3-4节 10:15 · F-105`）而不是"还有几分钟"：Smartspace 的刷新
  既不及时也不保证，绝对时间不会因为刷新延迟而显示错误；正在上课时改成
  `正在上课 · 至 11:50 · F-105`；
- 内容**每次取用时按当前时间现算**，所以拿到的一定是最新的课表缓存；
- G/F 座的提前作息同样生效（复用 `Course.effectiveStartAt` 与沈工大域名校验）；
- 数据来自 `TimetableRepository.timetableForDisplay()`：优先内存、其次磁盘缓存，
  不依赖 DataStore（ContentProvider 拿不到挂起上下文）。

### minSdk 的处理（重要）

Smartspacer 插件 SDK 的 AAR 声明了 **minSdk 29**，而本应用是 **26**，清单合并会直接失败。
这里用 `tools:overrideLibrary` 覆盖，保留 Android 8/9 用户，理由：

- Smartspacer 本体也要求 **API 29+**，所以在 8/9 上装不上，插件根本不会被调用；
- SDK 中我们用到的那条链路（`BaseProvider` → `SmartspacerTargetProvider`）只依赖
  `ContentProvider` / `Bundle` / `Icon` 等 API 26 就有的东西；真正需要 29 的
  （如 `AnalogClock` 用到的 `BlendMode`）本工程不触碰；
- **已知风险**：manifest 里声明的 provider 会在**每次应用启动时被系统实例化**，
  而我没有 Android 8/9 真机可以验证。这里只保证类能被加载（`onCreate` 直接返回 true），
  没有 29+ 才有的调用。如果你有 8/9 设备，值得实测一次。

### R8 混淆

`proguard-rules.pro` 里保留了整个 SDK：

```
-keep class com.kieronquinn.app.smartspacer.sdk.** { *; }
```

因为 Target 是通过 Binder 把 Parcelable 模型跨进程交给 Smartspacer 的，
**对方用自己的类副本按类名反序列化** —— 类名一旦被混淆，Target 就会失效。
已在 release APK 里核对 SDK 类名确实未被混淆。

---

## 十、发布与签名

> **⚠️ 发布密钥是有意公开的。** 仓库根目录的 `wakeup-release.jks` 及其口令写在
> [`SIGNING.md`](SIGNING.md) 里，任何访问本仓库的人都能拿到。这意味着**任何持有它的人都可以
> 签出与本项目官方包签名一致、可覆盖安装的 APK**。请不要把它当作安全凭证，
> 也不要用签名来证明某个包「来自官方」。详见 SIGNING.md 里的说明与取舍。

### 为什么必须是固定密钥

Android 要求**覆盖安装时签名一致**。如果每次构建用的密钥不同，用户就装不上新版本，
必须先卸载 —— 而卸载会丢掉已保存的课表链接和缓存。

CI runner 每次都是全新机器，**由 SDK 自动生成的调试密钥每次都不同**，
所以绝对不能在 CI 上用调试密钥发版。本项目因此做了两层保障：

1. 提供固定密钥时，`release` 用 `signingConfigs.release` 签名；
2. 发布流程用 **`-PrequireSigning=true`** 显式声明「这次必须用固定密钥」，
   缺密钥就直接失败。发布 workflow 另外会检查 `KEYSTORE_BASE64` 是否存在，
   并在构建后核对产物确实是签名包、把证书指纹打进 job summary。

> 为什么不用「CI 环境变量 + `assembleRelease`」来推断？因为 Gradle 在**配置阶段**
> 拿不到任务图，无法判断这次到底是不是在打 release。把检查写在 `android { }` 的
> `release { }` 里会**立即求值**，连 `assembleDebug` 一起干掉（这是实际踩过的坑：
> Android CI 第一次就因此失败）；写在 `afterEvaluate` 里也不行，它是项目级钩子、
> 与任务无关。所以改用调用方显式传参。

本地开发没有密钥时只打印警告并回退调试密钥，方便直接跑 `assembleRelease`。

### 配置步骤

生成密钥（一次性，之后一直用它；**务必备份，丢了就无法再覆盖安装已发布的版本**）：

```bash
keytool -genkeypair -v -keystore wakeup-release.jks -alias wakeup \
        -keyalg RSA -keysize 2048 -validity 10950 \
        -dname "CN=Wakeup, OU=Wakeup, O=giaocat, L=Shenyang, ST=Liaoning, C=CN"

# 转成 base64（-w0 不换行）
base64 -w0 wakeup-release.jks > keystore-base64.txt
```

在仓库 Settings → Secrets and variables → Actions 添加四项：

| Secret | 值 |
| --- | --- |
| `KEYSTORE_BASE64` | `keystore-base64.txt` 的全部内容 |
| `KEYSTORE_PASSWORD` | keystore 的 storePassword |
| `KEY_ALIAS` | `wakeup` |
| `KEY_PASSWORD` | keyPassword（与 storePassword 相同即可） |

`release.yml` 会把 `KEYSTORE_BASE64` 解到 `${RUNNER_TEMP}/release.jks`，
再通过环境变量传给 Gradle。

### 本地用同一密钥打包

```bash
export KEYSTORE_FILE=/绝对路径/wakeup-release.jks
export KEYSTORE_PASSWORD=...
export KEY_ALIAS=wakeup
export KEY_PASSWORD=...
./gradlew assembleRelease -PrequireSigning=true
```

也支持 `KEYSTORE_BASE64`（构建时解码到 `app/build/release-keystore.jks`），
以及写在 `gradle.properties` 里的同名属性。`.gitignore` 已忽略 `*.jks` / `*.keystore` /
`keystore.properties`，密钥不会被提交。

### 核对签名

`release.yml` 里有一个 `Report signing certificate` 步骤，会打印 APK 的证书指纹
并写进 job summary。**同一个 keystore 每次构建的指纹必须完全相同** ——
这就是验证签名稳定性的办法。本地核对：

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

### 发布说明（CHANGELOG）

**每次发版都要先写 [`CHANGELOG.md`](CHANGELOG.md)。** 它是发布说明的唯一来源：

- 一个版本一个 `## X.Y.Z` 段落（不带 `v`，与 `versionName` 一致），版本号大的在前；
- 只写用户能感知的变化，用 `新增` / `修复` / `注意` 三类，
  其中 **`注意` 用于升级前必须知道的事**（签名变更、需要卸载等）；
- 不要照抄 commit 标题 —— 提交信息是给开发者看的，日志是给用户看的。

`release.yml` 的 `Extract changelog` 步骤会按标签名（`v1.0.6` → `1.0.6`）切出对应段落，
作为 Release 正文。**找不到段落时退回自动生成的提交列表并打 warning**，不会让发布失败。

---

## 十一、说明

- 课表数据来自教务系统，具体课程请遵循所在学院实际安排。
- 代码与文档里**不含任何真实课表 token**：示例值统一是占位的 `100000`。
- App 不内置任何示例课表；`app/src/test/resources/timetable_payload.json` 是构造的测试数据，
  不会打进 APK。
- `ui/schedule/SchedulePreview.kt` 里的预览数据同样是虚构的，只服务于 Android Studio 预览。
- `network_security_config.xml` 允许用户证书（部分校园网需要安装学校根证书）。
- 第三方库：[Smartspacer 插件 SDK](https://github.com/KieronQuinn/Smartspacer)（Apache 2.0，
  仅用其 SDK，不涉及 Smartspacer 本体的 GPL 代码）。
