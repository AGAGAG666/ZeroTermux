# 更新日志

版本 `0.118.3.63`，分支 `feature/ccs-full-port`。
以下为相对 [hanxinhao000/ZeroTermux](https://github.com/hanxinhao000/ZeroTermux)
上游的全部改动。

## 新增：内置 CC Switch（供应商管理与路由代理）

把 cc-switch 完整搬进 ZeroTermux，侧边栏新增独立入口，不再需要 proot 容器
或桌面端。

- **跑上游前端本体**，不是功能子集重写。供应商管理、路由代理、模型映射、
  使用统计、连通性测试、S3/WebDAV 同步、技能管理均为原版实现
  （`9cb7d85` 取代了此前 14 文件 2081 行的原生精简版）
- **原生 sidecar 托管**：`libccsidecar.so` 以 `--webroot` 同源托管前端，
  经 stdout 握手把 `port`/`token` 交给 WebView，前端 shim 同步命中
- **进程守护**：前台服务保活，退出码 51 触发重启（`0f95b6b`、`b42e279`）
- **宿主能力桥接**：`pick_directory` 走 Android 目录选择器（`dbd5ddd`）
- **产物校验**：sidecar 与前端 zip 按 SHA-256 校验，webroot 缓存键取
  zip 摘要前缀，避免升级后用旧缓存（`4ded97a`）

### 路由与协议转换

- Codex 仅支持 Responses 协议；上游为 Chat / Anthropic 时由本地代理做格式转换
- 修复重复 Host 头导致 nginx 裸 400（`a778055`）
- 修复 Ctrl+C 打断后残留半截 assistant 消息，转 Anthropic 时被识别为
  prefill 而被网关拒收（`9767aa7`）
- 模型上下文默认设为 1,000,000（`f4f557c`）
- 支持从上游自动拉取模型列表并回填默认模型与模型映射（`5eaf6c5`）

### 移动端适配

- 开关控件尺寸、图标压扁、页头溢出、窄屏布局修复
- 供应商卡片支持触摸拖拽排序（此前只能靠鼠标）

## 新增：终端 dim 文本独立配色

`colors.properties` 新增 `dimforeground` 键，可单独指定 SGR 2（dim/faint）
文本的颜色 —— 即 Codex TUI 里命令回显的那类灰字。

此前 dim 色恒等于 `foreground × 2/3`，色相被锁死，想改灰字就只能改正常
文字颜色。新增独立调色板槽位后两者解耦（`f967861`）。

```properties
dimforeground:  #82a5b8
```

不配置该键时保持原有 2/3 缩放行为，对未配置的用户零影响。

同时修复两个相关缺陷：

- `updateWith()` 遇到未知键或非法色值时原本直接抛异常，而抛出前已应用了
  部分条目（Properties 遍历无序），导致整份配色进入半应用错乱态。改为
  跳过并记录警告（`f967861`）
- `checkForFontAndColors()` 只重置当前可见会话的调色板，后台会话仍用创建
  时的旧副本，`termux-reload-settings` 在那些会话上看似无效。改为遍历所有
  活跃会话（`5bbc65c`）

## 修复

- 恢复原生终端会话标题（`57dc80e`）
- 清理遗留会话名并规范化代理流（`f178204`）
- 修复文件访问与 DocumentsProvider 相关问题（`5eaf6c5`、`791dedb`）
- 过滤陈旧会话与协议选项（`52b3cce`、`7f84df6`）
- 修复 NanoHTTPD 状态码使用与代理端点回退循环（`2cf740b`、`04fcec7`、`bedaad3`）

## CI

- 新增 Android 单元测试工作流（`fc818df`）
- 新增从既有构建产物发布 Release 的工作流（`53807fc`）
- 新增 cc-switch 原生依赖交叉编译探测工作流（`830be99`、`ec26135`、`672ba65`）

所有工作流均为 `workflow_dispatch` 手动触发，仅仓库协作者可用。
