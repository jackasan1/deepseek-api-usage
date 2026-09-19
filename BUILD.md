# DeepSeek 用量 — 构建备忘

## 环境（Termux / Android 真机）

| 项 | 值 |
|---|---|
| 设备架构 | aarch64 |
| JDK | OpenJDK 21.0.12 (Termux) |
| Gradle | wrapper 8.14.3（`~/.gradle/wrapper/dists` 已缓存） |
| Android SDK | `~/android-sdk`（build-tools 34/35、platforms 34/36） |
| AGP | 8.13.2 |
| Kotlin | 2.2.21 |
| Compose BOM | 2026.06.01（解析出 Compose 1.11.4） |
| 签名 | `keystore/release.jks`（复用于 notes-app / DSH） |

## 构建命令

```bash
cd ~/ds-usage && ./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

## 构建耗时记录

| 版本 | 内容 | 耗时 |
|---|---|---|
| 0.1 | 首个可编译骨架（余额 + Keystore） | 1m 30s |
| 0.2 | 余额 + 用量数据层 | 37s |
| 0.3 | 通知/导出 + 开启 R8 | 1m 13s |
| 0.4 | 错峰计价 | 1m 19s |
| 0.5 | 四页签 + 玻璃 UI | 1m 14s |
| 0.6 | Haze 真模糊（1.7.1） | 1m 28s |
| 0.7 | 视觉修正 | 1m 18s |
| 0.8 | 摘除 Haze | 1m 21s |

**稳定区间 1m 15s – 1m 30s**；失败编译 15–42s（更早中断）。

## 三个必踩的坑

### 1. aapt2 架构不匹配（最致命）

Android SDK 自带的 `aapt2` 是 x86_64 ELF，在 ARM 上直接 `Syntax error`。
**解法**：装 Termux 官方源的 ARM 版，并在 `gradle.properties` 重定向：

```properties
android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
```

```bash
pkg install aapt2     # 提供 ARM 原生 aapt2 16.0.0.4
```

### 2. material-icons-core 不是 material3 的传递依赖

移除 `material-icons-extended` 后 `Icons.Default.*` 会编译失败。
本项目改用 **Canvas 手绘图标**（`NavGlyph`），零依赖且可控。

### 3. 依赖版本可能悄悄要求更高的 AGP

Haze 1.7.3 按 Compose 1.12 编译，而 Compose 1.12 要求 **AGP 9.1+ / compileSdk 37**
（当前 AGP 最高支持 36）。报错形如：

```
Dependency 'androidx.compose.runtime:runtime-saveable-android:1.12.0'
requires Android Gradle plugin 9.1.0 or higher.
```

**判断原则**：库的编译基线**低于**运行时版本是安全方向（向后二进制兼容），
反过来才危险。所以应降库版本，而不是升 AGP。
本项目最终未使用 Haze（见下）。

## 体积演进

| 版本 | 大小 | 说明 |
|---|---|---|
| 0.2 | 12.7 MB | 未混淆 + material-icons-extended |
| 0.3 | 1.38 MB | 开启 R8 + 资源压缩（-88.6%） |
| 0.8 | 1.41 MB | 四页签 + 玻璃 UI，含全部功能 |

## R8 注意事项

`proguard-rules.pro` 保留：

```
-keep class com.rikka.dsusage.data.** { *; }   # kotlinx.serialization 数据类
-keep class com.rikka.dsusage.ui.** { *; }
```

**验证方法**（每次开混淆后都应检查）：

```bash
unzip -p app-release.apk classes.dex > /tmp/x.dex
grep -ao 'Lcom/rikka/dsusage/data/[A-Za-z]*[$][$]serializer;' /tmp/x.dex | sort -u
# 应输出 8 个 serializer
```

## 未采用的方案

- **Haze 真背景模糊**：已完整接入并验证可用（1.7.1），但因性能代价
  （全屏动画源 × 6 个模糊节点，每帧重算）而放弃，保留纯玻璃拟态方案。
- **AGP 9 / compileSdk 37**：需连带升级 Gradle 9 并下载 platform 37，改动面过大。

---

# 实测构建耗时（2026-09-19，本机 Android 16 / 5 核 / 15.4 GB RAM）

**用 `date +%s` 测墙钟，不采用 Gradle 自报数字。**

| 场景 | 命令 | 墙钟 | 说明 |
|---|---|---|---|
| 增量（无源码改动） | `assembleRelease` | **10s** | 47/49 任务 UP-TO-DATE |
| clean，缓存命中 | `clean assembleRelease` | **13s** | 21 个任务 FROM-CACHE |
| clean，缓存部分命中 | 同上 | **64s** | 视改动面而定 |
| clean + 无缓存 | `clean assembleRelease --no-build-cache` | **81s** | 含 Lint |
| clean + 无缓存 + 跳 Lint | `… -x lintVitalRelease -x lintVitalAnalyzeRelease` | **73s** | 44 任务全实跑，0 缓存 |

**最坏情况 ≈ 73–81 秒。**

## 阶段拆解（全量那一次，带逐行时间戳测得）

```
 +  5s   配置阶段
 + 12s   compileReleaseKotlin          ← Kotlin 编译
 + 22s   lintVitalAnalyzeRelease       ← Android Lint
 +  8s   minifyReleaseWithR8
 + 27s   资源压缩 + 打包
```

## 已开启的优化开关

`gradle.properties`：

```properties
org.gradle.caching=true                  # 构建缓存（跨 clean 生效）
org.gradle.configuration-cache=true      # 配置缓存
org.gradle.parallel=true
org.gradle.vfs.watch=false               # 手机文件监听不可靠，关掉省 CPU
```

## 构建脚本

```bash
./build.sh          # 常规（约 60s）
./build.sh fast     # 跳过 Lint
./build.sh clean    # 全量
```

## 关于"感觉要 5 分钟"

**本机 Gradle 最坏 81 秒，复现不出 5 分钟。** 观察到 5 分钟量级通常来自：

1. **终端交互开销**：后台启动构建后再轮询日志，会额外叠加等待时间。
   若用 Agent 驱动，从"开始编译"到"APK 到手"的墙钟会被这个放大好几倍，
   而 Gradle 实际只用了约 80 秒。
2. **热节流**：机身温度超过 ~36°C 时，长时间编译会被降频。截图时电池已是 36.4°C。
3. **跑错任务**：`./gradlew build` 或 `assembleDebug assembleRelease` 会跑全部变体
   + 单元测试 + 全量 Lint，耗时翻倍以上。只出 release 包应使用 `:app:assembleRelease`。
4. **构建缓存被清**：`~/.gradle/caches/build-cache-1` 若被系统清理，
   下次 clean 构建会退化到 81 秒档。

---

# 构建提速（2026-09-19 第二轮实测）

## 新增 fast 变体

`build.gradle.kts` 增加第三种构建类型，跳过 R8 与资源压缩，但**保留 release 签名**：

```kotlin
create("fast") {
    initWith(getByName("release"))
    isMinifyEnabled = false
    isShrinkResources = false
    applicationIdSuffix = ".fast"    // 与正式版共存安装
}
```

## 实测对比（同一份改动）

| 目标 | 命令 | 墙钟 | 包体 |
|---|---|---|---|
| **fast 变体** | `./build.sh fast` | **41s** | 8.97 MB |
| debug 变体 | `./build.sh dbg` | 60s | 12.4 MB |
| release 正式包 | `./build.sh` | **75s** | 1.56 MB |
| clean 全量 | `./build.sh clean` | ~81s | — |

**fast 比 release 快 45%。** 代价是包体大 5.7 倍且运行时无优化，只适合功能验证。

## 关掉 release Lint

```kotlin
lint {
    checkReleaseBuilds = false
    abortOnError = false
}
```

`lintVital*` 任务从构建图中彻底消失（实测任务数归零）。
需要跑 Lint 时单独执行：`./gradlew :app:lintRelease`。

## 耗时构成（release）

改一个 Kotlin 文件后实际执行的任务：

```
compileReleaseKotlin                    ← Kotlin 编译
expandReleaseArtProfileWildcards
minifyReleaseWithR8                     ← R8
convertShrunkResourcesToBinaryRelease   ┐
optimizeReleaseResources                ├ 资源压缩，fast 变体跳过这三项
compileReleaseArtProfile                ┘
packageRelease
```

**结论：慢的不是任务数量，而是 R8 + 资源压缩 + 打包这条链。**
纯增量（无源码改动）只要 10 秒；一旦改了源码就要重走这条链，约 75 秒。
所以提升迭代速度的正确做法是切到 `fast` 变体，而不是设法减任务。

---

# 单元测试

```bash
./gradlew :app:testDebugUnitTest
# 报告：app/build/test-results/testDebugUnitTest/*.xml
```

当前 **16 个测试全部通过**：

| 测试类 | 数量 | 覆盖 |
|---|---|---|
| `PeakPricingTest` | 11 | 高峰窗口四个边界、周末判定、跨周末跳转、**"高峰价 = 空闲价 ×2" 不变量**、三档费率数值锁定、模型名→费率档映射、高峰占比反推（0%/100%/夹取） |
| `UsageMergeTest` | 5 | **`PROMPT_TOKEN` 必须跳过**（防重复计数）、按天汇总跳过 `REQUEST`、零用量日过滤、缺失日期补零 |

其中两条是**用踩过的坑换来的**，改动相关代码时它们会立刻报警：

1. `PROMPT_TOKEN 必须被跳过否则会重复计数` —— 接口返回的 `PROMPT_TOKEN` 恒为 "0"
   且已包含在 hit+miss 中，照抄会静默地把 token 数算多。
2. `每一档费率的高峰价都恰好是空闲价的两倍` —— 这是高峰占比反推公式的**全部前提**，
   一旦被改坏，App 显示的"高峰占比"会整体失真而不报错。

## 注意：设备内存

编译期间观测到 `free` 只剩约 1.6 GB、`load average` 8.6（5 核设备），
因此 `gradle.properties` 的堆已从 `-Xmx3g` 下调到 `-Xmx2g`。
若构建莫名变慢或 Termux 无响应，先检查内存压力再怀疑代码。

---

# 历史归档（1.3）

`data/HistoryStore.kt` 按月归档 `MonthRecord`（消费 / tokens / 请求 / 缓存命中率 / 高峰占比），
存于 SharedPreferences，上限 24 个月。

## 两条关键设计约束

1. **归档对任意月份都做，而"最近快照缓存"只存当月。**
   因为翻旧月份拉数据时应该能补录历史，但绝不能用旧月份的数据
   污染冷启动要用的那份"最近快照"。

2. **值未变化就不写盘。**
   `upsert` 比较同月记录的 cost / tokens / requests，相同则返回 false，
   调用方据此跳过 `state` 更新，避免每次刷新都触发无意义的重组。

## 缺失数据的处理方式

接口只能按月查询，所以历史必须靠本地累积。**装上新版本后不会凭空出现过去的数据**，
只能通过「用量」页切换到历史月份触发拉取来补录，或者等每月自然积累。
界面上对缺失环比的情况显示"缺少上月归档，暂时算不出环比"，而不是显示 0%。
