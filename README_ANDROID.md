# Android 构建与验收说明 · 1.6.0 预览版

1.6.0 请在 vivo Android 16 上检查：键盘弹出后最新消息可见；手动上翻后新增消息和键盘变化不打断阅读；返回底部后继续跟随。确认旧人设完整保留、分类填写和表情开关可用，并检查情绪自动变化、关闭、手动调整及重置。自动判断随聊天请求返回，不增加单独的 API 请求，但可能增加用量。

本版请在 vivo Android 16 上分别验证拖动、松手甩抛、跨应用悬浮、屏幕边缘及高刷新率，再验证四页切换和聊天键盘。长期记忆默认关闭，需在设置中确认费用后开启；完成 10 轮后检查整理及查看、修改、删除，超过每小时 3 次需等待。系统可能延迟后台任务。不要把真实密钥和聊天记录放进截图或 GitHub。

人设最多 10000 个 Unicode 字符，保存前显示当前字数，提交聊天时会随 API 发送并增加用量。保存带提醒时间的待办可引导授予通知和精确提醒权限。待办的“手机时钟”会请求打开时钟应用供用户确认；标准接口只传时分，不能可靠传递任意日期或秒数，不匹配时需用户手动核对。时钟中的闹钟独立管理，修改或删除待办不会同步更改它。

本项目基于 [PC2005-cloud/dsh-pet](https://github.com/PC2005-cloud/dsh-pet) 改造，固定上游提交 `cc498c3be921ace27a8eca72626759a107c3d672`。本文件只说明当前安卓实现，不将其描述为完整 DSH 插件的等价替代。

## 环境与生成 APK

- Android Studio，项目要求的 SDK 37、AGP 9.4.1、Gradle Wrapper 9.6.0 及兼容 JDK。
- Node.js 24+；本机验证版本为 24.15。
- 支持安装的最低系统为 Android 8.0（API 26）；实际效果受 WebView 和设备能力影响。

发布源码中的 `dshgit/` 是已下载的上游快照，包含程序源码和运行素材，不包含 Git 历史。仅省略展示用 GIF/截图、示例源视频及开发辅助元数据；原运行源码、109 个原动画和许可证保留。

在根目录执行：

```sh
npm --prefix android ci --ignore-scripts --no-audit --no-fund
./gradlew :app:assembleDebug --no-daemon --max-workers=1
```

首次需下载构建依赖；缓存齐备后可以添加 `--offline`。构建自动运行 `android/prepare-assets.mjs`，校验上游文件，生成共享逻辑和本地页面，不启动 Electron 或 DSH，不做视频转码。

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。Android Studio 也可选择 Build 菜单生成 APK。Node 不在可执行搜索路径时，设置 Gradle 属性 `nodeExecutable=/绝对路径/node`。

当前提供的是调试签名测试包。正式分发需自己创建/保管发布 keystore，在 Android Studio 中选择 Generate Signed App Bundle or APK → APK。不要提交 keystore、密码或本机配置；更换签名会影响后续覆盖安装。

构建默认单 worker、约 1GB 堆内存、关闭并行，不运行本地模型。`local.properties` 由各自 Android Studio 生成，不随源码分发。

## 关键文件

| 路径 | 作用 |
| --- | --- |
| `dshgit/dsh-pet/src/shared/` | 原物理、随机动作、菜单和碰撞规则 |
| `dshgit/dsh-pet/runtime/electron-helper/` | 原视频播放、交互与气泡渲染 |
| `android/upstream-tree.json`、`android/prepare-assets.mjs` | 固定版本校验、构建素材与配置 |
| `android/pet/` | 安卓桥、触屏菜单、离线/原生 API 边界 |
| `app/src/main/java/com/dailytodo/mydeepseek/PetScene.kt` | 专用窗口环境、裁剪、原生坐标、余额气泡、生命周期 |
| 同目录 `PetFrame.kt`、`PetOverlayService.kt` | 最新帧合并、稳定窗口跨度、多宠物广播和前台服务 |
| 同目录 `MainActivity.kt`、`ChatActivity.kt`、`AppNavigation.kt` | 四页导航、权限、计费/密钥弹窗、应用内聊天和键盘 |
| 同目录 `PetMemories.kt`、`MemoryRules.kt` | 长期记忆加密队列、后台调度、次数限制与管理 |
| 同目录 `PetApi.kt`、`DeepSeekTransport.kt`、`DeepSeekProtocol.kt` | 余额、流式对话、上下文限制、取消和错误处理 |
| 同目录 `PetSecureStore.kt`、`SecretEnvelope.kt`、`PendingValueWrite.kt` | 本机加密、原子写入、密钥保存取消回滚 |
| 同目录 `PetCompanion.kt`、`PetCompanionRules.kt` | 自动陪伴、次数限制、动作/表情包白名单 |
| 同目录 `TodoActivity.kt`、`PetTodos.kt`、`TodoRules.kt`、`TodoAlarms.kt` | 语音、AI 草稿、本机待办、系统提醒和恢复 |
| `app/src/main/assets/control/`、`chat/`、`todo/` | 全部内置手机界面 |
| `app/src/main/AndroidManifest.xml` | 权限、界面、服务和提醒接收器 |

上游源码保持原样。生成的 renderer 仅替换两处启动适配，原窗口桥由安卓实现承接。未接入的 DSH 宿主、其他插件联动、原语音合成和电脑截屏不会伪造成功。

## 使用与计费

长按宠物点“查看余额”只显示余额气泡；点“对话”打开聊天并请求键盘；“待办事项”进入本机小事记。

密钥只在安卓原生密码框输入，先显示计费提醒，验证成功后加密保存。对话默认使用 `deepseek-flash`，关闭 thinking；单次输入最多 4000 字符，最多携带最近 8 轮完整对话，历史与当前输入最多 16000 字符，回复上限 800 tokens。

碎碎念、梦话、聊天配图/动作需在陪伴设置中选择。自动选项默认关闭；自动请求全局每滚动一小时最多 12 次，不代表金额上限。锁屏、交互和自动请求失败时暂停，不补发错过周期，不自动重试。已经发生的用量以 [DeepSeek 官方账单与价格](https://api-docs.deepseek.com/quick_start/pricing/)为准。

待办和手动提醒无需 API；语音由系统服务处理，可能联网。只有点“AI 整理”才发送原话、当前手机时间和时区，AI 只生成草稿。再次确认保存后才创建本机记录和提醒。不明确或已过去的 AI 时间不会自动排期。提醒需开启通知，精确提醒还需要系统许可；普通提醒、省电、强制停止或关机可能影响送达。

## 自动验证

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon --max-workers=1
```

浏览器检查脚本为 `android/verify-control.mjs`、`verify-scene.mjs`、`verify-chat.mjs`、`verify-todo.mjs`。它们使用模拟数据，不调用付费 API 或实际麦克风。需要自行安装 Playwright 并提供本机 Chromium/Chrome，不会由这些脚本下载浏览器：

```sh
PLAYWRIGHT_MODULE=/path/to/playwright/index.mjs CHROME_EXECUTABLE=/path/to/chrome node android/verify-todo.mjs
```

已检查：原动画解码/哈希、睡眠与抛掷、手机点击多级菜单、余额和错误状态、聊天流式交互、本机待办流程、AI 预览不会自动保存、模型文本不执行 HTML，以及密钥取消/失败回滚。具体自动结果以分发包中的验证摘要为准。

## 真机验收仍然必要

1. 允许悬浮窗后回到桌面，切换其他应用，拖动、甩抛、旋转和多宠物显示。
2. 长按菜单逐层点击；余额不能打开聊天，聊天需自动请求键盘且收起后释放焦点。
3. 用自己的密钥验证余额和小额对话，不向维护者发送密钥；检查取消、停止、断网和密钥移除。
4. 待办检查手动保存、完成、改期、删除；拒绝/允许通知与精确闹钟；锁屏、省电、重启后的提醒。
5. 语音检查拒绝麦克风、无服务/语言包、退出页面停止录入；AI 草稿确认前不能创建记录。
6. 检查大字号和无障碍、不同键盘及 WebView。源码和浏览器检查不能证明所有厂商手机都已验证，也不能证明固定帧率。

隐私和权限详情见 [PRIVACY.md](PRIVACY.md)。上游来源和素材限制见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
