# 第三方声明

## 终端原生组件

Eta 的免 Root Linux 后端以独立进程运行 [PRoot](https://github.com/termux/proot)，并静态链接 [talloc](https://talloc.samba.org/) 与 [libandroid-shmem](https://github.com/termux/libandroid-shmem)。PRoot 源码采用 GPL-2.0-or-later；talloc 采用 LGPL-3.0-or-later；libandroid-shmem 采用 BSD-3-Clause。附带的组合 PRoot 可执行程序按 GPL-3.0-or-later 分发，各组件版权与许可证保留。

APK 的 `assets/native-sources` 附带校验过的原始源码，以及从实际构建入口自动生成的脚本、PTY 源码和补丁包；`assets/licenses` 附带许可证全文。构建脚本与说明位于 [终端原生组件](TERMINAL_NATIVE.md)。这些独立第三方程序保持其开源许可权利，不受 Eta 主项目非商业许可证的附加限制。

## Miuix

Eta 的应用界面使用 [Miuix](https://github.com/compose-miuix-ui/miuix)，其采用 [Apache License 2.0](https://github.com/compose-miuix-ui/miuix/blob/main/LICENSE)。

## Material Icons

Eta 的功能图标使用 AndroidX Compose Material Icons 的 Rounded 系列，通过 `material-icons-extended` 依赖提供。图标及其 AndroidX 实现采用 [Apache License 2.0](https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt)，来源见 [Material Design Icons](https://github.com/google/material-design-icons)。

## Android Hidden API Bypass

Eta 使用 [Android Hidden API Bypass](https://github.com/LSPosed/AndroidHiddenApiBypass) 应用用户选择的预测性返回设置。该库采用 [Apache License 2.0](https://github.com/LSPosed/AndroidHiddenApiBypass/blob/main/LICENSE)。

## Lobe Icons

模型与提供商品牌图标来自
[Lobe Icons](https://github.com/lobehub/lobe-icons) 的
`@lobehub/icons-static-avatar` 1.13.0。原始 1280×1280 WebP 素材在不改变颜色和比例的前提下，无损缩放为 128×128 后随 Eta 本地打包。

| Eta 资源 | Lobe Icons Avatar |
| --- | --- |
| `provider_logo_openai.webp` | `openai.webp` |
| `provider_logo_anthropic.webp` | `anthropic.webp` |
| `provider_logo_bailian.webp` | `bailian.webp` |
| `provider_logo_deepseek.webp` | `deepseek.webp` |
| `provider_logo_kimi.webp` | `kimi.webp` |
| `provider_logo_mimo.webp` | `xiaomimimo.webp` |
| `provider_logo_minimax.webp` | `minimax.webp` |
| `provider_logo_stepfun.webp` | `stepfun.webp` |
| `provider_logo_siliconflow.webp` | `siliconcloud.webp` |
| `provider_logo_openrouter.webp` | `openrouter.webp` |
| `model_logo_claude.webp` | `claude.webp` |
| `model_logo_qwen.webp` | `qwen.webp` |
| `model_logo_zai.webp` | `zai.webp` |
| `model_logo_chatglm.webp` | `chatglm.webp` |
| `model_logo_gemini.webp` | `gemini.webp` |
| `model_logo_gemma.webp` | `gemma.webp` |
| `model_logo_grok.webp` | `grok.webp` |
| `model_logo_meta.webp` | `meta.webp` |
| `model_logo_mistral.webp` | `mistral.webp` |
| `model_logo_doubao.webp` | `doubao.webp` |
| `model_logo_hunyuan.webp` | `hunyuan.webp` |
| `model_logo_yi.webp` | `yi.webp` |

Lobe Icons 使用 MIT License：

```text
MIT License

Copyright (c) 2023 LobeHub

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

## Kimi Code 图标

首页“启动 Kimi Web”入口的 `ic_kimi_code.xml` 转换自 Moonshot AI
[Kimi Code](https://github.com/MoonshotAI/kimi-code/blob/d1ca65e1de189617e9edbc54010e62d472a1de3d/apps/vscode/resources/kimi-icon.svg)
的单色侧栏图标。转换仅适配 Android VectorDrawable 格式，保留原始几何与比例。

Kimi Code 使用 MIT License：

```text
MIT License

Copyright (c) 2026 Moonshot AI

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

OpenAI、Anthropic、Claude、阿里云百炼、Qwen、DeepSeek、Kimi、Xiaomi MiMo、MiniMax、StepFun、Z.ai、ChatGLM、Gemini、Gemma、Grok、Meta、Mistral、豆包、混元、Yi、硅基流动和 OpenRouter 的名称、图标及其他品牌标识归各自权利人所有。Eta 展示这些图标仅用于准确标识用户正在配置的模型服务，不表示这些厂商对 Eta 的赞助、认可或合作关系。OpenAI 图标的使用还应遵循其[品牌规范](https://openai.com/brand/)。

## 可选语言运行时

Eta 不把 uv 或 Node.js 打包进 APK。用户主动安装对应 profile 时，Eta 下载当前固定的最新正式版制品；Debian 使用 Node.js 官方 Linux glibc 发行包，Alpine 使用其稳定仓库中的 `nodejs-current`，uv 按所选发行版下载官方 glibc 或 musl 制品。

| 工具 | 来源 | 许可证 |
| --- | --- | --- |
| uv / uvx | [astral-sh/uv](https://github.com/astral-sh/uv) | Apache License 2.0 / MIT |
| Node.js / npm / npx | [nodejs/node](https://github.com/nodejs/node) | MIT 及发行包内第三方许可证 |

## 可选 APK 分析工具

Eta 不把下列工具打包进 APK。用户在 Linux 工具环境页面主动安装“APK 分析”时，Eta 从固定官方 Release 下载并校验制品；工具保存在用户当前选择的 Alpine 或 Debian 环境中，适用各自许可证：

| 工具 | 来源 | 许可证 |
| --- | --- | --- |
| JADX | [skylot/jadx](https://github.com/skylot/jadx) | Apache License 2.0 |
| Apktool | [iBotPeaches/Apktool](https://github.com/iBotPeaches/Apktool) | Apache License 2.0 |
| smali / baksmali | [google/smali](https://github.com/google/smali) | BSD 3-Clause License |

JADX 的发行包许可证会随所需 CLI 文件一并保留；Apktool、smali 与 baksmali 的许可证和第三方声明保留在各自 JAR 制品中。Eta 仅提供经过校验的安装、命令入口和能力边界，不对这些工具重新授权。

GitHub 的实际制品域名不可达或较慢时，安装器可能通过 `gh-proxy.com` 请求同一个公开 Release URL；Node.js 制品可能先通过 `cdn.npmmirror.com` 请求。下载入口会获知用户的网络地址及所请求的公开制品；Eta 不向其发送账号、Cookie、API Key 或其他 Eta 数据，并在落盘前继续校验内置的官方制品大小与 SHA-256。不希望使用下载入口的用户可以不安装对应可选档案。
