# 许可

`ZeroTermux-CCS` 作为 `hanxinhao000/ZeroTermux` 的衍生作品，整体以
[GPLv3 only](https://www.gnu.org/licenses/gpl-3.0.html) 发布。

内置的 cc-switch 组件原为 MIT 协议。MIT 与 GPLv3 单向兼容，因此并入后
整体受 GPLv3 约束，但 cc-switch 的原始版权声明与 MIT 许可全文必须保留，
见下方「内置组件」一节及 [CREDITS.md](CREDITS.md)。

## 例外

以下部分沿用其原始协议：

- [Terminal Emulator for Android](https://github.com/jackpal/Android-Terminal-Emulator)
  代码，[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)。
  见 [terminal-view](terminal-view) 与 [terminal-emulator](terminal-emulator) 模块。
- [libcore/ojluni](https://cs.android.com/android/platform/superproject/+/android-11.0.0_r3:libcore/ojluni/)
  代码，[GPLv2 only with "Classpath" exception](https://openjdk.java.net/legal/gplv2+ce.html)。
  见 [termux-shared](termux-shared) 模块下 `com.termux.shared.file` 包。
- [libsuperuser](https://github.com/Chainfire/libsuperuser) 代码，
  [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)。
  见 [termux-shared](termux-shared) 模块下 `com.termux.shared.shell.StreamGobbler` 类。

## 内置组件：cc-switch

本项目内置 [farion1231/cc-switch](https://github.com/farion1231/cc-switch)
的 Rust sidecar 与 React 前端。其原始协议与版权声明如下，依 MIT 条款完整保留：

```
MIT License

Copyright (c) 2025 Jason Young

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## 商标与关联性

许可协议仅授予著作权许可，不授予商标许可。

- 本项目为非官方衍生版本，与 ZeroTermux 上游作者、cc-switch 上游作者、
  OpenAI 均无关联，未获得任何一方背书。
- "CC Switch" 及其官方站点 ccswitch.io 归其各自所有者；本项目不使用其
  品牌标识作为自身产品标识，也不暗示官方来源。
- "Termux" 归 Termux 项目所有者。本应用包名沿用 `com.termux`，这是
  ZeroTermux 上游的既有行为，非本项目引入。

## 改动声明（GPLv3 §5(a)）

本项目对上游文件的修改已在 [CHANGELOG.md](CHANGELOG.md) 中按主题列出，
逐文件改动可通过 git 历史查阅：

```bash
git log --oneline hanxinhao000/main..HEAD
```
