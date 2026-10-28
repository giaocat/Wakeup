# 参与开发

## 环境

- JDK 17+（Android Studio 自带 JBR 即可）
- Android SDK：`platforms;android-37.0`、`build-tools;37.0.0`、`platform-tools`
- Gradle 由 `gradle/wrapper` 指定（9.6.0），首次构建自动下载

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

.\gradlew.bat assembleDebug              # 手机端 + 手表端 debug APK
.\gradlew.bat testDebugUnitTest          # 单元测试（纯 JVM，不需要设备）
.\gradlew.bat lintDebug                  # Android Lint
```

产物：`app/build/outputs/apk/debug/app-debug.apk`、`wear/build/outputs/apk/debug/wear-debug.apk`。

## 三个模块

| 模块 | 内容 | 规矩 |
| --- | --- | --- |
| `:app` | 手机端界面、Smartspacer、更新检查 | 只放手机专属逻辑；新文案先考虑能不能进 `:core` |
| `:wear` | 手表端界面、表盘复杂功能、信息块、Data Layer 接收 | 不写网络解析、不写作息规则，全部复用 `:core` |
| `:core` | 抓取 / 解析 / 缓存 / 设置 / 作息规则 / 同步编解码 | 不依赖 Compose 与 Activity，保持可单测 |

## 写代码时的约定

- **文案单点**：「下一节课」的措辞走 `core` 的 `NextClassPresenter`（手机今日页、Smartspacer、
  表盘复杂功能、信息块四处共用）。不要在界面里各自拼字符串。
- **时间口径**：课表起止时间只从 `Course.effectiveStartAt/effectiveEndAt(timetable)` 取，
  它会处理沈工大 G/F 座 3、4 节提前 10 分钟；左侧时间轴固定用官方作息 `PeriodTimes.all`。
- **间距与字号**：手机端间距走 4dp 基数（页面边距 16dp、区块 24dp），字号只用 `MaterialTheme.typography` 的角色。
- **注释与文档**：中文；解释"为什么这么写"和踩过的坑，而不是复述代码。
- **新增纯函数尽量抽出来**：分栏位置、文案拼装、编解码这类逻辑单独成函数并补单测，
  避免只能在真机上验证（`FoldAwareSplit` 的 `exactFirstPanePx`、`TimetableSyncCodec` 就是例子）。

## 提交与发版

提交信息用 `<type>: <一句话>`，`type` 取 `feat` / `fix` / `docs` / `chore` / `refactor` / `test`。

发版流程（缺一步 CI 会失败）：

1. 在 `CHANGELOG.md` 顶部新增 `## X.Y.Z` 段落（`新增` / `修复` / `注意` 三类，只写用户能感知的变化）；
2. 改 `gradle/libs.versions.toml` 里的 `appVersionName` / `appVersionCode`（**两个模块共用这一处**）；
3. 打标签 `vX.Y.Z` 并推送 —— `release.yml` 会校验「标签 == appVersionName」、
   「CHANGELOG 里存在该版本段落」，然后产出双 APK 并以该段落作为 Release 正文。

## 隐私

代码与文档里**不得出现真实的课表 token**；测试数据一律用虚构的占位值（如 `100000`）。
