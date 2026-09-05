package io.github.mangi.eta.agent.runtime

/** 由客户端 Main Handler 按接收顺序调用，隔离历史恢复与实时事件的交付。 */
internal class AgentRuntimeAttachDelivery(
    private val onReplay: ((List<AgentEvent>) -> Unit)? = null,
    private val onEvent: (AgentEvent) -> Unit,
    private val onAttachResponse: (Boolean) -> Unit,
    private val onResult: (AgentRuntimeWire.RunResult) -> Unit,
) {
    private enum class State {
        REPLAYING,
        LIVE,
        CLOSED,
    }

    private var state = State.REPLAYING
    private val replayEvents = mutableListOf<AgentEvent>()

    fun event(event: AgentEvent) {
        when (state) {
            State.REPLAYING -> replayEvents += event
            State.LIVE -> onEvent(event)
            State.CLOSED -> Unit
        }
    }

    fun attachResponse(attached: Boolean) {
        if (state != State.REPLAYING) return
        if (attached) {
            state = State.LIVE
            deliverReplay()
        } else {
            state = State.CLOSED
            replayEvents.clear()
        }
        onAttachResponse(attached)
    }

    fun result(result: AgentRuntimeWire.RunResult) {
        if (state == State.CLOSED) return
        val needsReplay = state == State.REPLAYING
        state = State.CLOSED
        // 旧服务的成功响应在释放 Session 锁后发送，终态可能先到；先恢复历史再交付终态。
        if (needsReplay) deliverReplay()
        onResult(result)
    }

    private fun deliverReplay() {
        val events = replayEvents.toList()
        replayEvents.clear()
        if (onReplay != null) {
            onReplay(events)
        } else {
            events.forEach(onEvent)
        }
    }
}
