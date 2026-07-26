# Eta

<p align="center"><strong>AI Agent for Android</strong></p>

<p align="center">一个第三方 Android 系统级 AI Agent：结构化设备工具直达系统，GUI Agent 跨应用操作界面，Root Shell 与 Linux 终端则让它像 Coding Agent 一样把整台手机变成可编程执行环境——能力可以自由组合、持续生长，而不是被预设功能清单框死。</p>

> 底层基于 [libxposed API 102](https://github.com/libxposed/api) 的 Xposed 模块，面向 ColorOS 16。Hook 小布进程拦截对话请求，接入同一套 Agent Runtime，支持 BYOK 自定义模型；**App 本体是主工作台**。此外保留了 system_server、SystemUI、ColorDirectService、Google App 等进程中的早期 Hook 功能（电源键唤醒 Gemini、手势条/双指识屏触发一圈即搜），当前不是重点，后续仍会维护。

## 界面预览

|                     GUI Agent 演示                     |                   小布助手 BYOK：电源键启动                   |
| :----------------------------------------------------: | :-----------------------------------------------------------: |
| ![GUI Agent 演示](docs/Screenshots/demo_gui_agent.gif) | ![小布助手 BYOK：电源键启动](docs/Screenshots/demo_tools.gif) |

|              App 本体聊天首页              |                        小布 BYOK：系统内存分析                        |                    命令执行                    |
| :-----------------------------------------: | :-------------------------------------------------------------------: | :--------------------------------------------: |
| ![聊天首页](docs/Screenshots/chat_home.jpg) | ![小布 BYOK：系统内存分析](docs/Screenshots/chat_breeno_analysis.jpg) | ![命令执行](docs/Screenshots/chat_command.jpg) |

|                  设置                  |                工具能力                |                 Skills                 |
| :------------------------------------: | :-------------------------------------: | :------------------------------------: |
| ![设置](docs/Screenshots/settings.jpg) | ![工具能力](docs/Screenshots/tools.jpg) | ![Skills](docs/Screenshots/skills.jpg) |

## 核心能力

Agent 不会问一句答一句就结束。它在一个 loop 里运转：模型发指令，系统执行，结果写回上下文，模型再决定下一步。如此往复，直到做完。

作为 Android AI Agent，Eta 同时具备 GUI 操作和终端执行能力，这和主流 Coding Agent 的逻辑一致——屏幕是表层，shell 才是完整计算环境。

**GUI 操作**

- **屏幕与控件**：截图、读取带快照标识的无障碍节点、按节点或坐标点击，以及带位移验证的四向滚动
- **应用与系统**：启动 App、把链接显式交给外部应用、模拟按键、下拉通知栏、搜索应用
- **文本与剪贴板**：输入文字、设置剪贴板、粘贴、等待特定文本出现
- **运行可视化**：前台操作时显示浮层与手势反馈，让你知道它正在点哪里、怎么滑

### 设备直达：让 AI 调用系统，而不是学习怎么点手机

> 当普通手机 Agent 还在截图、找按钮、猜坐标时，Eta 已经把任务交给 Android 系统直接完成。

“明早 7 点叫我起床”“暂停音乐”“把媒体音量调到 30%”“开启 Wi‑Fi”“看看哪个应用最占空间”——Eta 会把这些自然语言转换成参数明确的设备工具调用。模型不需要先打开设置、等待动画、识别开关，再祈祷界面没有改版。

这不是在 System Prompt 里写几句“我可以控制手机”。Eta 为模型提供了一套真正可执行的结构化设备工具：`set_alarm`、`set_timer`、`set_device_state`、`set_volume`、`device_status`、`send_message`……每个工具都有独立的参数 Schema、权限分组、执行器和结构化结果。模型负责决定做什么，Runtime 负责可靠地在这台手机上完成。

这也不是一张做到这里就封顶的功能清单。结构化设备工具是 Eta 会持续扩展的核心能力层：后续更多系统状态、设备控制和应用动作会不断以独立 Schema 与专用执行器接入。能直达的能力就不再堆 GUI 脚本，让模型可用的手机能力随着版本持续增长。

- **时间与媒体**：直接创建闹钟和计时器，控制播放、暂停、切歌，并按媒体、铃声、通知和闹钟通道精确调整音量
- **设备状态**：读取电池、充电、内存、存储、系统版本、运行时间和当前网络，直接开启或关闭 Wi‑Fi、蓝牙
- **应用洞察**：找出最占内存的进程和最占存储的应用，不必打开系统管家逐页查找
- **深度系统能力**：读取通知和短信验证码，查询已保存的 Wi‑Fi 凭据与 Android Settings，获取有界系统日志
- **设备管理**：修改非安全关键 Settings，停止、冻结或恢复指定应用；核心系统包和安全关键设置始终受保护
- **真实发送微信消息**：精确搜索联系人并填写或发送消息。它不是打开微信后把任务丢给用户，而是真的完成输入、点击发送并验证结果

有稳定系统接口的任务优先直达；只有 Android 或目标应用没有提供合适接口时，才退回无障碍 GUI。GUI 仍然是 Eta 跨应用操作的通用能力，但不再是每件事都必须经过的笨重中间层。

| 普通 GUI Agent | Eta 设备直达 |
| --- | --- |
| 截图、识别按钮、模拟点击 | 调用职责明确的结构化工具 |
| 依赖坐标、文案和当前页面布局 | 优先依赖 Android 系统接口 |
| 页面改版、弹窗或动画都可能打断流程 | 多数操作不受设置页面变化影响 |
| 只能观察界面是否“看起来成功” | 执行器返回可供模型继续判断的结果 |
| 所有能力都挤在同一套 GUI 操作里 | 不同能力拥有独立 Schema 和执行边界 |

Eta 不要求用户背固定口令，也不会拿一份关键词表审核一句话“够不够明确”。相应能力开启后，模型可以根据任务直接调用。安全也不是靠每一步都反问用户，而是落在真正执行动作的地方：

- 设备能力分为“设备直达、敏感信息读取、敏感设备操作”三组开关；第一组默认开启，后两组默认关闭，Runtime 每次执行前都会重新读取
- 工具参数必须通过 Schema 和执行器校验；核心包、安全关键设置和越界参数不会因为模型坚持要求就被放行
- 短信验证码、Wi‑Fi 密码、通知正文、日志和消息参数只在当前回合提供给模型，原始参数与结果不会写入持久会话
- 微信联系人出现同名时立即停止；发送按钮最多点击一次，结果未知时绝不盲目重试，避免发错人或重复发送

**网页浏览**

`browser_use` 是运行在 Eta 内的 Agent 浏览器，不是简单调用系统 `ACTION_VIEW`。它可以在不抢占前台的情况下加载 JavaScript 网页、提取保留标题/段落/列表/链接等结构的正文、查找并操作页面元素、滚动和截图；需要人工验证或用户想查看过程时，可在 App 中挂载同一个 WebView 直接接管。外部打开链接仍由独立的 `open_uri` 工具负责，两种能力不会混淆。

浏览器只接受 HTTPS，SSL 错误不会被忽略，并对主页面与子资源执行 URL、DNS、主机数量和 Service Worker 限制；这些检查属于纵深防护，WebView 本身并不是可绑定实际连接 IP 的独立网络沙箱，因此不能把它当作访问内网或承载高敏感凭据的安全边界。网页内容会作为不可信数据交给模型，正文按偏移分页并以 UTF-8 字节严格限长；Cookie 不会作为工具结果暴露给模型。网页工具可在设置中关闭。

**终端与文件**

在用户授权下执行 `user` 或 `root` shell 命令，读写文件、列目录、跑脚本、查日志、改配置。会话式 shell 保持 cwd 和环境变量，异步任务后台执行并分段读取输出。默认关闭，需手动开启。

终端按用途分为两个环境：

- `android` 是原生 Android Shell，负责系统、应用、日志、Magisk 和设备文件操作。Root 会话会自动发现 Magisk、KernelSU 或 APatch 提供的 BusyBox，并以 standalone `ash` 补齐不在系统 PATH 中的 applet。
- `linux` 是可选安装的 Alpine 工具环境，负责 Python、Git、Bash、jq、zip、OpenSSL、SQLite 等通用任务。Eta 下载固定版本的官方 minirootfs 并校验 SHA-256，在 App 私有目录中解压，通过独立 mount namespace + Root chroot 运行；它不是安全沙箱，也不会取代 Android 环境。

**Skills**

- **AI 安装**：Eta 可以浏览公开 GitHub 仓库、列出候选 Skill，并直接安装模型选定的候选；也支持 `$skill-installer`
- **本地 ZIP**：在 Skills 页面选择只包含一个 Skill 的 ZIP 包导入；压缩包会先在 App 私有目录中完成路径、大小、结构和 `SKILL.md` 校验，再写入正式目录
- **冲突保护**：同名用户 Skill 默认不覆盖；GitHub 安装发生单个可替换冲突时，可按同一仓库、提交、路径和 ID 精确重试，内置 Skill 永远不会被导入包覆盖
- **按需读取**：模型先看到已启用 Skill 的索引，需要时再读取正文和引用资源。新安装的 Skill 从下一轮对话开始可用

安装 Skill 只会保存文件并建立索引，不会自动执行包内脚本，也不会替用户开启默认关闭的终端/文件工具。目前 AI 安装仅支持公开 GitHub 仓库，不接受私有仓库 Token。

**通用**

- **结果归档**：外部入口触发的运行结果会归档到 App 会话，即使 App 进程被杀也会尝试恢复

## 使用场景

- **设备直达操作** — “明早 7 点设个闹钟”“暂停音乐”“把媒体音量调到 30%”，优先走结构化系统接口
- **精确发送消息** — “微信告诉张三我十分钟后到”，在精确联系人匹配后真实发送，不把模糊匹配当作授权
- **跨 App GUI 操作** — “帮我把微信未读消息都点了”，没有对应直达工具时才由 Agent 看屏幕、找按钮、执行
- **跨 App 比价** — 截图分析淘宝商品，自动打开京东搜索同款并返回结果
- **网页研究** — 在后台阅读 JavaScript 渲染的文档或资讯页面，保留同一浏览会话；遇到验证码时由用户直接接管
- **终端任务** — "清一下后台，查 LSPosed 日志看 Hook 有没有异常，再看看 Magisk 模块生效了没"——Agent 可以执行 shell 命令、读系统日志、查模块状态、改配置，把意图转化为终端操作
- **小布入口触发复杂任务** — 按电源键唤醒小布，用自然语言让 Agent 执行多步流程

## 边界说明

第三方 Xposed 模块永远做不到系统内置助手那种动画丝滑和入口一致性。但原厂做得烂的时候，Hack 就是用户唯一的选择。

- **这不是聊天机器人换皮。** 普通 AI App 只能输出文字。本项目通过 Xposed Hook、无障碍服务、系统浮层和 ==root== 权限，让 Agent 同时掌握 GUI 和终端——前者操作屏幕，后者进入本机命令层。两个入口叠加，意味着 Agent 拥有接近完整手机环境的操作能力。
- **目标系统为 ColorOS 16。** Hook 点强依赖 OPPO / Google App 当前实现，系统或 App 大版本更新后可能需要重新适配。

## 与豆包手机助手的区别

豆包手机助手证明了手机 AI 的方向：从聊天框走向系统级操作。但它是超级 App，有平台资源，也有平台约束——跨 App 接管会撞上微信登录异常、淘宝人机验证、银行 App 风险提示。厂商要维护商业关系、支付安全和监管合规，天然被生态绑住手脚。

本项目走第三方开发者路线：不代表手机厂商，不需要维护预装合作。用户愿意解锁、==root==、启用 Xposed 和无障碍，就应该能把自己的手机入口接给自己选择的 Agent。风险边界由用户决定，工具必须透明可见，敏感操作必须能随时停止和接管。

更重要的是，我们把终端执行能力放进了 Agent Runtime。普通手机 AI 只会点屏幕，但 Agent 一旦能在用户授权下执行 shell 命令、读写文件、跑脚本、改配置，它就具备了和主流 Coding Agent 同类的"把意图转化为操作"的能力。GUI 是手机表层，终端才是完整计算环境。

## 系统入口接管

项目早期做了大量 ColorOS 系统入口的 Hook 工作，保留至今，当前不是重点：

- **小布接管**：接管小布对话入口，解析图片上下文，交给同一套 Agent Runtime 处理。支持 BYOK，默认只在 `/agent` 前缀下触发
- **Gemini 解锁**：电源键长按唤起 Gemini、锁屏自动语音输入、息屏维持 Hey Google 检测
- **一圈即搜**：手势条长按和双指识屏触发 Android `contextual_search`，不改系统文件

## 模型与 BYOK

Agent 的能力取决于你用什么模型。

- **OpenAI-compatible** 与 **Anthropic** 双协议，支持 SSE 流式传输、流式工具调用、图片输入、推理内容
- **内置提供商**：OpenAI、Anthropic、阿里百炼、DeepSeek、Kimi、MiMo、MiniMax、StepFun、硅基流动、OpenRouter
- **厂商品牌图标**：已知提供商在列表中显示本地品牌原色图标，未知或自定义接口保留通用图标；素材来源与许可见 [第三方声明](docs/THIRD_PARTY_NOTICES.md)
- **自定义提供商**：自定义 Base URL、API Key、请求头、body JSON
- **模型管理**：内置官方目录、远程拉取列表、自定义模型、启停管理；新增项仅在确认保存后入库，远程同步只更新远程来源，不删除手工模型
- **会话历史**：新会话先作为本地草稿存在，发送第一条消息后才进入历史列表
- **单次运行统计**：一次 Agent 任务结束后，最终回答下方会显示输入 Token、缓存输入 Token、输出 Token 和耗时。任务中多次调用模型（包括工具调用前后）会合并计算；接口没有返回某项 usage 时，该项显示为 `--`。

BYOK（Bring Your Own Key）意味着 Agent 能力跟随你选择的模型，而不是被内置供应限制。

## 安装

<details>
<summary><b>展开安装步骤</b></summary>

1. 在支持 libxposed API 102 的 LSPosed 环境中安装 APK
2. 作用域包含 `system`、`SystemUI`、Google App、小布识屏 和小布助手
3. 重启手机
4. 打开 App，配置模型提供商、API Key 和当前模型
5. 按需授予悬浮窗、无障碍、应用列表读取、位置、后台运行等权限；位置仅在 Agent 调用时间与位置工具时读取，小布等后台入口需要“始终允许”；终端/文件工具支持用户明确选择 `user` 或 `root` 身份。用户主动执行 GUI Agent 操作时，如果 Eta 无障碍服务尚未连接，Runtime 会通过 Root 在保留其他服务的前提下启用 Eta，等待服务真实连接后再执行工具；开机或升级广播仍只审计状态
6. 按需开启小布接管、敏感信息读取、敏感设备操作和终端/文件工具；需要 Python、Git 等通用命令时，再从设置中主动安装 Linux 工具环境

</details>

## 开发与验证

项目使用 Java 25、Android SDK 37。完成环境配置后，可以在项目根目录运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

Linux 或 macOS 使用 `./gradlew`。Debug APK 会生成在 `app/build/outputs/apk/debug/`，构建产物不需要提交到 Git。

## 当前限制

- 第三方模块无法获得原厂系统组件的全部私有权限，交互 UI、动画衔接和系统级一致性会弱于厂商内置方案

## 项目结构

核心代码在 `app/src/main/kotlin/`：

```
ModuleMain.kt              Xposed 模块入口
Application 层             App 初始化

hook/system/               system_server Hook
hook/google/               Google App 进程 Hook
hook/colordirect/          ColorDirectService Hook
hook/breeno/               小布入口接管

agent/runtime/             Agent Runtime、跨进程协议、结果归档
agent/model/               模型提供商抽象、SSE 解析
agent/tool/                本机工具执行器
agent/browser/             共享离屏浏览器、网页读取与安全边界
agent/device/              root / 无障碍 / input 设备控制
agent/terminal/            Android/Alpine 会话式 shell、环境安装与文件工具
agent/overlay/             运行浮层与手势反馈
agent/skill/               Skills 解析、安装、安全读取与索引
agent/accessibility/       无障碍服务、节点快照与授权状态检查

data/db/                   Room：会话、模型提供商、运行归档
data/repository/           仓库层
data/provider/             内置模型提供商与官方模型目录

ui/app/                    App 根状态、导航
ui/screens/                各功能页面
ui.pages/providers/        模型提供商管理
ui/components/             通用组件
systemizer/                Google App 系统化安装器
config/Prefs.kt            RemotePreferences 配置
```

Agent Loop、工具批次、steering 与 transcript 语义见 [docs/AGENT_RUNTIME.md](docs/AGENT_RUNTIME.md)。Gemini、一圈即搜和 RemotePreferences 链路见 [docs/TECHNICAL.md](docs/TECHNICAL.md)。

## 参考与致谢

Eta 的开发过程中参考或关注过以下开源项目：

- [Pi Coding Agent](https://github.com/earendil-works/pi)：Eta Agent Runtime 的核心参考，包括 Agent Loop、工具调用、steering 与 transcript 状态管理
- [OpenOmniBot](https://github.com/omnimind-ai/OpenOmniBot)：Android 端 AI Agent 方向的参考项目
- [Operit](https://github.com/AAswordman/Operit)：Android Shell 与完整 Linux 工具环境分层的产品形态参考

Eta 结合自身的 Xposed 系统入口、Android Runtime、IPC 与模型协议边界进行了独立实现。

## 许可证

Eta 的源代码公开，欢迎用于个人学习、研究、修改和非商业用途。完整条款请参阅 [PolyForm Noncommercial License 1.0.0](LICENSE)。

未经作者书面授权，不得销售本项目、其源码、APK 或修改版本，也不得基于本项目提供付费分发、收费代装或其他商业服务。如需商业授权，请通过 GitHub 联系 [蛮吉（Mangi-11）](https://github.com/Mangi-11)。

第三方依赖、图标和品牌素材适用各自的许可证，不由 Eta 的许可证重新授权，详情见[第三方声明](docs/THIRD_PARTY_NOTICES.md)。

为确保项目能够统一授予商业许可，外部代码贡献需要在贡献者许可协议（CLA）流程建立后才能合并；在此之前欢迎通过 Issue 提交建议和问题。

<sub>Community: <a href="https://linux.do">LINUX DO</a></sub>
