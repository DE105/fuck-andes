# HyperOS 系统入口适配

Eta 将 HyperOS 系统快捷动作和桌面导航手势分别放在 `hook/hyperos/`，通过 `ModuleMain` 和 `SystemServerHooks` 分发。小爱对话请求仍由 `hook/xiaoai/` 处理，两者互不替代。本文描述源码适配，不代表已完成 HyperOS 真机验证。

## 电源键

- 进程：`system_server`，对应模块作用域 `system`。
- 类：`com.miui.server.input.util.ShortCutActionsUtils`。
- 拦截：返回 `boolean` 的 `triggerFunction(String, String, Bundle, boolean)` 与末尾增加 `String` 的五参数重载；安装时检查签名以及 `mContext` 的类型。
- 触发条件：第一个参数为 `launch_voice_assistant`，第二个参数为 `long_press_power_key` 或 `imperceptible_press_power_key`。后者是部分系统版本的短时长电源键助手路径。
- 执行：读取现有电源键目标。系统默认助手直接放行；Gemini / Eta 通过 `AssistantManager.showAssistantSession` 请求当前用户的数字助理会话。只有调用返回成功才返回 `true` 消费快捷动作，失败调用原方法。

该入口在系统识别完按键动作后接管，不重写按键计时或触摸事件，也不修改系统电源键设置。关机菜单、SOS、双击相机、智能家居和非电源键助手动作不符合匹配条件，保持系统逻辑。当前用户需已启用电源键助手快捷方式，并将目标设为默认数字助理；沿用 Eta 的“自动设置默认助理”开关与已有恢复机制。HyperOS 路径没有普通 Activity 兜底，避免把会话失败误判为助理已唤起。

系统私有分发签名已有源码使用证据，助手功能名也与小爱读取的系统设置一致；尚无当前目标 ROM 的 framework / services APK，不能据此断言所有 HyperOS 版本的调用链、字段和按住松手时序相同。未知类、字段和签名通过安装报告诊断，不按模糊方法名拦截。

## 底部手势横条

进程为 `com.miui.home` 或 `com.mi.android.globallauncher` 的主进程，需勾选实际安装的桌面作用域。`ModuleMain` 仅保留这些主进程，且只在首包就绪时安装，不注入桌面子进程的业务 Hook。

以下目标按顺序尝试，成功注册一个后停止尝试后续入口：

| 顺序 | 系统类与方法 | 上下文来源 |
| --- | --- | --- |
| 1 | `com.miui.home.recents.gesture.NavStubGestureEventManager.handleLongPressEvent()` | `com.miui.home.launcher.Application.getInstance()` |
| 2 | `com.miui.home.recents.cts.CircleToSearchHelper.invokeOmni(Context, int, int)` | 第一个参数 |
| 3 | `com.miui.home.recents.cts.NavBarEventHelper.onLongPress(MotionEvent)` | 实例 `mContext` |
| 4 | `com.miui.home.recents.NavStubView.onTouchEvent(MotionEvent)` | View 自身 Context |

前三种入口复用桌面自己的长按识别，仅替换搜索分发。关闭“手势条长按触发一圈即搜”、上下文缺失、Google 入口缺失或服务调用失败时，执行原方法。`invokeOmni` 仅接受 `void` / `boolean` 返回类型，成功返回对应的空值 / `true`。导航搜索统一使用 entry point `1`，其他厂商原有调用保持原 entry point。

旧版触摸检测只在前三种入口均未安装，且 `NavStubView` 不存在 `mCheckLongPress` 时启用。已存在原生检测但回调未知的桌面不会叠加第二套检测。

- 每个 View 独立管理手势和延迟任务；使用系统长按超时与触摸滑动阈值。
- 滑动超过阈值、多指、抬手、取消、视图移除均取消任务；延迟执行前重新读取开关。
- 触发成功后，下一个事件转换为 `ACTION_CANCEL` 结束原生手势；搜索失败保留原事件。
- 仅在旧版手势等待长按期间抑制 `startRecentsAnimationPre()`。移动超过阈值后放行，未全局禁用最近任务预启动。
- 延迟任务使用视图弱引用，不持有 Hook Chain，不增加线程或轮询。

## HyperOS 4 导航识屏服务入口

Android 17 / HyperOS 4 的横条长按可直接交给超级小爱识屏，绕过上述桌面回调。Eta 在 `com.miui.voiceassist` 包的进程中独立安装 `HyperOsScreenSearchHooks`，拦截 `com.xiaomi.voiceassistant.VoiceService.onStartCommand(Intent, int, int)`；该入口不依赖小爱对话接管的版本门禁。小爱对话业务仍只在原有主进程 / `:core` 范围及版本检查通过后启用，其他子进程仅安装结构明确的识屏服务 Hook。

只在现有手势开关开启，且请求满足以下全部条件时替换搜索分发：

- Action 为 `android.intent.action.ASSIST`。
- `voice_assist_function_key` 为 `start_screen_recognition`。
- `triggerType` 为 `NavLongPress`。
- `voice_assist_start_from_key` 为 `long_press_fullscreen_gesture_line`、`long_press_home_key` 或 `two_gesture_long_press`。这些来源只有与前两项同时匹配才视为导航识屏，不能单凭来源截获普通助理请求。

空 Intent、字段缺失 / 类型不符、其他功能及来源均放行。搜索请求发送失败时执行原 `onStartCommand`；发送成功后返回 `START_NOT_STICKY`，仅在 `stopSelfResult(startId)` 确认本次仍为最新启动时移除前台状态，避免停止已经排队的后续启动。清理失败记录节流诊断，不再重复唤起原识屏界面。不会把原 Intent、图片或其他 extras 转发给 Google，搜索参数仍由 Eta 固定生成。

启用条件：

1. 在 LSPosed 的 Eta 作用域中勾选“超级小爱”（`com.miui.voiceassist`），保留 `system`、Google App 和实际使用的桌面作用域，重启使新 Hook 生效。
2. 在 HyperOS 系统导航方式中，将“长按手势提示线”设为“超级小爱识屏”。
3. 开启 Eta 的“手势条长按触发一圈即搜”，确保 Google App 已安装并初始化。

系统设置未指向小爱识屏时，不保证会产生上述服务请求。Android 17 的 `startContextualSearch(int, ContextualSearchConfig)` 已由共享调用器支持，配置参数传 `null`，旧系统的一参数签名继续保留。接口兼容不等于入口已接通；诊断需同时查看 `HyperOsScreenSearch` 与 `ContextualSearch` 日志。

## 搜索服务与权限

桌面与小爱识屏服务入口都复用 Eta 的 `CircleToSearchInvoker`，调用 `contextual_search` Binder，跳过原生识屏处理，也不在桌面或小爱 Hook 中执行 Agent Runtime。

`ContextualSearchHooks` 负责：

1. 将 `SystemServer.deviceHasConfigString(Context, int)` 对 `config_defaultContextualSearchPackageName` 的判断修正为可启动，其他资源判断走原逻辑。
2. 保留 `startOtherServices` 尾段的服务补启动；服务已存在时不重复启动。
3. 将 `ContextualSearchManagerService.getContextualSearchPackageName()` 指向 Google App。
4. 仅对 `enforcePermission("startContextualSearch")` 扩展可信 HyperOS UID：调用 UID 包列表中须含上述桌面包或 `com.miui.voiceassist`，对应包须为系统应用或系统应用更新，且手势开关开启。既有 SystemUI 和 ColorOS 识屏调用方保持原权限规则。

Binder 的 oneway 调用不提供界面是否显示的确认；代码返回成功只代表请求已发出，不代表 Google 已显示搜索界面。权限按 UID 裁决，shared UID 下的其他进程共享同一权限边界。

当前不实现旧式 `voiceinteraction.showSessionFromSession` 加资源覆盖的搜索旁路，也不取消整个语音交互服务的身份校验。ROM 若没有 `ContextualSearchManagerService`，仅靠本模块无法补出系统中不存在的实现，保留原触发逻辑。

## 已知边界与真机验证

- `MiuiSingleKeyRule.supportLongPress()` / `onLongPress(long)` 与 `mKeyCode == KEYCODE_HOME` 是另一条 Home 键长按路径，不是电源键或横条事件。Eta 不拦截该底层按键路径；服务收到明确的 `NavLongPress` 识屏请求时，才处理其中的兼容来源。
- 类存在不等于回调一定被当前桌面使用。原生入口存在但被区域资格开关挡住、原生长按期间的最近任务动画、国际版桌面的包与进程布局，都需要对应版本验证。
- Google App 资格补齐继续使用既有 Google Hook；不新增桌面设备伪装。
- 双指识屏和 ColorOS 的锁屏语音、热词补偿没有新增 HyperOS 专用实现。
- 配置 key、默认值与存储协议不变；新增作用域后需在模块管理器勾选并重启，已有进程不会自动获得新 Hook。

真机验证应覆盖：系统默认 / Gemini / Eta 三种目标、目标未设为默认或未安装、关机与 SOS 原动作、横条静止长按与滑动、多指和抬手取消、横竖屏切换、开关关闭及重启恢复。记录 ROM / Android / 桌面 / 小爱版本，以及 `HyperOsPower`、`HyperOsLauncher`、`HyperOsScreenSearch`、`ContextualSearch` 安装报告，以区分目标缺失、入口未被调用和 Binder / Google 侧失败。
