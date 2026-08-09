# 代码引用与第三方组件

本项目的源代码与第三方组件归属如下。许可义务按 [LICENSE.md](LICENSE.md) 执行。

## 上游源码仓库

| 组件 | 来源 | 协议 | 在本项目中的使用 |
|---|---|---|---|
| ZeroTermux 主体 | [hanxinhao000/ZeroTermux](https://github.com/hanxinhao000/ZeroTermux) | GPLv3-only | 整个 Android 应用为它的衍生版本 |
| cc-switch | [farion1231/cc-switch](https://github.com/farion1231/cc-switch) | MIT © 2025 Jason Young | 内置 Rust sidecar (`libccsidecar.so`) 与 React 前端 (`ccs-web.zip`)，由本仓库 fork 分支 [AGAGAG666/cc-switch:android-sidecar](https://github.com/AGAGAG666/cc-switch/tree/android-sidecar) 构建 |
| Terminal Emulator | [jackpal/Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator) | Apache-2.0 | `terminal-emulator`、`terminal-view` 模块 |
| libcore/ojluni | [AOSP libcore](https://cs.android.com/android/platform/superproject/+/android-11.0.0_r3:libcore/ojluni/) | GPLv2-only + Classpath 例外 | `termux-shared` 模块 `com.termux.shared.file` 包 |
| libsuperuser | [Chainfire/libsuperuser](https://github.com/Chainfire/libsuperuser) | Apache-2.0 | `termux-shared` 模块 `com.termux.shared.shell.StreamGobbler` 类 |

## Java/Kotlin 依赖（Maven Central / Google Maven）

| 坐标 | 用途 |
|---|---|
| `io.github.rosemoe:editor` / `language-textmate` | 内置代码编辑器 |
| `androidx.appcompat` / `material` / `constraintlayout` / `cardview` | Android UI 基础 |
| `androidx.room:room-runtime` / `room-ktx` | 本地数据库 |
| `androidx.biometric:biometric-ktx` | 生物识别 |
| `commons-net` / `ftpserver-core` / `jlan` 等（`app/libs/`） | FTP 服务端、网络工具 |

## 构建期二进制（不入库）

`app/build.gradle` 按 `ext.ccsArtifactTag`（当前 `ccs-android-c15583c`）从
构建产物下载，并校验 SHA-256：

- `libccsidecar.so` —— cc-switch 的 Android aarch64 原生 sidecar
- `ccs-web.zip` —— cc-switch 上游 React 前端构建产物

两者均为 [AGAGAG666/cc-switch](https://github.com/AGAGAG666/cc-switch)
`android-sidecar` 分支的构建产物，协议同其上游（MIT）。

## 图标与品牌

本项目不使用任何第三方的商标或 logo 作为自身标识，也不暗示与
ZeroTermux / cc-switch / OpenAI 存在官方关联。
