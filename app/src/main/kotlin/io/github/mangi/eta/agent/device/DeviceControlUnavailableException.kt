package io.github.mangi.eta.agent.device

/** 在坐标换算与动作之间断连时，仍向工具边界返回可识别的无障碍状态。 */
internal class DeviceControlUnavailableException : IllegalStateException(
    "Eta 无障碍服务已断开，请重新开启服务并观察屏幕",
)
