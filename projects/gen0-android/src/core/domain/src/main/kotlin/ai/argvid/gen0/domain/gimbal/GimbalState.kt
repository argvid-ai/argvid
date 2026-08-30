package ai.argvid.gen0.domain.gimbal

enum class GimbalConnectionState(val wireName: String) {
    Disconnected("disconnected"),
    Discovering("discovering"),
    Connecting("connecting"),
    Ready("ready"),
}

enum class GimbalMotionState(val wireName: String) {
    Idle("idle"),
    Moving("moving"),
    Settling("settling"),
    Holding("holding"),
    Stalled("stalled"),
    Fault("fault"),
}
