package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.libxposed.service.XposedService
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.device.RootAccessState
import io.github.mangi.eta.agent.device.RootAccessStatus
import io.github.mangi.eta.agent.tool.AgentToolCapabilities

internal data class DeviceCapabilitiesUi(
    val root: RootAccessState,
    // 服务回调只反映 Binder 连接，不能用于判断管理器中的模块开关或 Hook 生效状态。
    val xposedConnected: Boolean,
    val tools: AgentToolCapabilities,
) {
    val accessibilityAvailable: Boolean get() = tools.accessibilityAvailable
}


@Composable
internal fun rememberDeviceCapabilities(): DeviceCapabilitiesUi {
    val context = LocalContext.current.applicationContext
    val root by RootAccess.state.collectAsState()
    var xposedConnected by remember { mutableStateOf(EtaApp.serviceInstance != null) }
    var tools by remember { mutableStateOf(AgentToolCapabilities.capture(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, context) {
        val listener = object : EtaApp.ServiceStateListener {
            override fun onServiceStateChanged(service: XposedService?) {
                xposedConnected = service != null
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                RootAccess.refresh(context)
                tools = AgentToolCapabilities.capture(context)
            }
        }
        EtaApp.addServiceStateListener(listener, notifyImmediately = true)
        owner.lifecycle.addObserver(observer)
        onDispose {
            EtaApp.removeServiceStateListener(listener)
            owner.lifecycle.removeObserver(observer)
        }
    }
    return DeviceCapabilitiesUi(root, xposedConnected, tools.copy(rootAvailable = root.isGranted))
}

internal fun RootAccessState.description(context: Context): String = context.getString(
    when {
        isChecking -> R.string.capability_root_checking
        status == RootAccessStatus.GRANTED -> R.string.capability_root_granted
        status == RootAccessStatus.UNAVAILABLE -> R.string.capability_root_unavailable
        status == RootAccessStatus.TIMED_OUT -> R.string.capability_root_timeout
        else -> R.string.capability_root_not_granted
    },
)
