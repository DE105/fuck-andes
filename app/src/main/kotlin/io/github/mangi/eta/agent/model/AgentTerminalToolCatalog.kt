package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentTerminalToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "terminal",
                    description = "Manage terminal sessions on the current device. environment=android runs Android system commands and root operations; environment=linux runs the Alpine or Debian environment selected by the user. Apktool build is unavailable until an ARM64 AAPT2 runtime is installed. Use open_and_exec for one-shot commands. Use open to create a persistent shell session and exec with session_id for multi-step work. Use async=true without session_id for long-running independent commands, then read_async_result with job_id to stream output chunks. Use daemon_start for services that must keep running after the Agent run (listening ports, web panels, watchers): the process detaches from any command shell, logs to a file, and survives until daemon_stop or device reboot. Manage daemons with daemon_list, daemon_logs and daemon_stop by task_id. Use close to stop jobs or close sessions.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "action",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("open")
                                                .put("exec")
                                                .put("open_and_exec")
                                                .put("read_async_result")
                                                .put("close")
                                                .put("daemon_start")
                                                .put("daemon_list")
                                                .put("daemon_logs")
                                                .put("daemon_stop")
                                        )
                                        .put("description", "open creates a session. exec runs command in a session or cwd. open_and_exec runs a one-shot command. read_async_result reads async output by job_id. close closes a session_id or job_id. daemon_start launches a detached long-lived service and returns task_id. daemon_list lists daemon tasks with liveness. daemon_logs tails a task log. daemon_stop terminates and removes a task.")
                                )
                                .put(
                                    "identity",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("user").put("root"))
                                        .put("description", "宿主执行身份。Android 默认使用当前可用身份；Linux 根据已选择的后端使用 user 或 root。PRoot 内模拟 root 不授予 Android 特权。")
                                )
                                .put(
                                    "environment",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("android").put("linux"))
                                        .put("description", "android uses the native Android shell with BusyBox applets when available. linux uses the Alpine or Debian environment selected in Eta settings. Default android.")
                                )
                                .put(
                                    "command",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Android shell command to execute. Required for exec/open_and_exec.")
                                )
                                .put(
                                    "cwd",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Working directory. Defaults to /data/local/tmp/eta for android and /workspace for linux. Relative paths use the environment default. ~/ means /storage/emulated/0.")
                                )
                                .put(
                                    "timeout_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Command timeout in milliseconds. Default 30000, max 180000.")
                                )
                                .put(
                                    "merge_stderr",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "Whether stderr should be appended to stdout in command responses.")
                                )
                                .put(
                                    "session_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Session id returned by action=open. Use with exec or close.")
                                )
                                .put(
                                    "job_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Async job id returned when async=true. Use with read_async_result or close.")
                                )
                                .put(
                                    "task_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Daemon task id returned by daemon_start. Use with daemon_logs or daemon_stop.")
                                )
                                .put(
                                    "async",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "Start command in a separate background shell and return immediately with job_id. Do not combine with session_id. Use read_async_result to stream output.")
                                )
                                .put(
                                    "offset_chars",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "For read_async_result, read stdout from this character offset. Default 0.")
                                )
                                .put(
                                    "max_chars",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "For read_async_result, maximum stdout characters to return. Default 8000, max 16000.")
                                )
                                .put(
                                    "close_if_done",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "For read_async_result, remove the async job when it has completed.")
                                )
                        )
                        .put("required", JSONArray().put("action"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "run_command",
                    description = "在 Android 设备上用非交互 Root Shell 执行命令。适合系统信息、包管理、文件检查、Linux 命令流水线。每次调用都是新 shell；不要运行交互式或长期驻留命令。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "command",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "要执行的 shell 命令，可使用管道和重定向。")
                                )
                                .put(
                                    "cwd",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "工作目录，默认 /data/local/tmp/eta。相对路径也按该目录解析；用户存储可用 ~/ 表示 /storage/emulated/0。")
                                )
                                .put(
                                    "timeout_seconds",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "超时秒数，1 到 180，默认 30。")
                                )
                        )
                        .put("required", JSONArray().put("command"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "read_file",
                    description = "读取 Android 文件内容。适合读取配置、日志、小文本文件；大文件用 offset_bytes/max_bytes 分段读取。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("path", JSONObject().put("type", "string"))
                                .put(
                                    "offset_bytes",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "从第几个字节开始，默认 0。")
                                )
                                .put(
                                    "max_bytes",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "最多读取字节数，1 到 262144，默认 65536。")
                                )
                        )
                        .put("required", JSONArray().put("path"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "write_file",
                    description = "写入 Android 文件。可覆盖或追加；会自动创建父目录。用于明确需要修改文件的任务。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("path", JSONObject().put("type", "string"))
                                .put("content", JSONObject().put("type", "string"))
                                .put(
                                    "append",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "true 追加，false 覆盖，默认 false。")
                                )
                        )
                        .put("required", JSONArray().put("path").put("content"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "list_directory",
                    description = "列出 Android 目录内容。默认 /data/local/tmp/eta，输出类似 ls -l。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("path", JSONObject().put("type", "string"))
                                .put("show_hidden", JSONObject().put("type", "boolean"))
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "最多返回 1 到 200 行，默认 80。")
                                )
                        )
                )
            )
    }

}
