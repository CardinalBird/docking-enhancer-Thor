package com.odininputmirror.domain.model

/** What the external pad's Home/Guide button does. `wire` is the daemon's `home_mode` value. */
enum class HomeButtonMode(val wire: Int) {
    /** Forwarded to the target as-is; games see it, Android does not. */
    PASS_THROUGH(0),
    /** Tap is Back, hold is Recents. */
    BACK(1),
    /** Tap is Home, hold is Recents. */
    HOME(2),
}
