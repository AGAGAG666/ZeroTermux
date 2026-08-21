# ZeroTermux-CCS

ZeroTermux-CCS 是 ZeroTermux 的一个 Android 改版，内置了 CC Switch。它把供应商管理、本地路由和 Termux 放在同一个应用里。

本项目是非官方 fork，与 ZeroTermux、CC Switch 和 OpenAI Codex 没有从属关系，也没有得到这些项目的背书。

## 目前发布的版本

- APK 版本：`0.118.3.63`
- CC Switch：上游 `v3.20.0`
- CC Switch Android 产物：`ccs-android-2f8353d`
- ZeroTermux Release：`ccs-2f8353d`
- 支持架构：当前只有 `arm64-v8a` 包含 sidecar

正式 Release 中有两个 arm64 包：

- `ZeroTermux-0.118.3.63-debug_arm64-v8a.apk`
- `ZeroTermux-0.118.3.63-release_arm64-v8a.apk`

从 [Releases](https://github.com/AGAGAG666/ZeroTermux-CCS/releases) 下载对应文件。覆盖安装前，建议先备份 `~`。

> 应用包名仍是 `com.termux`，因此它不能和官方 Termux 或其他同包名版本并存。sidecar 要求 Android 7.0（API 24）或更高版本；低于 API 24 时，终端功能仍可用，但 CC Switch 页面不能启动。

## 使用 CC Switch

从侧边栏打开 **CC Switch**。第一次进入时，ZeroTermux 会启动 sidecar 前台服务，并在 WebView 中打开本地页面。通知栏会显示 sidecar 状态。

CC Switch 使用上游 React 前端。供应商、模型、路由、使用统计、连通性测试、Skills 和同步功能都由 CC Switch 处理。

### 供应商配置

CC Switch 使用 Termux 中已有的配置目录：

| 路径 | 用途 |
| --- | --- |
| `~/.cc-switch/` | CC Switch 数据库、供应商和运行设置 |
| `~/.codex/config.toml` | Codex 配置 |
| `~/.codex/auth.json` | Codex 认证信息 |
| `~/.claude/` | Claude Code 配置 |

切换供应商后，新启动的 CLI 进程会读取新的配置。模型目录或 CLI 已经加载的配置，可能需要重启对应 CLI；通常不需要重启整个 Termux。

### 路由代理

Codex 使用 Responses API。供应商上游如果使用 OpenAI Chat Completions 或 Anthropic Messages，需要在 CC Switch 中打开 Codex 路由，让本地代理完成格式转换。上游本身支持 Responses 时，CC Switch 直接转发。

模型映射由供应商配置决定。遇到请求格式错误时，先检查供应商的 API 格式、模型 ID、模型映射和路由状态，再看上游返回的 `cause`。

### Codex 终端

Codex 仍然在普通 Termux 会话中运行：

```bash
codex
```

当前版本不维护独立的 Codex 专属终端和右侧 Codex 历史会话列表。会话文件仍由 Codex 自己保存，恢复或切换请使用 Codex CLI 的会话功能。

## 架构

```text
ZeroTermux APK
├── Termux 终端
├── CcsSwitchActivity + WebView
├── libccsidecar.so       # Rust sidecar
└── assets/ccs-web.zip    # CC Switch 前端

sidecar
├── 本地代理
├── Responses / Chat / Anthropic 转换
├── SQLite 数据库 ~/.cc-switch/cc-switch.db
└── /rpc 与 /events 本地接口
```

sidecar 先解包前端，再启动本地 HTTP 服务。服务输出端口和 token，WebView 使用同一个地址访问页面、RPC 和 SSE。APK 内的 `libccsidecar.so` 和 `ccs-web.zip` 由 Gradle 按 tag、SHA-256 和文件大小校验。

## 常见问题

| 现象 | 先检查什么 |
| --- | --- |
| CC Switch 白屏或打不开 | 通知栏是否有 sidecar；重新打开页面前先确认服务没有被系统杀掉 |
| 上游返回 HTML 400 | 检查 endpoint、API 格式、请求头和模型；HTML 通常说明网关在返回 JSON 前就拒绝了请求 |
| 返回 JSON 400 | 看错误里的 `cause`，重点检查 `tools`、思考字段、消息顺序和模型能力 |
| 切换供应商后模型没变 | 检查模型映射和 Codex 当前进程；模型目录更新通常需要重启 Codex |
| 代理开关打开但请求没走本地 | 检查 `~/.codex/config.toml` 是否指向 `127.0.0.1:15721`，再确认 sidecar 端口是否监听 |
| 文件选择器闪退或目录打不开 | 检查目录权限和路径是否位于 Termux 可访问范围；不要直接把几十 GB 的 home 当作一次性扫描目录 |

## 主要源码位置

| 路径 | 内容 |
| --- | --- |
| `app/src/main/java/com/termux/zerocore/ccs/CcsSidecar.java` | sidecar 生命周期、前端解包和握手 |
| `app/src/main/java/com/termux/zerocore/ccs/CcsSidecarService.java` | 前台服务和保活 |
| `app/src/main/java/com/termux/zerocore/ccs/CcsSwitchActivity.java` | WebView 宿主 |
| `app/src/main/java/com/termux/zerocore/ccs/CcsHostBridge.java` | JavaScript 与 Android 桥接 |
| `app/src/main/java/com/termux/zerocore/ccs/CcsDirectoryPicker.java` | 目录选择器 |
| `terminal-emulator/`、`terminal-view/` | 终端模拟器和显示层 |
| `app/build.gradle` | CCS 产物 tag、SHA-256、size 和 APK 集成 |

## 构建和发布

本地需要完整 Android SDK/NDK。手机上优先使用 GitHub Actions：

```bash
gh workflow run build.yml \
  -R AGAGAG666/ZeroTermux \
  --ref feature/ccs-full-port \
  -f arch=arm64 \
  -f build_debug=true \
  -f build_release=true
```

常用工作流：

| 文件 | 用途 |
| --- | --- |
| `.github/workflows/build.yml` | 构建 APK |
| `.github/workflows/unit-tests.yml` | Android 单元测试 |
| `.github/workflows/publish-release.yml` | 发布 Debug 和 Release APK |
| `.github/workflows/ccs-core-probe.yml` | 检查 CCS 原生依赖的 Android 交叉编译 |

Actions artifact 名称里保留 `Nightly`，例如 `ZeroTermux-Nightly_release_arm64-v8a`。这是 CI 内部名称；正式 Release 文件不会带这个前缀。

## 许可证

本项目整体使用 GPLv3-only，见 [LICENSE.md](LICENSE.md)。第三方代码和许可证见 [CREDITS.md](CREDITS.md)。上游 README 保留在 [README.upstream.md](README.upstream.md)。
