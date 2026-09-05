# 技术实现

模块按进程与功能域安装针对性的 Hook。入口只负责生命周期、进程筛选、配置注入与安装结果汇总；具体目标定位和拦截逻辑留在各自功能域中。

## Hook 安装与诊断

- 每个功能域通过 `HookRegistrar` 注册 Hook，并使用稳定 ID、`PROTECTIVE` 异常模式和统一优先级策略。
- 安装结果区分 `INSTALLED`、`MISSING`、`FAILED`、`SKIPPED`，保留 `HookHandle`，便于定位 ROM 或目标 App 升级后的签名漂移。
- 普通目标缺失与反射异常按功能域失败开放；`HookFailedError` 等框架级 `Error` 不会被普通异常隔离层吞掉。
- `ModuleMain` 会尽早过滤无关进程并调用 `detach()`，避免在不需要的进程中继续保留生命周期回调。

## 日志与 Release 裁剪

Eta 使用同一组四级日志语义，并按运行环境选择后端：App 与 Agent Runtime 通过 `AndroidAgentLogger` 写入 logcat，Hook 进程通过 `ModuleLogger` 写入 Xposed 日志。业务代码不得直接调用 `android.util.Log` 或 `XposedModule.log`。

| 级别 | 使用范围 | Release |
| --- | --- | --- |
| `DEBUG` | 高频正常流程、目标匹配、重试细节、尺寸与计数 | 全部裁剪 |
| `INFO` | 低频生命周期、Hook 安装汇总、特权动作的结构化摘要 | 保留 |
| `WARN` | 可恢复降级、fallback、目标签名漂移、重试耗尽 | 保留；高频事件必须节流 |
| `ERROR` | 当前请求或功能确定无法完成、关键不变量被破坏 | 保留 |

取消、功能关闭和可选目标缺失不记为 `ERROR`。`debug` 只接受惰性 supplier；supplier 必须是纯观察代码，不能执行 Hook、反射写入、状态变更或其他业务副作用，因为 Release 的 R8 会删除整次调用。

任何级别都禁止记录 Prompt、请求或响应正文、API Key、认证 Header、Cookie、工具参数与结果、原始命令、stdout/stderr、URI、文件路径、图片内容、应用清单和原始运行标识。异常默认只记录类型，不拼接 `Throwable.message`；外部或模型生成的名称必须先转成受长度和字符集约束的安全 token。只有确认不承载用户数据的框架或反射异常才允许附带完整堆栈。

Release 裁剪以 `app/proguard-rules.pro` 为唯一可执行事实来源，规则边界如下：

- `-maximumremovedandroidloglevel 3 class io.github.mangi.eta.** { *; }` 只删除 Eta 自有代码中的 Android `VERBOSE/DEBUG`，不影响依赖库。
- 对 `AgentLogger.debug(Function0)`、`AndroidAgentLogger.debug(Function0)` 和 `ModuleLogger.debug(Function0)` 使用精确的 `-assumenosideeffects`，覆盖 R8 无法识别的 Xposed 日志后端。
- 不为 `INFO/WARN/ERROR` 声明无副作用，不使用 `*Logger` 或全局 `android.util.Log` 通配裁剪规则。
- 每次修改规则后同时构建 Debug 与 Release，并检查 R8 configuration/usage、DEX 日志调用、代表性日志字符串和 Xposed 入口元数据。

上述策略依据 Android 官方的 [R8 附加规则](https://developer.android.com/topic/performance/app-optimization/additional-rule-types)、[日志信息泄露防护](https://developer.android.com/privacy-and-security/risks/log-info-disclosure)、AOSP [日志级别约定](https://source.android.com/docs/core/tests/debug/understanding-logging)、OWASP [运行时日志测试](https://mas.owasp.org/MASTG/tests/android/MASVS-STORAGE/MASTG-TEST-0203/) 与 [CWE-532](https://cwe.mitre.org/data/definitions/532.html)。

## Eta 原生数字助理

Manifest 注册 `VoiceInteractionService`、独立进程的 `VoiceInteractionSessionService`、全屏 `TYPE_APPLICATION_OVERLAY` 助理浮窗以及 Android 助理角色资格要求的 `RecognitionService`。设置页只负责打开系统数字助理选择界面；当前浮窗不请求麦克风权限。

`VoiceInteractionSession` 只承接系统入口并关闭自身 UI；`EtaAssistantOverlayService` 持有全屏窗口、彩色边缘动画和键盘输入。窗口通过 `setFitInsetsTypes(0)` 绘制到状态栏、导航栏与显示开孔后方，可交互内容再通过 `WindowInsetsRulers.SafeDrawing` 与 `Ime` 保持可触达，避免给根容器增加 Insets 后截断 edge-to-edge 背景。用户提交的文本交给 `AgentRuntimeClient`；请求、流式结果、前台工具收起、取消与归档沿用既有 Runtime 协议。前台工具执行前，Eta 自有入口在主线程定向移除窗口并通知系统会话 `hide()`，Runtime 等待该 View 真正 detach 后才继续；小布与超级小爱等外部入口仍使用返回动作和目标包窗口确认。当前不执行语音识别或语音朗读。

`:voice`、`:voice_session` 与 `:recognition` 进程只初始化本地偏好，不预热数据库、Skills 或 Xposed UI 服务。`RecognitionService` 仅保留 Android 数字助理角色资格所需声明，不由当前浮窗调用；HyperOS 按键适配不在当前实现范围内。

## system_server

- **电源键接管**：Hook `PhoneWindowManagerExtImpl$OplusSpeechHandler.handleMessage()` 处理系统分发给小布的唤醒消息（`what == 0x3F3`）。目标为小布时直接执行原方法；目标为 Gemini 或 Eta 时才拦截并分发到对应入口。
- **兼容配置**：三态目标写入字符串键；键不存在或值非法时读取旧 `POWER_KEY_TAKEOVER` 布尔协议，`true` 继续表示 Gemini，`false` 表示小布。新安装默认保持小布，旧用户不会因新增 Eta 被改写目标。
- **数字助理配置修复**：独立自动设置开关开启后，开机、解锁、切用户及启动失败恢复时，通过 `AssistantManager` 异步校正当前 Gemini/Eta 目标的 `android.app.role.ASSISTANT` 与 secure settings。小布模式和开关关闭时不写系统配置；校验缓存及异步回调同时核对用户与目标，旧目标任务不会继续覆盖新选择。
- **唤起逻辑优化**：Gemini 恢复原有 `VoiceInteractionManagerService`、`ACTION_ASSIST`、`ACTION_VOICE_COMMAND` 顺序；Eta 优先使用活动 `voiceinteraction` 会话，再在已经配置为默认助理时尝试同包 `ACTION_ASSIST` 桥。所有路径失败后立即执行小布原逻辑，不阻塞系统回调。
- **息屏后维持 Hey Google 可用**：Hook `PhoneWindowManager.screenTurnedOff()`，在默认显示息屏后短延迟检查 Google 的 `SoftwareTrustedHotwordDetectorSession`。只有已有 `mSoftwareCallback` 且当前未 running 时，才恢复 `startListeningFromMicLocked()`；亮屏或恢复成功后会取消未执行任务。
- **一圈即搜支持**：强制启用 `ContextualSearchManagerService`，将包名指向 Google App，并放行 `SystemUI` 与 ColorDirectService 的调用权限。作为一圈即搜的底层依赖始终执行，不可关闭。
- **无障碍保护**：复用已验证的 `SystemServer.startOtherServices(TimingsTraceAndSlog)` 生命周期点，在系统服务启动完成后接入事件驱动保护。后台工作复用 Android `BackgroundThread`，不开模块线程、不轮询；开关默认关闭，开启请求需同时通过 signature 权限、真实发送 UID、服务声明与 APK signer 钉扎校验。保护只维护 owner 用户中的 Eta 组件和总开关，保留其他服务；断连时通过仅允许 `system` UID 调用的健康 Provider 确认，并对 Eta 做带次数上限和冷却的定向重绑。

## 无障碍保护

「强制保持无障碍」默认关闭。开启后，注入 `system_server` 的保护后端会校验 Eta 的服务声明、调用 UID 与 APK 签名，并在无障碍服务列表、总开关、Eta 安装包或 owner 用户解锁状态变化时校正配置。它保留其他无障碍服务，不依赖 App 自启动，也不执行周期轮询。

如果服务仍在启用列表中但没有真实连接，保护后端只重启 Eta 自身，并最多逐步尝试三轮；持续失败后冷却一分钟。ColorOS 持续反删设置时，写回间隔会从 300 ms 退避到 30 秒，稳定一分钟后恢复。关闭开关只停止保护，不会替用户关闭当前服务。

GUI 工具执行前仍会确认真实服务连接。保护未开启、system 作用域未生效或重绑超时时，本次动作会明确失败，不会改用 Root 或 Shell 偷偷修改无障碍设置。

若 App 控制入口不可用，可用 ADB 停止保护后再从系统设置关闭服务：

```bash
adb shell settings put global eta_accessibility_protection_enabled 0
```

删除该设置会恢复默认关闭状态。开发阶段主动更换签名且确认 APK 来源可信时，还需清除旧 signer 钉扎值：

```bash
adb shell settings delete global eta_app_signer_sha256
```

## SystemUI

拦截底部手势条长按触发的 OPPO OCR 识屏，通过 binder 直接调用 `contextual_search` 服务触发一圈即搜。

## ColorDirectService

拦截 `com.coloros.directui.ui.CollectInfoActivity.M(Intent)`，读取 `startInfo.directExt` 中的 `fingerTrigger` 与 `touchInfo.fingerCount`。确认是双指识屏后，直接调用 `contextual_search` 服务触发一圈即搜，并关闭小布识屏页面；调用失败才回退小布原逻辑。

## 小布记忆

ColorOS 系统记忆存在 `com.oplus.aimemory` 的 `ai_memory` 数据库中。Eta 只在小布记忆默认进程保留模块生命周期，Hook 其 `DataShareProvider.call(String, String, Bundle)` 安装内部查询桥。Runtime 通过 Root 以固定 method 调用该 Provider，Hook 在拥有数据库权限的目标进程内以只读模式执行固定查询。非 Eta method 会原样进入小布记忆自身逻辑；内部 method 只接受 UID 0，不向模型暴露任意 URI、表名或 SQL。

查询协议只允许系统记忆、个人订单和已保存地点三种操作，请求与结果都有 UTF-8 字节上限。数据库层只查询预定义的表和字段，SQLite 标识符统一引用，以兼容 `shipments.order` 等与 SQL 保留字重名的字段。结果继续按敏感工具处理，不写入持久会话。

进程内查询桥不可用时，Runtime 才回退到 Root 快照路径：将主数据库及存在的 WAL、SHM 或 journal 边车文件限大复制到 Eta 缓存，用同一查询引擎只读打开，并在查询结束后立即删除。

## 超级小爱

超级小爱入口当前锁定包名 `com.miui.voiceassist`、版本 `7.13.32.0016`（versionCode `507013032`），并且只在主进程与 `:core` 进程保留模块生命周期。版本不匹配或无法读取版本时不安装业务 Hook。

适配器在 `OperationManager.setQueryInfo(String, String, JSONObject)` 原方法执行前暂存对话 ID、查询文本和可选的 `extra_image_file_id`，同时从 `z10.a.processed(Instruction)` 记录终态 `SpeechRecognizer.RecognizeResult`。`y00.r0.C0(Event): boolean` 收到 `Nlp.Request` 时，小爱已经通过 `APIUtils.buildEvent` 生成了新的 Event ID，因此适配器按查询文本关联输入上下文，不能要求它与 `setQueryInfo` 的对话 ID 相等。只有配置、前缀、图片引用和后台队列全部通过检查后才认领请求并返回发送成功；否则只调用一次原方法，让超级小爱继续原生处理。

终态 ASR 会在 `setQueryInfo` 之前建立短时轮次状态。当前轮次确定由 Eta 接管时，`kh0.s0` 的 `execute` / `executeActionsAsync` 原生 Agent Action 会被跳过，避免本地动作链在模型请求认领前抢先打开设置或执行其他动作。轮次状态有时效并在小爱会话清理时释放；文档输入不会进入这条接管链路。

图片 ID 只解析为当前小爱进程可读的单个本地图片文件，认领前校验存在性、大小和文件头，不扫描目录，也不记录文件路径。图片正文继续通过 Eta 现有的文件描述符传输链路进入 Agent Runtime。文档理解、多图片以及无法解析的图片不在当前接管范围内。

结果使用超级小爱的 `FlowTemplateToastCard` 在主线程流式更新，完成后通过其原生 TTS 入口朗读。卡片、取消、结果恢复和 Runtime handoff 都绑定已认领的对话 ID 与独立的 `xiaoai` source；不会全局屏蔽小爱的卡片、RN 数据或 TTS，也不共享小布适配器的状态。

该版本已通过小米真机验证。系统或 App 大版本更新后，仍需重新确认目标签名与完整链路。

## Google App

伪装设备为 Samsung S24 Ultra，使 Google 启用一圈即搜能力；同时拦截 `SystemProperties` 和 `PackageManager.hasSystemFeature()` 的关键查询，让 Google App 看到 `ro.opa.eligible_device=true`、`GOOGLE_BUILD` 与 `GOOGLE_EXPERIENCE`。这对应现成 Google App Magisk 模块和 OpenGApps 常用的 OPA eligibility 做法，但限定在 Google App 进程内，不改系统文件。机型伪装与资格补齐作为一圈即搜的底层依赖始终执行，不可关闭。

锁屏唤起 Gemini 浮窗后，Google 偶发只显示输入框、不启动录音。模块优先直接 Hook `FloatyActivity.onResume()`，找不到目标类时才回退到全局 `Activity.onResume()`；确认仍处于锁屏后，带去重地补发一次 `ACTION_VOICE_COMMAND`，避免用户还要手动点麦克风。亮屏（解锁态）唤起时同样存在该偶发问题，因此在同一 hook 点对称增加亮屏分支：确认仍处于解锁态后同样补发一次 `ACTION_VOICE_COMMAND`。去重粒度限定在同一个 `FloatyActivity` 实例，防止同一浮窗 `onResume` 短时间内重复补发，但关闭后立刻新开浮窗不会被上一次全局冷却挡住；两分支各自在延迟任务执行前复查对应开关与锁屏状态是否仍匹配。

## Google App 系统化

Google App 作为普通用户应用时，缺乏语音唤醒所需的系统权限，且容易 ColorOS 被自启管理杀掉。模块内置了 Magisk/KernelSU 模块，可将 Google App 安装为系统 priv-app。

安装流程由 `GoogleAppSystemizerInstaller` 负责：

- 检测 root 管理器类型（Magisk 或 KernelSU）
- KernelSU 需先安装 meta-overlayfs 模块，否则不支持模块安装
- 将内置的 Google App 系统化模块通过 root 执行安装
- 安装成功后提示用户重启生效

系统化安装是用户主动操作，不自动执行。安装入口位于设置页「高级」分组，点击后弹窗确认说明原因与操作方式，用户确认后才开始安装。

## 配置与实时生效

模块 UI 使用 Miuix，组件与图标依赖版本统一由 `gradle/libs.versions.toml` 声明。功能图标使用 Material Icons Rounded；设置与管理列表使用统一尺寸的单色图标，品牌图标保留原色，状态和选中操作按主题语义着色。设置、权限、Skills、MCP、备份与提供商列表通过卡片和行间距分组，空列表统一展示轻量提示。提供商类型、模型数量和内置来源使用可换行的辅助文字，当前选择由勾选图标表示。Skills 行按显示行数省略简介，「更多」菜单提供完整说明与删除入口，删除仍需确认。工具卡片明确标注可执行的跳转动作；纯介绍卡片点击查看完整说明，可跳转卡片通过信息按钮查看说明。思考状态统一使用星芒图标，执行工具时显示对应工具图标。设置页的「工具列表」入口直接打开现有工具能力页面，返回时回到设置页。Root 授权与重新检测操作位于状态行内；工作区文件列表区分目录导航和文件导出，空目录显示轻量提示。

Linux 环境页按当前环境、环境配置、文件与目录、扩展工具组织。顶部状态卡显示版本、运行方式、安装阶段与结果，主操作位于说明下方；发行版和运行方式通过带说明的紧凑浮动菜单切换，仅有普通模式时显示静态状态。电源键目标使用同一原生选择器。弹出菜单统一沿用 Miuix 默认的背景遮罩与普通选项高亮，点选或点击外部即关闭。工作区入口始终可用，共享文件夹与文件浏览在基础环境就绪后显示，扩展工具在基础工具就绪后显示。Root 授权失效时保留原 chroot 选择，允许查看授权说明或切换独立的 PRoot 环境。

配置链路如下：

根导航使用 Miuix `NavDisplay` 与可保存路由栈，横移返回开关实时控制各路由的 `swipeDismiss`；预测性返回开关通过 `ApplicationInfo` 的系统开关应用，并无转场重建主 Activity。首页会话列表保持在聊天舞台下层，通过双锚点横向拖动状态控制显露与收起；手势沿用 Compose 的方向仲裁和子组件优先级，不抢占消息滚动、横向代码块、附件栏或文本选择。设置页与标准二级页面共用自适应 Scaffold：手机显示可折叠大标题，宽屏改用小标题并将内容限制在居中的最大宽度内。界面缩放覆盖主 App 的 Compose Density，但宽屏判定保留缩放前 Density，避免缩放触发错误的手机/宽屏布局切换；系统助手浮层不应用界面缩放。

外观配置保存在现有 `eta_settings` DataStore。主题根统一解析跟随系统、浅色、深色、Monet 色彩风格、强调色与纯黑背景，并同步系统栏和 Markdown 的 Material 颜色桥接。顶栏使用 Miuix `LayerBackdrop` 捕获滚动内容，可选择高斯或渐进模糊；关闭模糊时，顶栏与聊天输入区都回退为主题纯色表面。页面滚动继续使用 Miuix 越界回弹和边界触感反馈，横屏安全区由 display cutout 与导航栏 Insets 共同约束。

- **Eta Runtime 配置**：默认思考、网页浏览、设备直达、敏感信息读取、敏感设备操作和终端/文件工具保存在 App 私有配置中，不依赖 LSPosed。Runtime 在请求开始和每次工具执行前读取当前值；升级时会兼容迁移已有 RemotePreferences 值。
- **Hook 配置**：`EtaApp` 在 `Application.onCreate` 注册 `XposedServiceHelper`，框架通过 `XposedProvider` 推送 binder 后拿到 `XposedService`。系统助手接管、Gemini 和一圈即搜等 Hook 开关通过 `XposedService.getRemotePreferences()` 写入 LSPosed 数据库；服务未连接时这些开关保持不可修改。
- **Hook 进程**：`ModuleMain.onModuleLoaded` 调用 `XposedInterface.getRemotePreferences()` 缓存只读 `SharedPreferences` 到 `Prefs`。各 Hook 拦截回调入口直接读 `Prefs.isEnabled(key)`，关闭则走原逻辑；因此正常使用时，配置切换后的下一次相关触发表现为实时生效。这里的实时生效来自 Hook 入口读取当前配置，不是 libxposed API 102 的 hot reload 特性。
- **延迟任务复查**：已排队的后台配置修复、`HotwordSelfHealHooks` retry 与 `GoogleAppHooks` 锁屏/亮屏语音命令会在执行前再次检查对应开关，避免用户在任务排队期间关闭开关后被旧任务绕过。

不可关闭的底层依赖（ContextualSearch 服务补齐、机型伪装、资格补齐）始终执行，不暴露开关。

## 个人数据直达

个人数据检索复用“敏感设备信息读取”开关；模型调用时可读取原始结果，但工具参数与结果不会持久化进会话记录。每个能力都固定到已验证的 Provider、投影字段和排序方式，只接受受长度限制的关键词与返回数量，不向模型暴露任意 URI、表名或 SQL。

Runtime 提示要求模型在用户目标会明显受益于本机上下文时主动调用已公开的只读工具。对于“了解我”、近期活动、习惯偏好和个性化建议等宽泛任务，模型应从多个相关来源按时间与代表性取样后再归纳；工具已公开即表示对应能力已由用户开启，不重复询问授权，也不因单个来源为空就直接停止。专用工具不存在、结果不足或数据源不可用时，如果 Root Shell、文件或终端工具已公开，模型会继续定位并只读检查相关应用私有文件与数据库，先识别格式和 schema，再执行有界查询，不修改源数据。

- 标准 Android Provider：相册图片、音频、共享文件、日历、通讯录、通话记录、短信和下载记录。
- ColorOS 数据源：通过固定 Provider 读取便签正文、待办、普通录音、通话录音与录音摘要；系统记忆优先由小布记忆进程内的只读 Hook 桥查询，再在桥不可用时回退到包含 SQLite 边车文件的 Root 临时快照。两条路径共用固定查询引擎，可检索记忆正文及其账单、日程、取件码、快递、地点和附件。
- 个人上下文：位置按需读取最近系统位置；应用活动与使用时长依赖用户授予的使用情况访问权；闹钟、计时器、输入法剪贴板历史和 Health Connect 聚合值通过固定数据库只读快照查询。健康工具只返回指定时间窗口的汇总，不返回原始测量序列。
- 通知历史：系统自身没有可用历史时不伪造旧记录。用户授予通知使用权后，Eta 从授权时点开始在独立本机数据库中保存标题、正文、来源包和时间，保留 7 天且最多 1000 条；查询结果仍按敏感工具规则从持久会话移除。
- 个人订单：优先检索系统记忆已经识别的外卖、购物、快递、票券和出行信息。第三方应用导出的进程通信 Provider 不等于订单查询合同，Eta 不依赖其易变私有订单库。
- QQ 与微信专用目录：仅扫描已验证的聊天图片缓存目录，按最近修改时间返回有界的文件元数据；不扫描视频、消息数据库、消息正文或任意其他应用私有目录。
- 设备上缺少相应应用或 Provider 合同变动时，工具返回结构化不可用错误；不会改用遍历其他应用私有目录的方式猜测数据。

## 文件视觉

`read_image` 属于通用文件视觉能力，随“终端/文件工具”开关公开，不依赖个人数据直达。它接受用户或其他工具已明确提供的任意本地绝对路径、file URI 或系统相册 URI；本机路径由 Root 读取。Root 将单张、大小受限的文件复制到 Eta 临时缓存，符号链接按实际目标读取；发送给模型前会仅为视觉请求缩放压缩，以避免多张原图撑大 OpenAI 兼容请求体，原始文件不会被修改。当前回合结束后立即删除临时文件。QQ/微信检索工具只负责提供可传入的图片路径。

运行时提示与工具描述共同要求模型每轮最多调用一次 `read_image`。需要查看多张图片时，模型必须先消费当前图片的视觉结果，再在下一轮读取下一张，避免同一请求携带多张工具图片导致部分 OpenAI 兼容服务长时间无响应。

## 内置浏览器

`browser_use` 是运行在 Eta 内的 Agent 浏览器，基于共享离屏 WebView，不是简单调用系统 `ACTION_VIEW`。它可以在不抢占前台的情况下加载 JavaScript 网页、提取保留标题/段落/列表/链接等结构的正文、查找并操作页面元素、提交表单、滚动和截图；用户想查看过程时，可在 App 中挂载同一个 WebView 直接接管。外部打开链接仍由独立的 `open_uri` 工具负责，两种能力不会混淆。

Eta 不对浏览器请求执行额外的 URL、DNS、IP、主机数量、请求方法、重定向或 Service Worker 拦截，页面直接交给系统 WebView 加载。浏览器允许本地内容、混合内容、第三方 Cookie、自动媒体播放和表单提交；系统 WebView 与 Android 平台自身的协议支持、TLS 校验和权限行为保持不变。网页工具可在设置中关闭。

## 终端与文件

在用户授权下执行 `user` 或 `root` shell 命令，读写文件、列目录、跑脚本、查日志、改配置。会话式 shell 保持 cwd 和环境变量，异步任务后台执行并分段读取输出。聊天中的终端工具卡片可展开查看并复制实际执行的完整命令；运行日志仍只记录长度等受控摘要，不记录命令正文。

需要长期驻留的后台服务（监听端口、Web 面板、定时任务等）通过 terminal 的 `daemon_start` 托管：进程经 setsid 脱离命令会话的进程组，输出写入工作区日志文件，任务记录落盘，不随 run、会话或页面结束回收。App 重启后按 PID 与归属标记校验认领存活进程，`daemon_list`、`daemon_logs`、`daemon_stop` 用于查看状态、读取日志和停止任务，用户也可在终端页的任务面板管理。守护任务数量与单次日志读取设有上限；设备重启后全部任务失效。

终端按用途分为三个环境：

- `android` 是原生 Android Shell，负责系统、应用、日志、Magisk 和设备文件操作。Root 会话会自动发现 Magisk、KernelSU 或 APatch 提供的 BusyBox，并以 standalone `ash` 补齐不在系统 PATH 中的 applet。
- Linux 用户态在 Alpine musl 与 Debian Trixie glibc 中二选一，选择持久化后由模型工具、块式终端和控制台共同使用；模型可见协议统一为 `environment=linux`。基础 rootfs 与基础工具分步安装，基础工具集不包含 Python 或 Node.js；Python profile 只安装 uv，再由 uv 全局安装最新正式版 Python，Node.js profile 安装当前可用的最新正式版。SSH 使用发行版最新稳定包，APK 分析在两个发行版中均可单独安装。Kimi Code profile 依赖 Node.js profile，通过 npm 安装最新正式版 `@moonshot-ai/kimi-code`（国内镜像优先），两个发行版均可使用；安装就绪后可在环境页一键启动 Kimi Web——以守护任务常驻 `kimi web`，从日志解析带 token 的本机地址并拉起系统浏览器。App 侧只读取安装器写入的完成标记，不再从非 Root 进程重复检查 rootfs 内的符号链接、二进制或执行权限。Eta 通过独立 mount namespace + Root chroot 运行所选环境；Linux 默认在映射到 Eta Android 工作目录的 `/workspace` 中执行，共享存储位于 `/sdcard`。它不是安全沙箱，也不会取代 Android 环境。
- 中国大陆网络下，Alpine APK 只尝试阿里云与官方 CDN；Debian 主仓库只尝试清华 TUNA 与 Debian 官方源，安全更新固定使用 Debian 官方源。成功的源会写回 rootfs 供后续 profile 和工具安装复用；APT 同时关闭易触发连接重置的 HTTP pipelining 并启用重试。GitHub 制品只尝试一个固定 HTTPS 下载入口，再回到官方地址，所有 rootfs/制品仍必须通过固定大小和 SHA-256 校验。

首页溢出菜单的「打开终端」是供用户手动操作的终端，默认是块式终端，BusyBox `script` 可用时可切换到 PTY 控制台模式：经 `script` 为 shell 分配伪终端（启动时 stty 设定网格尺寸、TERM 宣告为 xterm-256color），输出字节流由 VT 子集屏幕缓冲区维护成字符网格——支持 SGR 颜色与样式、光标定位、行/屏擦除、滚动区、备用屏幕（alt buffer）与宽字符占格，滚动历史有界保留；软键盘输入经隐藏输入框捕获直接写 stdin，Esc/Ctrl/Tab/方向键由键条补齐，Ctrl 组合键产生真实控制字节。两种模式各自支持多会话并存（上限各 6 个），状态栏的会话列表统一提供新建、切换、重启与关闭；切换环境与离开页面都不回收存活会话，会话由 ViewModel 持有到手动关闭或进程死亡。两种模式的会话启动时都显式加载 `/etc/profile` 与 `~/.profile`，安装器写入 PATH 的用户 CLI 可直接运行。

块式终端按命令、输出和退出码组织，只在 Android 与当前选中的 Linux 发行版之间切换。它使用独立的常驻 shell 会话，与 Agent 工具调用的会话互不共享；离开页面后运行中的命令继续在后台执行，回到页面可接着查看输出，后台会话的输出继续累积、切回即可看到。输出中的 ANSI SGR 序列渲染为颜色与字重/斜体/下划线，`\r` 按行覆盖处理（进度条只保留最终状态），其余控制序列丢弃；复制与日志场景使用剥离转义后的纯文本。命令运行期间输入框保持可用，内容写入会话 stdin——状态行协议把退出码标记与命令合并在同一逻辑行内由 shell 自己输出，交互式命令（REPL、`read` 等）读取 stdin 时不会吃掉标记。

共享文件夹把用户选定的 Android 目录呈现到 Linux 环境的 `/workspace/mounts/<名称>` 下，在 Linux 工具环境页管理。挂载不做 Android 全局的持久 bind：每个 Linux 会话在自己的 mount namespace 建立时按当前配置逐个 bind 源目录，会话结束随 namespace 回收，因此配置改动对下一条命令或新会话生效，设备重启后也没有残留挂载需要清理。源路径与挂载名经归一化和禁区校验（系统关键树、工作区与 rootfs 自身不可作为源）；`/sdcard` 等模拟存储在 Linux 中不支持修改权限位。

rootfs 内文件归 root 所有，Linux 工具环境页还提供只读的文件浏览：列目录与读文件都通过一次性 Root Shell 在宿主路径上执行，路径只做词法归一化、不解析符号链接（链接目标在 chroot 内才有 Linux 语义）；文件预览上限 256 KB，含 NUL 字节的文件按二进制处理不提供预览。

聊天输入栏可以引用任意本地绝对路径下的普通文件或文件夹，发送后以附件名称和原始请求分开展示。Eta 只把经过 Root 解析的规范绝对路径写入模型上下文，不上传、不复制或缓存原文件；模型再按任务调用文件或终端工具读取。系统文件选择器会解析内部存储文档，以及能转换为本地媒体库路径的“最近”文件；云盘和其他只有 `content://` URI 的来源不会降级为上传。

## 长期记忆

跨对话长期记忆保存在 App 私有目录中的单一 `MEMORY.md`，不额外调用提取模型或运行后台整理任务。当前对话的主模型根据需要调用 `memory_get` 与 `memory_write`，负责去重、修正冲突和删除过期信息。

- **按需注入**：每轮只自动提供预算内的 `# 核心记忆`、标题索引和文件 revision；详细章节由模型按任务检索，不把整个文件反复塞进上下文
- **动态预算**：核心注入量根据当前模型上下文窗口计算；窗口未知时按 128K 处理，并始终为历史、工具、图片和回复预留空间
- **原子更新**：文件上限为 1 MiB UTF-8 字节，模型使用 revision 进行局部更新，冲突时必须重新读取；完整文件通过 `AtomicFile` 覆盖，失败保留旧内容
- **用户控制**：设置页可查看用量、编辑完整 Markdown、清空或关闭记忆；关闭不会删除文件，但 Runtime 会立即停止注入并拒绝新的记忆工具调用

## 会话级 Thinking Effort

聊天会话保存独立的 `ReasoningEffort`，输入栏按当前 Provider、端点和模型能力显示 `Thinking · Off / Default / Low / Medium / High / XHigh / Max` 的实际子集。模型不支持推理时不显示入口；强制推理且没有可调档位的模型只显示不可点击的 `Thinking · Default`。模型切换或远端能力刷新后，已保存但不再合法的档位会向下裁剪到最近的有效档位，没有可比档位时回到 `Default`。

能力解析依次采用远端精确元数据、内置模型目录、Provider 与模型家族规则，最后安全降级。`Default` 保留供应商或高级自定义请求体的默认行为；显式档位在请求体合并完成后应用，因此会话选择是最终覆盖。Room、Runtime Bundle、RemotePreferences JSON 和外部归档同时保留旧 `thinkingEnabled` 布尔投影，旧 `true/false` 分别解释为 `Default/Off`；强制推理模型收到 `Off` 时直接报告配置错误。

## 聊天流式渲染

模型的 SSE 文本增量先在 App 状态层按 50 ms 合并，减少高频列表状态写入；思考、工具调用和块边界事件仍会立即刷新，事件顺序不变。聊天渲染使用增量 Markdown AST，但不会把尚未显示的大段网络 backlog 一次性交给布局：解析器每次最多追加 12 个 Unicode 字素，批与批之间让出一拍供重组排版，供给节奏与显现速度解耦；消息高度始终由显现进度驱动，解析领先不会提前撑高回答。输入器使用 state-based `TextFieldState` 在组件内持有编辑缓冲区，逐字编辑不会回写聊天页面状态，也不经过旧版 `CoreTextField` 管线。

Markdown 空行只参与块结构解析，不按源码数量累加可见高度；稳定与流式渲染共用同一套块级排版，按正文、标题、列表、引用、代码和表格之间的关系分配留白，首块不额外下移。块间距使用随系统字体缩放的排版单位，普通段落之间保留约一个正文字符的间隔，标题前进一步扩大、标题后适度收紧，在大字体下仍保持清晰层级。Runtime 同时要求最终答复使用合法且克制的 GFM：普通交流优先短段落，仅在有助于分组、步骤或比较时使用标题、列表和表格，表格各行保持独立，避免把整句粗体当作标题。

逐字效果由单个回答级帧时钟驱动。段落、标题、代码块和表格单元格先完成一次真实排版，再由 `DrawModifierNode` 按字素边界裁剪 `TextLayoutResult`；帧间推进只使绘制失效，不重建 Markdown AST、`AnnotatedString` 或文本布局。显现速度随积压自适应（48–240 字素/秒），积压时允许单帧补多个字素，避免输出稳定滞后于模型。行内语法闭合（加粗、行内代码、链接折叠）导致渲染文本变短时，显现进度保持单调前进，不回退重打已显示的文字。前缀路径按字素增量累计，只有显现跨入新行时才触发一次测量并增加消息高度，因此隐藏文本不会提前把当前回答顶出视口。Emoji ZWJ、肤色修饰、组合音标、国旗和代理对均作为完整字素显示，不会从 UTF-16 中间断开。

底部跟随只在用户真实拖动时解除，并仅在消息实际增高、底部哨兵离开视口时滚动；纯网络状态更新不会反复触发滚动。网络结束后，底部跟随继续覆盖解析、文字显现和稳定排版切换，待末尾操作行完成布局且列表滚到底部后收尾；用户拖动查看历史时立即停止跟随，已完成消息的手动展开不会触发跟底。解析追平且显现队列排空后，回答切换到稳定 Markdown 状态，恢复完整链接解析与文本选择。token 用量事件只更新用量字段，不触碰消息的流式标记，避免流式/静态视图在轮次边界反复切换。

## 功耗与开销

追求极简，绝不给系统增加额外负担：

- 不轮询、不保活 Google 进程、不持续写日志
- 无障碍保护默认关闭；开启后只响应设置、包、用户生命周期与 Runtime 明确上报，设置争抢采用退避，断连重绑有次数和冷却上限
- 热路径只保留当前机型实际验证有效的 `OplusSpeechHandler` hook
- 默认助理配置检查带 15 秒冷却，息屏后的 Hey Google 恢复路径不主动查写默认助理配置
- 高频成功路径使用 `DEBUG`；Debug 构建可诊断，Release 由 R8 确定性裁剪
- 电源键拦截路径不执行休眠、轮询或阻塞等待；本次触发只做快速启动尝试，失败即回退系统原逻辑
- 默认助理修复异步执行并按用户串行，完成时重新核验 role、目标与开关状态
- 息屏后的 Hey Google 恢复只响应系统息屏事件；最多串行尝试 3 次，失败才投递下一次，亮屏/成功/结束都会移除未执行 callback
- Google App 的锁屏/亮屏语音输入优先 Hook 固定 FloatyActivity，不常驻拦截 Google App 所有页面；语音补偿只按同一 FloatyActivity 实例去重，避免重复补发又不影响快速关闭后再次启动

## 预期行为

电源键目标为小布时，ColorOS 长按电源键保持厂商原始行为且不修改当前默认助理。目标为 Gemini 时，长按恢复 Google 原有系统助手与 Activity 兜底链路。目标为 Eta 且 Eta 已是默认数字助理时，长按会打开 edge-to-edge 全屏助理浮窗并自动聚焦键盘输入框；入口会在浮窗与 IME 出现前准备一张屏幕截图，只有用户选择后才作为下一条消息的图片上下文发送。用户提交文本后，工具执行、流式结果和归档仍由主进程中的 Agent Runtime 负责，当前流程不执行 ASR 或 TTS。

Eta 尚未成为默认助理且自动设置关闭时，按既定策略直接回到小布，不创建平行 Activity 会话。自动设置开启时，失败触发只在后台修复当前选择，当前长按仍立即回退；后续触发使用修复后的主路径。HyperOS 后续只需把厂商按键事件接到同一目标分发边界，不需要修改文本会话和 Runtime。

配置界面按消费边界保存开关：Agent 与本地工具写入 App 私有配置，Hook 能力写入 LSPosed 侧 RemotePreferences。Hook 回调和延迟任务执行前都会读取对应开关，所以后续触发按当前配置执行。
