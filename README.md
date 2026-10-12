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

```bash
git tag v1.0.0
git push origin v1.0.0
```

> 未配置签名密钥时，Release 产出的是 `app-release-unsigned.apk`。
> 若要产出可直接安装的签名包，在仓库 Settings → Secrets 添加
> `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`，
> 并在 `app/build.gradle.kts` 的 `signingConfigs` 中引用。

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
│   └── Timetable.kt              # WeekExpression / PeriodTimes / Term
├── ui/
│   ├── WakeupApp.kt              # Scaffold + 底部导航
│   ├── WakeupViewModel.kt        # 课表状态、周次切换、缓存恢复、设置项
│   ├── schedule/ScheduleScreen.kt
│   ├── today/TodayScreen.kt
│   ├── settings/SettingsScreen.kt
│   ├── components/CourseDetailSheet.kt
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

当前共 **21 个测试**，`.\gradlew.bat testDebugUnitTest` 全部通过。

### 安装到设备

```powershell
.\gradlew.bat installDebug           # 需要已连接设备或运行中的模拟器
# 或直接安装产物：
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

首次启动没有缓存，会提示去「设置」填课表链接；同步成功一次之后即可离线查看。

---

## 六、作息时间

`PeriodTimes.all` 是**标准作息**（第 1 节 08:30 起，每两节为一个大节）：

| 节次 | 时间 | 节次 | 时间 |
| --- | --- | --- | --- |
| 1–2 | 08:30–10:05 | 7–8 | 15:25–17:00 |
| 3–4 | 10:25–12:00 | 9–10 | 18:00–19:35 |
| 5–6 | 13:30–15:05 | 11–12 | 19:40–21:15 |

### GF 座 3、4 节提前 10 分钟

**沈工大 GF 座**的教学楼在上第 3、4 节时提前 10 分钟上下课：

| 节次 | 标准作息 | GF 座 |
| --- | --- | --- |
| 3 | 10:25–11:10 | **10:15–11:00** |
| 4 | 11:15–12:00 | **11:05–11:50** |

规则**只对 GF 座生效**，且必须同时满足两个条件：

1. **课表来源是沈工大域名** —— host 为 `sut.edu.cn` 或 `*.sut.edu.cn`（如 `sutjwt.sut.edu.cn`）；
2. **教室名以 `GF` 开头**（去空格、忽略大小写，如 `GF-101`、`GF204`）。

其余教学楼（`F-105`、`B-109`、`C-101`…）以及 3、4 节之外的节次都保持标准时间。
**别的学校的课表即使有间教室叫 `GF-xxx` 也不会被改时间** —— 域名不匹配。

实现要点：

- `isSutDomain(url)` 判断来源域名；`Timetable.sourceUrl` 由数据层写入（联网时取实际请求地址，读缓存时取缓存里记录的地址）；
- `Course.usesGfSchedule(timetable)` = 沈工大域名 **且** 教室名匹配；
- `Course.startAtWithGf` / `endAtWithGf` 是含 GF 偏移的时间，`standardStartAt` / `standardEndAt` 是不含偏移的标准时间；
  **课表格子、"今日"页面、课程详情**都按来源学校 + 教室选择用哪一个，所以「正在上课」的判断对 GF 座的课也是准确的；
- GF 座的课在格子上会标出「提前 10:15」，详情面板也会注明。

改作息只需动 `domain/Timetable.kt` 里的 `PeriodTimes.all`（标准）与 `gfTimes`（GF 座）。

相关校验见 `app/src/test/java/com/wakeup/UpdateAndSchoolTest.kt` 与 `CourseVisualsTest.kt`：
覆盖 GF 座 3、4 节偏移、其余节次不受影响、其它楼栋不受影响、前缀识别容错、
**外校域名不触发 GF 作息**、沈工大域名识别（含 `notsut.edu.cn`、`evil.com/?u=…` 等反例）。

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

- 数据源：`https://api.github.com/repos/Koneko-sakana/Wakeup/releases/latest`（仓库地址在 `app/build.gradle.kts` 的
  `buildConfigField` 里配置：`GITHUB_REPO` / `RELEASES_API_URL` / `RELEASES_PAGE_URL`）；
- 版本比较见 `VersionCompare`：按 `major.minor.patch` 逐段比，段数不同时缺的段按 0；
  带 `-beta1` 之类后缀的视为**预发布**，同号下低于正式版（所以 `pre-release` 不会推给用户，代码里也直接跳过
  `prerelease`/`draft`）；
- 采取「提示 + 打开发布页」的方式，**不做应用内下载安装** —— 后者需要 `REQUEST_INSTALL_PACKAGES` 权限，
  也会被系统的未知来源限制拦住。因此应用不需要任何新权限。

相关校验见 `UpdateAndSchoolTest.kt`：版本号解析（含 `v` 前缀、`-beta`、`+build`、非法输入）、
新版本判定、预发布语义、以及比较结果不受整型溢出影响。

---

## 九、说明

- 课表数据来自教务系统，具体课程请遵循所在学院实际安排。
- 代码与文档里**不含任何真实课表 token**：示例值统一是占位的 `100000`。
- App 不内置任何示例课表；`app/src/test/resources/timetable_payload.json` 是构造的测试数据，
  不会打进 APK。
- `ui/schedule/SchedulePreview.kt` 里的预览数据同样是虚构的，只服务于 Android Studio 预览。
- `network_security_config.xml` 允许用户证书（部分校园网需要安装学校根证书）。
