# 项目状态 · 交接文档

> **给接手的 AI / 协作者：先读这份，再读 `BUILD.md`。**
> 这份文档的目的是让一个没有任何上下文的 agent 能在 2 分钟内接手。

## 一句话

`~/ds-usage` —— Android 上的 DeepSeek API 用量看板，液态玻璃风格，
**全程在 Termux 里用手机编译，没有用电脑**。

- GitHub：https://github.com/jackasan1/deepseek-api-usage
- 包名 `com.rikka.dsusage` / 显示名「DeepSeek 用量」
- 当前版本：**1.1.0（versionCode 17）**

## 环境（关键，别跳过）

| 项 | 值 |
|---|---|
| 编译位置 | Termux（`~/ds-usage`） |
| Android SDK | `~/android-sdk`（build-tools 34/35、platforms 34/36） |
| AGP / Kotlin | 8.13.2 / 2.2.21 |
| Compose BOM | 2026.06.01（解析出 Compose 1.11.4） |
| JDK / Gradle | OpenJDK 21 / wrapper 8.14.3 |
| 签名 | `keystore.properties`（已 gitignore）；缺失时自动退回 debug 签名 |

### 构建命令

```bash
cd ~/ds-usage
./build.sh          # release 正式包，约 70-90s
./build.sh fast     # 跳过 R8 + 资源压缩，约 41s —— 改代码后验证用这个
./build.sh clean    # 全量重编
```

单元测试：`./gradlew :app:testDebugUnitTest`（当前 16 个全绿）

## 三个必踩的坑

### 1. aapt2 架构不匹配（已在配置里解决）

Android SDK 自带的 `aapt2` 是 x86_64 二进制，在 ARM 上直接 `Syntax error`。
`gradle.properties` 里这行**不能删**：

```properties
android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
```

### 2. Termux 会被 Android 冻结 —— 命令卡住不等于网络问题

Termux 退到后台久了会被系统冻结（cached app freezer），命令队列停止推进，
表现就是**命令挂住直到超时**。连 `echo`、`git remote -v` 这种纯本地命令都会卡。

**解法**：
```bash
am start -n com.termux/.HomeActivity     # 先把它唤到前台
```
长任务一律放后台 + 轮询日志，不要用一个前台命令死等：
```bash
nohup bash long_task.sh > /dev/null 2>&1 &
tail -5 long_task.log
```
排查：`uptime`（load 异常高）、`free -m`、`ps -A -o PID,RSS,NAME --sort=-RSS`（无失控进程 → 是冻结不是泄漏）

### 3. R8 可能剪掉 kotlinx.serialization 的序列化器

每次开混淆构建后都要验证：
```bash
unzip -p app/build/outputs/apk/release/app-release.apk classes.dex > /tmp/x.dex
grep -ao 'Lcom/rikka/dsusage/data/[A-Za-z]*[$][$]serializer;' /tmp/x.dex | sort -u | wc -l
# 应为 12 —— 少于这个数说明 keep 规则失效，App 会在运行时崩
```

## 设计系统 · 冷色 Frost（1.1.0 起）

三条硬规则：

1. **只用一套深色配色。** 玻璃的通透感建立在深背景上，浅底上的「玻璃」永远像塑料。
2. **数字全部用等宽字形**（`font-variant-numeric: tabular-nums` / 等宽字体）——刷新时字符宽度固定，布局不左右抖。
3. **语义色分离，红色只留给负向。**

| Token | 值 | 用途 |
|---|---|---|
| `accent` | `#5B9CFF` | 主数据 / 选中 / 可交互 |
| `accentHi` | `#A8C6FF` | 图表「最新一天」高亮 |
| `offPeak` | `#2DD4BF` | 正向 / 空闲时段 / 环比下降 |
| `peak` | `#FF5C8A` | 负向 / 高峰 / 环比上涨（**玫红，不是正红**） |
| `warn` | `#FFB84D` | 需要用户处理（旧模型名告警） |
| `onGlass` / `Muted` / `Faint` | `#EAF0FF` / `#93A3C7` / `#5A6B8F` | 文字三级 |
| 背景渐变 | `#04060F → #071722 → #050A18` | 深空 |
| 卡片圆角 | **20dp**（原来是 26dp） | |
| 卡片描边 | **顶部亮、向下衰减**，不是整圈均匀 | 模拟光从上方来 |
| Hero 卡片 | `GlassCard(glow = true)` | 品牌色径向光晕 |

**改动传播规律**：配色都走 `LocalGlass`，所以改 `Glass.kt` 里的 `rememberGlassColors()`
会自动传播到几乎全部界面。**例外是桌面小组件** —— 它的配色在
`res/values/colors.xml`，需要手动保持同步。

### 设计稿（可对比预览）

`/sdcard/Download/` 下三个 HTML：

| 文件 | 内容 |
|---|---|
| `design-v2.html` | 整版预览（6 屏 + 设计规格） |
| `design-ab.html` | 冷/暖色系 A/B（同结构、只换色） |
| `design-ab2.html` | 冷暖并排对比 |

本地预览：
```bash
cd /sdcard/Download && python3 -m http.server 8899 --bind 127.0.0.1
# 浏览器打开 http://127.0.0.1:8899/design-v2.html
```

## 已完成

- 余额（官方 `/user/balance`）+ 用量（平台内部接口）双数据源
- **峰谷计价**：从按天聚合数据**代数反推**高峰占比 —— 因为官方每一项的高峰价恰好是空闲价的 2 倍，
  所以 `高峰占比 = 实际消费 ÷ 闲时理论成本 − 1`，不需要小时级数据
- **三档费率实测反推**：旧模型名那档（`0.05/1.5/4.5`）是用「周末必为纯空闲」这一约束解出来的
- 按月归档 + 环比趋势
- 桌面小组件（RemoteViews 无 Canvas，图表是渲染成 Bitmap 再 setImageViewBitmap）
- 余额预警后台任务 + 通知
- 本地缓存（冷启动秒开、离线可读）+ 回前台自动刷新
- CSV 导出
- 16 个单元测试（锁定「峰价 = 闲价 × 2」不变量、`PROMPT_TOKEN` 必须跳过等踩过的坑）
- 冷色 Frost 重设计（1.1.0）

## 待办 TODO

按优先级：

1. **模型详情的「Token 三段构成条」** —— 设计稿第 ④ 屏是命中 / 未命中 / 输出三段用语义色分区
   （参考 `design-v2.html`）。`Details.kt` 里已有雏形，需要按设计稿调整
2. **其余详情页配色视觉确认** —— 余额/错峰/历史详情随色板自动更新了，但**没有在真机上看过**
3. **分级预警**（第三梯队最后一项）—— 单一阈值改成两档，每档每天最多提醒一次，
   防重复轰炸（记在 prefs 里）。改动集中在 `MonitorSettings` / `Notifier` / `BalanceWorker` / 设置页
4. **以下功能实现过但从未在真机验证**：
   - 后台余额预警的实际推送（测试技巧：**阈值临时填 9999**，下次任务必触发）
   - CSV 导出
   - 1.1.0 冷色重设计的实际观感

## 已知问题 / 操作纪律

- ⚠️ **不要用 awk 拼接改 `MainActivity.kt`。** 曾因漏写 `skip { next }` 把文件截断过一次
  （丢了整个 `GlassNavBar` 函数）。大改请整文件重写。
- 任何 awk/sed 批量改文件后，**必须查大括号余额**：
  ```bash
  awk '{for(i=1;i<=length($0);i++){c=substr($0,i,1); if(c=="{")n++; else if(c=="}")n--}} END{print n}' FILE
  # 必须是 0
  ```
- `values-night/` 与 `drawable-night/` 已删除（现在是单套深色配色，不做浅色模式）
- 首次运行需填 API Key；用量统计要在 App 内置 WebView 里登录一次

## 装到手机

```bash
cp ~/ds-usage/app/build/outputs/apk/release/app-release.apk /sdcard/Download/
```
然后在文件管理器里点开安装（需允许「安装未知来源应用」）。
