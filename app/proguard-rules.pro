# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# libxposed 通过 META-INF/xposed/java_init.list 中的类名字符串加载模块入口；
# 允许入口类混淆时，需要同步改写 java_init.list，避免 release 裁剪后模块失效。
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation class fuck.andes.ModuleMain {
    public <init>();
}

# R8 默认规则已覆盖 Compose 运行时；Miuix 图标是普通 Kotlin 代码，允许 R8 裁掉未使用图标。
# -dontwarn 仅抑制 KMP 依赖在 Android 侧可能出现的可选平台 warning，不阻止裁剪。
-dontwarn top.yukonga.miuix.**

# libxposed service 通过静态调用和 manifest provider 接入，交给 R8/Android 默认规则保留可达代码。
-dontwarn io.github.libxposed.service.**

# 配置 key 是字符串常量并通过静态调用访问，不需要保留类名或成员名。

# ── Release 日志策略 ────────────────────────────────────────────────────────
# 仅删除 Eta 自有代码中的 Android VERBOSE/DEBUG 调用；INFO/WARN/ERROR 必须保留，
# 第三方依赖的日志策略由依赖自身决定。
-maximumremovedandroidloglevel 3 class fuck.andes.** { *; }

# XposedModule.log 不是 android.util.Log，R8 无法通过上面的规则识别。
# debug supplier 是纯观察 API；禁止在 supplier 内执行任何业务副作用。
-assumenosideeffects interface fuck.andes.core.AgentLogger {
    public abstract void debug(kotlin.jvm.functions.Function0);
}
-assumenosideeffects class fuck.andes.core.AndroidAgentLogger {
    public void debug(kotlin.jvm.functions.Function0);
}
-assumenosideeffects class fuck.andes.core.ModuleLogger {
    public void debug(kotlin.jvm.functions.Function0);
}

# ── 序列化与网络依赖 ─────────────────────────────────────────────────────────
# DataStore、kotlinx.serialization、OkHttp 与 Okio 均自带精确的 consumer rules；
# 不在 App 层重复保留整个类或包，避免阻断裁剪、内联和混淆。
# 保留源码与行号属性，便于使用 release mapping 还原线上堆栈。
-keepattributes SourceFile,LineNumberTable

# MCP SDK 是 KMP 产物，依赖 kotlinx-serialization 多态反序列化 JSON-RPC 消息；
# 其自带 consumer rules 可能不完整，整体保留以保证 release 下协议编解码稳定。
-keep class io.modelcontextprotocol.kotlin.sdk.** { *; }
