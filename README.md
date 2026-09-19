# DeepSeek API Usage

> 一个 Android 上的 DeepSeek API 用量看板 · 液态玻璃界面 · 支持峰谷计价反推
> An Android dashboard for DeepSeek API usage — liquid-glass UI, peak/off-peak price inference.

<!-- 截图放这里（建议放到 docs/screenshots/ 后取消注释）
| 概览 | 用量 | 错峰 | 桌面小组件 |
|---|---|---|---|
| ![](docs/screenshots/overview.png) | ![](docs/screenshots/usage.png) | ![](docs/screenshots/peak.png) | ![](docs/screenshots/widget.png) |
-->

---

## 中文

### 它解决什么问题

DeepSeek 开放平台**只提供余额查询 API，没有用量统计 API**。你只能在网页控制台里反复手动刷新，
既看不到分模型的消费构成，也算不清自己有多少调用落在了高峰时段。

这个 App 把这些数据搬到了手机上，并且常驻桌面小组件。

### 功能

- **余额** —— 走官方 `/user/balance` 接口，稳定可靠；按本月日均消费推算余额可用天数
- **用量构成** —— 分模型的 Tokens / 消费 / 请求次数 / 缓存命中率，逐日趋势图
- **峰谷计价** —— 当前处于高峰还是空闲（带倒计时）、**反推本月有多少比例落在高峰**、错峰可省多少钱、完整费率表
- **历史归档** —— 按月本地归档，支持「本月 vs 上月」环比与多月趋势
- **桌面小组件** —— 9 项数据 + 14 天趋势细条，不打开 App 就能看
- **余额预警** —— 后台每 30 分钟联网检查，低于阈值推送通知
- **CSV 导出** —— 按模型 + 按天两段式，导出到下载目录
- **凭证安全** —— API Key 与登录 Token 全部用 Android Keystore（AES-256-GCM）加密存储，明文不落盘

### 技术亮点

#### 1. 从聚合数据反推高峰时段占比

官方接口只给**按天汇总**，没有小时粒度，照理无法知道用量落在高峰还是空闲。
但官方费率有个关键性质——**每一项的高峰价都恰好是空闲价的 2 倍**：

| 计费项（元 / 百万 tokens） | flash 空闲 | flash 高峰 | pro 空闲 | pro 高峰 |
|---|---|---|---|---|
| 输入（缓存命中） | 0.02 | 0.04 | 0.15 | 0.30 |
| 输入（缓存未命中） | 1.0 | 2.0 | 4.5 | 9.0 |
| 输出 | 4.0 | 8.0 | 13.5 | 27.0 |

于是有恒等式：

```
实际消费 = 闲时理论成本 × (1 + 高峰占比)
⟹ 高峰占比 = 实际消费 ÷ 闲时理论成本 − 1
```

**这是代数恒等式，不是统计估计**，所以不需要小时级数据就能算出高峰占比。
实现见 `PeakPricing.analyze()`，并有单元测试锁定「峰价 = 闲价 × 2」这个前提——
一旦这条不变量被破坏，测试会立刻失败（`PeakPricingTest`）。

#### 2. 三档费率中的「旧模型名」一档是实测反推的

`deepseek-v4-flash` / `deepseek-v4-flash-vision-exp` 这两个已下线的模型名，
实际扣费费率与 `deepseek-flash` **不一致**。用「周末必然 100% 是空闲时段」这一约束
反解线性方程组，得到它们的费率是 `0.05 / 1.5 / 4.5`，三个周末日的实测值全部精确吻合到 1.000。

#### 3. RemoteViews 里画图表

桌面小组件不支持 `Canvas`。趋势图的做法是先用 `android.graphics.Canvas`
渲染成 `Bitmap`，再通过 `setImageViewBitmap()` 推进 `ImageView`；
分辨率按小组件实际尺寸（`OPTION_APPWIDGET_MIN_WIDTH`）动态计算，避免拉伸模糊。

#### 4. 全程在手机上编译

本项目**没有用电脑**——全部在 Termux 里编写并编译。核心是绕过 Android SDK 自带
`aapt2` 是 x86_64 二进制、在 ARM 设备上无法执行的问题：

```properties
android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
```

完整的踩坑记录、构建耗时实测、R8 验证方法见 [`BUILD.md`](BUILD.md)。

### 快速开始

```bash
git clone https://github.com/jackasan1/deepseek-api-usage.git
cd deepseek-api-usage
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

首次运行需要在代码签名上做一点配置——仓库**不含签名密钥**。
把 `keystore.properties.example` 复制为 `keystore.properties` 并填入你自己的密钥信息；
**若不做这一步，构建会自动退回 debug 签名**，功能完全可用。

然后在 App 内：

1. 填入 DeepSeek API Key（platform.deepseek.com → API Keys），用于读余额
2. 点「打开内置登录页自动获取」，在 App 内置的 WebView 里登录 DeepSeek，自动抓取用量凭证

### 构建变体

| 命令 | 用途 | 耗时（本机实测） |
|---|---|---|
| `./build.sh` | release 正式包（R8 + 资源压缩） | ~75s |
| `./build.sh fast` | 跳过 R8 与资源压缩，改代码后快速验证 | ~41s |
| `./build.sh clean` | 全量重编 | ~81s |
| `./build.sh dbg` | debug 变体 | ~60s |

### 项目结构

```
app/src/main/java/com/rikka/dsusage/
├── MainActivity.kt          外壳：分页导航 + 详情页路由 + 返回手势
├── data/
│   ├── Data.kt              Keystore 加密存储 + 官方余额接口
│   ├── Usage.kt             内部用量接口 + 合并聚合逻辑
│   ├── PeakPricing.kt       峰谷计价：费率表 / 时段判定 / 反推分析
│   ├── CacheStore.kt        最近快照缓存（离线可读）
│   ├── HistoryStore.kt      按月归档（环比与趋势）
│   ├── Monitor.kt           后台任务 + 余额预警通知
│   └── Exporter.kt          CSV 导出
├── ui/
│   ├── Glass.kt             玻璃材质组件 + 动画 + 手绘图标
│   ├── DashViewModel.kt     状态与请求（Job 取消防并发覆盖）
│   ├── Tabs1.kt / Tabs2.kt  四个分页
│   ├── Details.kt           三个下钻详情页
│   └── Charts.kt            趋势图
└── widget/BalanceWidget.kt  桌面小组件

app/src/test/                16 个单元测试（费率不变量 / 合并逻辑 / 日期补齐）
```

### 技术栈

Kotlin · Jetpack Compose (Material 3) · kotlinx.serialization · WorkManager ·
Android Keystore · minSdk 26 / targetSdk 36 · **无 Google Play 服务依赖**

### 免责声明

- 本项目为**个人开发的第三方工具**，与 DeepSeek 官方**没有任何关联**，未获其授权或认可
- 余额查询使用官方公开 API；**用量统计依赖平台网页端的内部接口**（`/api/v0/usage/*`），
  该接口未对外承诺、随时可能变更。接口失效时用量页会报错，但余额页走官方接口不受影响
- 所有凭证仅保存在本机 Keystore 中，**不会上传到任何服务器**；本项目不收集任何数据
- 请遵循 DeepSeek 的服务条款使用

---

## English

### The problem

The DeepSeek platform exposes **a balance API but no usage-statistics API**. You can only
sit in the web console hitting refresh, and you have no way to see your per-model spend
breakdown — or how much of your traffic landed in the expensive peak windows.

This app puts that on your phone, plus a home-screen widget.

### Features

- **Balance** — official `/user/balance` endpoint, with a runway estimate in days
- **Usage breakdown** — tokens / cost / requests / cache-hit rate per model, plus a daily trend
- **Peak vs off-peak** — live period indicator with countdown, **inferred peak share**, potential
  savings, and the full rate table
- **Monthly archive** — locally archived per month, enabling month-over-month comparison
- **Home-screen widget** — 9 data points plus a 14-day trend strip, no app launch needed
- **Low-balance alert** — background check every 30 min, push notification under a threshold
- **CSV export** — per-model and per-day sections, written to Downloads
- **Credential safety** — API key and login token are encrypted with Android Keystore
  (AES-256-GCM); plaintext never touches disk

### Technical highlights

**1. Inferring the peak share from aggregate-only data**

The API returns daily aggregates with no hourly granularity, so you would think the
peak/off-peak split is unknowable. But every line item's peak price is *exactly*
double its off-peak price, which yields an algebraic identity:

```
actual_cost = off_peak_cost × (1 + peak_share)
⟹ peak_share = actual_cost ÷ off_peak_cost − 1
```

Not a statistical estimate — an identity. A unit test pins the "peak = 2 × off-peak"
invariant so that any change breaking it fails loudly.

**2. Reverse-engineering a third rate tier**

The retired model names `deepseek-v4-flash` / `deepseek-v4-flash-vision-exp` are billed at
different rates than `deepseek-flash`. Exploiting the constraint that weekends are
*always* off-peak, the rates solve to `0.05 / 1.5 / 4.5` — matching three sampled
weekend days to within 1.000.

**3. Charts inside RemoteViews**

App widgets have no `Canvas`. The trend chart is rendered into a `Bitmap` with
`android.graphics.Canvas` and pushed via `setImageViewBitmap()`, sized from
`OPTION_APPWIDGET_MIN_WIDTH` so it stays sharp.

**4. Built entirely on a phone**

No desktop was used. The Android SDK ships an x86_64 `aapt2` that cannot execute on
ARM, worked around with:

```properties
android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
```

Full notes, measured build times and R8 verification steps: [`BUILD.md`](BUILD.md).

### Quick start

```bash
git clone https://github.com/jackasan1/deepseek-api-usage.git
cd deepseek-api-usage
./gradlew :app:assembleRelease
```

Signing keys are **not** in the repo. Copy `keystore.properties.example` to
`keystore.properties` and fill in your own — without it the build falls back to the
debug signature and still works.

Then, in the app: paste your DeepSeek API key, and use the built-in WebView login to
capture the usage credential automatically.

### Tech stack

Kotlin · Jetpack Compose (Material 3) · kotlinx.serialization · WorkManager ·
Android Keystore · minSdk 26 / targetSdk 36 · **no Google Play Services dependency**

### Disclaimer

- Unofficial personal project, **not affiliated with or endorsed by DeepSeek**
- Balance uses the official public API; **usage statistics rely on the platform's
  internal web endpoints** (`/api/v0/usage/*`), which are undocumented and may change
  without notice. When they break, the usage tab errors out while the balance tab keeps working
- All credentials stay in the on-device Keystore and are **never uploaded anywhere**;
  this project collects no data
- Please comply with DeepSeek's terms of service

## License

[MIT](LICENSE)
