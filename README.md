# ZeroTermux-CCS

> ZeroTermux 改版 —— 内置 CC Switch 供应商管理与路由代理的 Android 终端环境。
>
> **非官方项目。** 与 ZeroTermux 上游、cc-switch 上游、OpenAI Codex 均无关联，
> 亦未获得其背书。CC Switch 官方站点为 ccswitch.io，本项目与之无从属关系。

---

## 这是什么

在 ZeroTermux（Termux 的第三方增强版）里内置了 [cc-switch](https://github.com/farion1231/cc-switch)
的完整实现，让你在手机上直接给 Codex CLI 切换供应商、做协议路由转换，
不需要 proot 容器、不需要另装桌面端。

核心区别在于**跑的是 cc-switch 上游 React 前端本体**（约 84k 行 TS/TSX），
由一个原生 Rust sidecar 同源托管，而不是功能子集的重写版。所以供应商管理、
路由代理、模型映射、使用统计、连通性测试、S3/WebDAV 同步、技能管理
全部是原版实现。

## 安装

从 [Releases](https://github.com/AGAGAG666/ZeroTermux-CCS/releases) 下载对应
架构的 APK 安装即可，支持覆盖安装（不中断已有终端会话）。

与官方 Termux、ZeroTermux **包名相同**（`com.termux`），三者互相覆盖、
不能共存。首次从其它版本切换过来前请自行备份 `~`。

sidecar 需要 **Android 7.0（API 24）** 及以上；低于此版本时 CC Switch
入口不可用，终端功能不受影响。

## 使用

### 打开 CC Switch

侧边栏（左滑抽出）→ **CC Switch**。首次进入会拉起 sidecar 前台服务，
之后常驻，切后台不被回收；通知栏可见其状态。

界面是 cc-switch 原版 React 前端，操作方式与桌面版一致。

### 配置供应商

在 CC Switch 里增删改供应商、切换当前项。写入的是标准配置文件：

| 文件 | 用途 |
|---|---|
| `~/.cc-switch/` | cc-switch 自身配置与供应商库 |
| `~/.codex/config.toml` `~/.codex/auth.json` | Codex CLI 读取 |
| `~/.claude/` | Claude Code 读取 |

因为落到的是 CLI 原本就读的文件，终端里的 `codex` 无需任何额外设置。

### 路由代理

Codex 只认 Responses 协议。上游若是 Chat 或 Anthropic 格式，
需要在 CC Switch 里开启**路由总开关**，由本地代理做协议转换。
Responses 格式的上游直连，不经转换。

切换供应商后，新起的 codex 进程即时生效（配置每次直读，无缓存）。

### 终端里用 Codex

```bash
codex
```

Codex 会话会自动归入右侧会话列表，可点击直接切换到对应终端画面。

## 排障

| 现象 | 处理 |
|---|---|
| CC Switch 打不开 / 白屏 | 通知栏确认 sidecar 在跑；杀掉服务重进即可重启 |
| 请求报 HTTP 400 且响应体是 HTML | 上游网关拦截，多为请求体过大，`/compact` 压缩上下文 |
| 请求报 400 且响应体是 JSON | 多为额度或参数问题，看 `message` 字段 |
| 切了供应商但模型没变 | 检查该供应商的模型映射；不在 `modelCatalog` 内的模型会被改写成配置里的默认模型 |

## 架构

```
┌─────────────────────────────────────────────────────┐
│ ZeroTermux (Android, com.termux)                    │
│                                                     │
│  ┌───────────────┐        ┌──────────────────────┐  │
│  │ 终端会话       │        │ CcsSwitchActivity    │  │
│  │ (Codex CLI)   │        │ WebView              │  │
│  └───────┬───────┘        └──────────┬───────────┘  │
│          │                            │ loadUrl     │
│          │ HTTP                       │ 127.0.0.1   │
│          ▼                            ▼             │
│  ┌────────────────────────────────────────────────┐ │
│  │ libccsidecar.so  (cc-switch Rust sidecar)      │ │
│  │  · 本地代理 / 协议转换 (Responses↔Chat↔Anthropic)│ │
│  │  · 同源托管 React 前端 (--webroot)              │ │
│  │  · SQLite: ~/.cc-switch/cc-switch.db           │ │
│  └────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────┘
                          │
                          ▼  上游 API
```

**启动序列**

1. 工作线程 `CcsSidecar.ensureStarted()`：解包前端产物 → 拉起原生进程 →
   读 stdout 握手行取 `port` / `token`
2. 主线程 `loadUrl("http://127.0.0.1:<port>/")`
3. sidecar 在 `index.html` 的 `<head>` 注入 `window.__CCS_SIDECAR__ = {port, token}`，
   前端 shim 同步命中，无需 Java 侧补握手

## 主要源码位置

| 路径 | 说明 |
|---|---|
| `app/src/main/java/com/termux/zerocore/ccs/CcsSidecar.java` | sidecar 进程生命周期、webroot 解包、握手 |
| `app/src/main/java/com/termux/zerocore/ccs/CcsSidecarService.java` | 前台服务，保活 |
| `app/src/main/java/com/termux/zerocore/ccs/CcsSwitchActivity.java` | WebView 宿主 |
| `app/src/main/java/com/termux/zerocore/ccs/CcsHostBridge.java` | JS ↔ 原生桥（`__CCS_NATIVE__`） |
| `app/src/main/java/com/termux/zerocore/ccs/CcsDirectoryPicker.java` | `pick_directory` 的宿主实现 |
| `app/src/main/java/com/termux/zerocore/ccs/CcsRestartPolicy.java` | 重启策略（退出码 51 触发） |
| `terminal-emulator/` `terminal-view/` | 终端模拟与渲染（含 dim 配色改动） |

sidecar 与前端产物不入库，由 `app/build.gradle` 按 `ext.ccsArtifactTag`
从构建产物拉取并校验 SHA-256。

## 构建

```bash
./gradlew :app:assembleDebug
```

CI 走 GitHub Actions，工作流均为 `workflow_dispatch` 手动触发
（仅仓库协作者可见可用）：

| 工作流 | 用途 |
|---|---|
| `build.yml` | 构建 APK（选架构） |
| `unit-tests.yml` | Android 单元测试 |
| `publish-release.yml` | 从已有构建产物发布 Release |
| `ccs-core-probe.yml` | cc-switch 原生依赖交叉编译探测 |
| `CI.yml` | push 到 `main` 时自动跑 |

## 许可

本项目整体以 **GPLv3-only** 发布，详见 [LICENSE.md](LICENSE.md)。
第三方代码归属与协议见 [CREDITS.md](CREDITS.md)。
本版本改动见 [CHANGELOG.md](CHANGELOG.md)。

上游 README 保留为 [README.upstream.md](README.upstream.md)。
