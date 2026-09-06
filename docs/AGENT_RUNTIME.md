# Agent Runtime

Eta 的 Agent Runtime 负责把一次用户输入组织为模型回合、工具执行和可持久化的增量 transcript。它运行在模块自身进程；Hook 进程只负责识别入口、发送请求和接收结果。

## 代码边界

- `AgentModelClient`：稳定门面、配置与跨进程会话 DTO。
- `AgentLoop`：单次 run 的状态机，不依赖 Android Service、Room 或 Compose。
- `AgentPromptBuilder`：系统约束、Skill 索引、历史和当前用户输入。
- `AgentConversationCodec`：Provider JSON 与稳定会话 DTO 的转换。
- `AgentToolCatalog` 及分组目录：模型可见的工具 schema，不执行工具。
- `AgentTraceFormatter`：只生成可展示、可记录的脱敏摘要。
- `AgentProviderClient`：OpenAI-compatible、Anthropic 等协议边界。
- `AgentRunController`：取消、暂停和 steering 队列。
- `AgentRuntimeSession`：每个 run 自持 reply channel，并保证唯一最终结果。
- `AgentRuntimeRunExecutor`：从 Skill/工具初始化到模型执行、资源清理和终态提交的统一异常边界。
- `AgentRuntimeService`：Android 生命周期、入口 IPC 和浮层宿主；不再内联 Agent 执行循环。
- `ShellProcessSupervisor`：Android/Alpine/Debian Shell 进程的接纳、独立进程组、取消和回收；终端协议不承担进程所有权细节。

## Loop 语义

一个 turn 是“一次 assistant 响应 + 该响应提交的完整工具批次”。循环遵守以下顺序：

```text
pending steering
→ provider response
→ assistant history
→ tool batch（按模型顺序串行执行）
→ contiguous tool results
→ optional image observations
→ next turn / final result
```

关键不变量：

- steering 默认逐条排队，只在当前 turn 完整结束后注入；它不会取消当前 HTTP 请求或关闭工具资源。
- 同一 assistant 消息中的全部 tool result 必须连续写入，再追加不受 Provider 原生 tool-result image 支持的图片观察。
- `finish_reason=length` 或 `max_tokens` 且包含工具调用时，不执行任何可能被截断的参数；为每个调用写入结构化错误结果，让模型重新规划。
- 只有明确的 `tool_calls` / `tool_use` 终止原因才允许执行工具；`stop`、内容过滤或未知终止原因中夹带的调用一律作为协议矛盾拒绝。
- 工具参数在执行前按本轮实际下发的 JSON Schema 校验，支持本地 `$ref`、组合 Schema、条件 Schema 与常用对象、数组、字符串、数值约束；这只检查调用合同，不承担权限确认或额外安全策略。
- transcript 只返回本次 run 新增的 assistant、tool 和运行中 steering 消息，不重复旧 history 或本轮初始用户消息。
- GUI/终端工具保持串行。Android 前台状态和会话式 Shell 都不具备可安全并行的通用语义。
- 单次 run 不设置固定回合数或总时限，由模型自然结束、用户取消或不可恢复错误终止。
- cancel 是终止信号；pause 是检查点阻塞；steering 是下一回合输入。三者不能互相模拟。
- cancel 的主线程路径只做原子终态与资源关闭：共享浏览器按 runId 校验归属；终端立即封闭新的进程接纳，并在后台按独立进程组终止同步命令、会话和 async job，再完成线程与流回收。Android 上 `setsid` 或 PID/PGID ownership 握手不可用时会 fail closed；非 Android 测试环境才允许父子树快照回退。终止前还会核验随机 ownership token，避免陈旧 PGID 复用后误杀无关进程。
- 最终 steering 检查会原子关闭接收入口；Loop 返回后不会再把无人消费的补充指令误报为已接收。补充指令也不会解除 pause。
- 新 run 替换旧 run、用户取消和正常完成都通过 `AgentRuntimeSession` 的 `RUNNING → COMMITTING → TERMINAL` 状态机竞争唯一终态；提交胜者独占 outbox、归档和最终发布，客户端等待最终结果或 Binder 断连，不按等待时长取消任务。
- 入口请求只能缩小工具能力，不能自行授权。Runtime 在开始 run 时裁剪配置，在每次浏览器、终端和设备工具执行前重新读取用户开关，并在 thinking 关闭时移除自定义请求体中的 reasoning/thinking 覆盖字段。
- 设备工具分为直达工具、敏感读取工具和敏感操作工具，当前均默认开启。Runtime 在每次执行前重新读取用户开关；开关允许且参数符合工具 Schema 后即可执行，不再匹配用户原话，也不维护关键包、系统应用或 Settings key 黑名单。
- 微信发送不提供专用工具、参数协议或额外策略层，完全使用通用 GUI 工具观察和操作微信界面。
- 通知、短信验证码、Wi‑Fi 凭据和日志属于瞬时敏感工具数据。当前模型回合可以使用原始值，但持久 transcript 会同时替换对应工具参数和结果，避免进入会话数据库或后续 IPC。

## Provider 协议

Provider 默认基础提示词将 Eta 定义为运行在 Android 设备上的 AI 助手，可以回答问题、与用户交流，也可以通过工具了解设备情况并执行操作；回答使用用户的语言，简洁、直接、自然。默认正文以 `BuiltinProviders.DEFAULT_SYSTEM_PROMPT` 为准；Provider 提示词为空时使用该默认值，已有非空配置保持原值。

Runtime 独立于 Provider 自定义提示词注入 Eta 身份，以“当前配置的模型”标注 `ModelConfig.model` 的实际值，随本次运行配置更新，不使用模型显示名或历史消息推断当前模型，也不据此推断部署版本、知识截止日期或能力。通用交流规则要求日常问答直接回答、仅在缺少关键参数时澄清、按用户需求调整详略，并如实交代工具操作结果；个性化分析区分事实与推测，不根据零散记录断言性格、动机或心理状态。工具、记忆与 Skills 等系统规则仍按运行时条件追加。

OpenAI-compatible Provider 可在配置页选择 `Chat Completions` 或 `Responses API`。新安装和重置后的内置 OpenAI 默认使用 Responses；数据库中已有 Provider 不会被默认值覆盖。自定义 Provider 和其他内置 Provider 默认仍使用 Chat Completions。

Chat Completions 在协议边界把当前上下文中的全部 `system` 内容按原顺序合并为首条唯一系统消息，兼容要求系统消息只能位于开头的模型 Chat Template。Responses 则把完整的 `system`/`developer` 上下文投影到 `instructions`，并将持久历史重建为带 `type: "message"` 的 input Items。

Responses 请求固定使用 `stream:true`、`store:false`，不发送 `previous_response_id`。Runtime 在同一次 run 的工具回合之间精确回放 Provider 返回的完整 output Items；因此 encrypted reasoning、服务端工具状态等 opaque 数据只存在于内存，不进入 IPC transcript、Room、日志或运行归档。持久会话只保留规范化回答、可见推理内容和 Eta 工具记录，后续 run 由这些稳定数据重新构建上下文。

兼容接口若在 `response.completed` 中省略 `output` 或返回空数组，Runtime 只使用同一 SSE 流中已经收到的标准文本、推理摘要和函数调用增量完成当前轮次；非空终态始终是权威结果，且本地恢复结果不会冒充 Provider 的 opaque output Items。

推理界面展示的是 Provider 返回的 reasoning summary；它不是原始思维链，也不会由 Eta 伪造。兼容 Provider 若按 Responses 协议返回 `reasoning_text`，Runtime 会把它作为可见推理内容展示。Responses 只对精确命中官方目录且未被远端显式标记为 `reasoning:false` 的模型补齐推理能力，不会因 Endpoint 类型而假定所有模型支持推理。

Chat Completions、Responses 与 Anthropic Messages 在 Provider 边界统一投影为带 `round + block index` 身份的正文、思考和工具块。Responses 额外使用 `item_id/output_index/content_index` 区分同一轮中的多个 output item；Chat Completions 在 delta 类型切换时创建新块；Anthropic 直接保留 `content_block.index`。正文、思考或工具类型一旦切换，上一段可见块立即定稿，后续同类型内容也不会跨过工具卡片回填到旧块。终态只在 Provider 的权威内容与已流式内容不一致时携带一次替换，不用整轮聚合正文覆盖最后一个块。

服务端网页搜索是 Responses Provider 的独立开关，默认关闭。开启后请求只增加 `web_search` 托管工具；搜索开始和结束作为独立运行事件投影到 UI，不进入 Eta 本地工具执行器。最终回答中的 `url_citation` 会去重并转换为可点击 Markdown 引用；偏移无效时降级为回答末尾的来源列表。当前不接入 file search、code interpreter、Provider 托管 MCP 或其他托管工具。

### 模型等待与重试

模型流使用独立的 HTTP 配置：连接等待 15 秒、写入等待 30 秒、读取等待 5 分钟；读取限制针对等待新数据，不是整个任务的总时限。MCP、模型列表与下载继续沿用各自配置。模型 HTTP 客户端关闭底层连接自动重试，模型回合的有限重试统一由 Loop 编排。

连接中断、超时、提前 EOF、暂时限流和部分服务端错误最多重试 3 次，依次等待 2、4、8 秒；每个成功的模型回合重新获得独立预算。认证、额度、计费、证书、协议格式等非暂时性失败不自动重试。重试等待可取消，并遵守暂停检查点；排队的 steering 留到当前回合及工具批次完成后处理。

失败尝试不提交 assistant history，不执行其中的本地工具调用；前面完成的工具结果、当前回合的工具 schema 和截图在重试期间保持不变。重试使用新的展示轮次，失败的半截输出留在运行轨迹并标注重试，后续输出不会拼接到旧块；最终推理摘要不包含被替换的失败尝试。重试事件通过既有 IPC、checkpoint 和归档编码保存，恢复回放不会重新执行工具。若 Provider 已报告托管工具开始执行，本次失败不自动重试，避免重复触发服务端操作。

## MCP 工具

Eta 直接作为 MCP 客户端连接远程 Streamable HTTP 服务器，不把协议能力绑定到某个模型 Provider。当前优先使用 `2026-07-28` 无状态协议，并兼容需要 `initialize` 与 session 的 `2025-11-25` 服务；只接入 `tools/list` 和 `tools/call`，暂不支持 Resources、Prompts、Tasks、stdio、OAuth、交互式补充输入或 Provider 托管 MCP。

工具默认关闭，服务器也可整体停用。添加服务器时先发现并缓存工具目录，用户再逐项启用；未标记只读的工具需要额外确认。现代服务的目录按 `ttlMs` 到期并在下次 run 前刷新，legacy 目录由用户手动刷新。每次 run 开始时一并冻结启用目录与 Bearer Token，并生成带服务器命名空间的模型工具名，因此后续设置变化不会改变正在执行的 schema 或账户。Eta 不因 `$ref`、组合关键字、条件关键字等复杂 Schema 禁用工具，而是原样投影给模型并在调用前按同一份 Schema 校验；现代 Streamable HTTP 的 `x-mcp-header` 参数会同步映射为请求头。

MCP 地址由用户直接配置，HTTP、HTTPS、局域网与本机地址使用同一条连接链路，并沿用共享 OkHttp 客户端的默认重定向和超时行为；HTTP 会明文传输 Token、工具参数和结果。Bearer Token 通过 Android Keystore 加密后保存在本机。MCP 原始参数与结果只在当前回合使用，持久 transcript、运行 checkpoint 和归档只保留脱敏记录；文本、结构化结果、图片、分页次数和单次 run 工具数仍有独立预算，不支持或超出预算的结果会携带明确标记。取消 run 会立即封闭新调用并关闭在途 HTTP 请求，legacy session 的释放只做异步 best-effort，不阻塞取消线程。

## 长期记忆

长期记忆保存在 App 私有目录的单一 `MEMORY.md` 中。文件使用 UTF-8，安全上限为 1 MiB；仓库在进程内锁中应用变更，并通过 `AtomicFile` 覆盖完整文件。模型写入携带当前内容的 SHA-256 revision，revision 不一致时返回 `MEMORY_CONFLICT`，不会覆盖并发更新。

每次 run 只把 `# 核心记忆` 的预算内内容、一级/二级标题索引和 revision 放入系统背景。核心预算为 `min(32000, max(4000, contextWindow / 16))` 个字符；模型窗口未知时按 128K 计算。没有 `# 核心记忆` 标题时不自动注入正文。其余内容由 `memory_get` 按行分页或按文本检索，单次最多返回 32000 字符。

`memory_write` 支持行区间替换、独立章节追加与清空；单次模型生成内容最多 3500 字符，设置页的用户手动编辑不受此单次工具限制。关闭记忆不会删除文件，后续 run 不再注入或暴露工具；已开始的 run 在每次执行记忆工具前也会重新检查开关。

记忆内容只作为可编辑背景，不具有指令优先级。记忆工具原始参数与结果可供当前 Agent Loop 使用，但对应工具调用在持久 transcript 中整体脱敏；运行事件只保存操作类型、行数、字节数和错误码，不保存正文或查询词。

## 本地工具能力合同

`AgentToolRequirements` 为每个本地工具声明 `NONE / PARTIAL / REQUIRED` Root 要求与无障碍、普通系统授权、ROM 条件；工具未登记元数据时不能进入模型目录。`AgentToolCapabilities` 每轮捕获设备条件，同一份投影后的 Schema 同时用于 Provider 声明与参数校验。元数据属于 Eta 内部，不扩展 Provider 协议。UI 聚合卡关联真实工具 ID，“全部能力”只改变展示。

没有 Root 时，专属工具彻底移除；混合终端仅公开 `identity=user`，设备默认路径与模型提示同步调整。执行器再次核查当前 Root 与参数，旧调用返回 `ROOT_REQUIRED`。普通前台 Intent 不要求无障碍；截图、节点、手势、输入和条件等待需要真实服务连接，已开启系统保护时保留有限修复链路。当前通知来自已连接的通知监听服务，断连返回明确错误，不以历史记录替代。用户选择保存在原有本地 Agent 配置与 RemotePreferences 协调链路中，能力变化不改写保存的开关。

Root 探测在 IO 线程执行：存在 `su` 时首次自动请求一次，最多等待 30 秒，仅 UID 0 视为可用；拒绝和超时不会反复弹出请求，用户可在“系统增强”手动重试。LSPosed 连接独立判断，不代替 Root 授权。

## 终端环境

`terminal` 的 `environment` 明确区分设备控制与通用 Linux 工具，默认值为 `android`：

- `android` 继续使用系统 Shell。`user` 身份不升级权限；`root` 身份在 `su` 内探测 Magisk、KernelSU、APatch 或系统 BusyBox，并优先进入 standalone `ash`，因此 BusyBox applet 不要求预先加入 PATH。旧 `run_command`、文件读写和目录操作保持这一环境，避免改变既有 Android 路径与命令语义。
- `linux` 解析用户选择的发行版和后端。chroot 保持原有 rootfs、独立 mount namespace、`/data/local/tmp/eta` 工作区与特权挂载。新建 PRoot 环境和普通工作区使用 App UID 独占的 `filesDir/terminal-user` 目录，避开旧 Root 目录的属主限制；已有普通环境继续使用原位置，路径统一由 `TerminalPrivateStorage` 解析，`/workspace` 映射该私有工作区。仅映射有权访问的共享目录，拒绝“所有文件访问”后仍可导入导出。Linux 内的模拟 root 不意味着 Android Root，两个后端都不构成隔离安全沙箱。
- 已建立会话和任务保存后端与实际 rootfs/工作区，不因 Root 变化自动切换。持久任务记录的后端与宿主工作区字段为可选，兼容旧记录。获得 Root 不迁移 PRoot，失去 Root 不删除 chroot 或改变文件属主。
- 普通 Android Shell、文件读写与图片读取使用 App UID；Root 用户保留原有特权路径。无法直接访问的选择器文件经有界复制导入工作区；目录选择不能冒充可实时访问的路径。

用户在 Alpine 与 Debian 中选择一个当前 Linux 发行版，模型与终端统一通过 `environment=linux` 使用该选择。基础环境安装与基础工具安装是两个独立步骤：安装器先下载固定版本、大小和 SHA-256 的 rootfs，在临时目录解压，运行检查成功后才写入基础完成标记；PRoot 的流式解包校验归档路径和链接，支持取消与失败清理；用户随后安装只含通用命令的基础工具集。Python profile 只安装 uv，随后由 uv 把最新正式版 Python 安装到 `/opt/eta/python` 并把全局命令链接到 `/usr/local/bin`。Node.js profile 在 Debian 安装上游最新正式版 ARM64/x64 制品，在 Alpine 安装稳定分支提供的 `nodejs-current`；SSH 使用所选发行版的最新稳定包。App 侧只读取安装器完成标记，不再重复检查 rootfs 内的符号链接、二进制或执行权限。中国大陆网络下，Alpine 使用阿里云镜像，Debian 主仓库使用清华 TUNA、安全更新使用 Debian 官方源，各自只保留官方主仓库作为失败出口；APT 还启用重试并关闭 HTTP pipelining。

APK 分析在 Alpine 与 Debian 中都作为可选档案显示。JADX、Apktool、smali 与 baksmali 使用当前最新正式版的固定官方 Release URL、大小和 SHA-256，下载完整校验后才进入 App 可写的 cache staging；不能把下载或解包暂存目录放进由 Root 创建的 Linux 管理目录。GitHub 制品先尝试一个 HTTPS 下载入口，再回到官方地址，但仍只接受与官方清单 SHA-256 完全一致的字节。JADX 只解出 CLI 脚本、运行库与许可证，成功验证全部命令后再原子切换当前版本。档案在 Alpine 安装 `openjdk25-jdk`，在 Debian 安装 `openjdk-25-jdk-headless`，但不安装全局 Gradle、Android SDK 或 NDK。由于 Google 的 Linux SDK、AAPT2 与 NDK 主机工具只提供 x86_64 构建，手机 ARM64 chroot 无法原生组成受官方支持的完整 Android 编译链；`apktool build` 因而稳定拒绝，解码、代码查看和独立 Smali 汇编/反汇编不受影响。

## 后台执行生命周期

`AgentExecutionService` 使用 `specialUse` 前台类型，为当前 Agent 运行、普通终端和 PRoot 后台进程持有任务引用。用户退出页面只断开 UI；最后一个任务结束时服务释放，通知中的停止操作回收它实际持有的任务。普通后台任务保持宿主 tracer 与输出读取，不能像 Root daemon 那样脱离 App 生命周期。Root daemon 保持原有独立生命周期，普通任务清理不会批量停止 Root daemon。Root 用户的原有 Runtime 绑定链路在新增前台服务启动受限时仍可继续，不因新增服务阻断厂商助手入口。

Kimi 使用 `kimi web --no-open`，按发行版及后端复用活跃实例。启动失败或取消只清理本次新建的进程；复用实例保留。服务使用 `START_NOT_STICKY`，系统强停或重启后不自动重放命令。通知授权被拒绝不会直接阻止合法前台启动，但系统后台启动限制与厂商进程回收策略仍然生效。

## 上下文与续接

App 在发起请求前已经把当前用户消息写入会话 history，因此 Runtime 返回的 transcript 必须保持“增量”语义。已完成 run 的补充请求由 `AgentContinuationBuilder` 使用以下顺序重建上下文：

```text
旧 history
→ 原始用户消息
→ 完整增量 transcript
→ 新补充消息
```

图片只在需要它的当前模型回合中传递；持久 transcript 会删除 data URL，并写入稳定的省略说明，避免截图 base64 同时膨胀 Binder、Room 和后续上下文。外部入口归档可以另外保存有界的小预览用于还原用户消息 UI，但预览不会重新进入模型历史。启动请求在发送前按实际 `Parcel` 大小校验，超过 768 KiB 时会明确拒绝并提示减少图片数量或分辨率。运行归档 transcript 上限为 100 万字符；会话上下文检查点和直接 IPC transcript 上限为 9.6 万字符；outbox 批量 drain 使用更紧的单项预算，确保最坏 8 条待交付结果仍处于 Binder 事务预算内。任何容量压缩都会在保留的 history 前插入明确的 Eta system notice，不会把删头后的 transcript 冒充成完整上下文。会话元数据、逐条展示消息和有界上下文检查点分别存储；会话列表查询不读取上下文正文，启动时也不会因单个长期会话阻塞全部会话恢复。

浮层在已完成结果后发起的 continuation 会在 handoff 中只携带本次新增的 prompt supplement，不累计复制旧补充。App 回到前台时 drain outbox，把该用户消息和增量 transcript 一起写回 history。

上下文自动压缩和跨 run、跨 Provider 的 opaque reasoning 状态尚未实现；Responses output Items 只在当前 run 内回放，不能作为持久会话状态。

## Skills 安装边界

Skill 安装工具始终向模型提供，不再根据顶层用户输入的固定关键词决定是否暴露或执行。网页、仓库 README 和已安装 Skill 仍只是数据，不能改变工具参数或执行边界。

- AI 安装只访问公开 GitHub HTTPS 地址；curated 默认来自 `openai/skills` 的 `skills/.curated`。安装路径必须来自当前 run 对同一仓库与 ref 的检查结果，最多 20 个。
- 本地 ZIP 由 Skills 页面通过系统文件选择器读取，不申请共享存储权限，也不把归档复制到公开目录；每个 ZIP 只允许包含一个 Skill。
- GitHub 下载与本地 ZIP 共用受限解包和校验流程：拒绝路径穿越、绝对路径、重复条目、嵌套 Skill、非法 frontmatter，以及超过条目数、单文件、归档或总解压预算的输入。
- 安装先在 App 私有临时目录完整验证，再提交到正式 Skills 目录。文件系统与 Room 变更由持久事务日志协调，进程异常退出后会在下次变更前恢复；批量安装任一步失败都会回滚。同名用户 Skill 默认保持不变；GitHub 单冲突替换绑定仓库、提交、路径和 Skill ID，可在同一 run 精确重试；内置 Skill 永远不能被导入包覆盖。
- 安装只保存文件、登记索引并默认启用，不执行 `scripts/`，也不改变终端/文件工具开关。本轮 Skill 索引在模型调用前已经冻结，因此新 Skill 从下一轮对话开始可用。

已安装 Skill 的附属文本资源通过独立的有界读取工具访问，读取时再次做相对路径、canonical root、UTF-8 与大小检查；脚本和二进制 asset 不会借此被执行或当作无限文本送入上下文。

待确认结果和外部入口归档会把 transcript 一并写入 Room。数据库 6 → 7 使用显式非破坏迁移为旧记录补 transcript，7 → 8 为会话增加已应用 run 标记，10 → 11 将会话上下文迁入独立的有界检查点并清理旧的大字段。恢复幂等性不再靠比较 history 尾部猜测；保存任务严格按调用顺序串行，只有包含对应标记的快照落盘后才 ACK outbox。旧 6.x 结果仍可用已有 assistant 内容合成兼容 history。

主界面的 `AgentAppState` 由 Activity 级 ViewModel 持有，配置变更只重建 Compose UI，不替换正在等待 Runtime 的客户端。用户消息先提交到 Room，再启动可能产生设备副作用的 run。Runtime 为 App 会话维护追加式在途 checkpoint：文本增量有界合并，结构化边界先落盘再发布；块结束通常只保存边界和字符数，仅在终态修正流式内容时保存替换正文。工具调用的原始参数增量和原始结果不进入该日志，UI 已展示的参数摘要、脱敏终端命令与结果摘要会随工具状态保存。终态先封存 checkpoint 再提交 outbox，只有会话成功落盘并 ACK 结果后才同时删除 outbox 与 checkpoint；outbox 的时间或容量裁剪也会成对删除对应的终态 checkpoint。

App 恢复时以 `checkpoint + outbox + active session` 统一对账，不再用进程是否变化推断 run 状态。有 outbox 时先恢复工具轨迹，再用终态结果定稿；Runtime 仍 active 时，新 UI 会先整体恢复内存中的安全事件，再订阅实时事件与最终结果；只有既无终态又不 active 的 run 才标记为中断。恢复不会自动重放任何工具，也不会把半截助手回复加入后续模型 history。

重新订阅沿用已有的 attach 响应作为历史回放结束边界：Runtime 在同一会话锁内依次发送安全历史、成功响应，再加入实时订阅，实时事件与终态不能越过此边界。App 客户端在响应前缓冲历史并一次性交付 UI，UI 在一个状态快照中重建该 run 的消息投影；只有边界后的新增内容进入实时更新。旧服务若先发送终态，客户端先交付已缓冲历史再交付结果。恢复前清理可重建的旧投影，保留原始用户请求和没有对应回放事件的补充内容，避免重复追加或丢失用户输入。任务终态独立于文字显现状态，最终结果会收口尚未结束的文字标记；缺少结果的工具记录显示未知状态，不伪造成功。

## 验证

核心回归测试位于：

- `AgentModelClientLoopTest`
- `AgentConversationCodecTest`
- `AgentRunControllerTest`
- `AgentContinuationBuilderTest`
- `AgentRuntimePolicyTest`
- `AgentRuntimeSessionTest`
- `AgentRunCheckpointStoreTest`
- `AgentRunMessageProjectorTest`
- `AgentToolCatalogTest`
- `McpProtocolValidationTest`
- `McpRunContextTest`
- `AgentMemoryStoreTest`
- `AgentMemoryContextBuilderTest`
- `EtaDatabaseMigrationTest`

最终验证仍运行项目统一命令：

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```
