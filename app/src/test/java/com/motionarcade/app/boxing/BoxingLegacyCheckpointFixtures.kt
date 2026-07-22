package com.motionarcade.app.boxing

import java.util.Base64

/** Exact bytes emitted by the pre-envelope boxing encoder at commit dece589. */
internal object BoxingLegacyCheckpointFixtures {
    val soloPausedV3: ByteArray
        get() = Base64.getDecoder().decode(SOLO_PAUSED_V3_BASE64)

    val dualPausedV3: ByteArray
        get() = Base64.getDecoder().decode(DUAL_PAUSED_V3_BASE64)

    /** Exact payload bytes emitted by the immediately previous v4 envelope writer at 68c33eb. */
    val soloPausedV4: ByteArray
        get() = Base64.getDecoder().decode(SOLO_PAUSED_V4_BASE64)

    val dualPausedV4: ByteArray
        get() = Base64.getDecoder().decode(DUAL_PAUSED_V4_BASE64)

    private const val SOLO_PAUSED_V3_BASE64 =
        "QlhORwAAAAMAAAAEABJib3hpbmctbGVnYWN5LXNvbG8ABkJPWElORwAEU09MTwATYm94aW5nLXJ1bGVz" +
            "LXYzLXB2cAAAAAAAAAA9AAAAAgAAAAAAAAAAAAZQQVVTRUQBAA5BUFBfQkFDS0dST1VORAAFUk9VTkQA" +
            "AABkAAAAZAAAAGQAAAAAAAADhAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAD///////////////////////////////8AAAAAAA=="

    private const val DUAL_PAUSED_V3_BASE64 =
        "QlhORwAAAAMAAAAEABJib3hpbmctbGVnYWN5LWR1YWwABkJPWElORwAERFVBTAATYm94aW5nLXJ1bGVz" +
            "LXYzLXB2cAAAAAAAAABDAAAAAgAAAAAAAAAAAAZQQVVTRUQBAA5BUFBfQkFDS0dST1VORAAFUk9VTkQA" +
            "AABkAAAAZAAAAGQAAAAAAAADhAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAD///////////////////////////////8AAAAAAgACUDEAAABkAAAAZAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAA////////////////////////////////AAJQMgAAAGQAAABkAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD///////////////////////////////8="

    private const val SOLO_PAUSED_V4_BASE64 =
        "QlhORwAAAAQAAAAFABJib3hpbmctbGVnYWN5LXNvbG8ABkJPWElORwAEU09MTwATYm94aW5nLXJ1bGVz" +
            "LXYzLXB2cAAAAAAAAAA9ABRib3hpbmctYWktb3JkaW5hbC12MQAAAAEAAAAAAAAAAAAAAAIAAAAAAAAA" +
            "AAAGUEFVU0VEAQAOQVBQX0JBQ0tHUk9VTkQABVJPVU5EAAAAZAAAAGQAAABkAAAAAAAAA4QAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA////////////////////////////////" +
            "AAAAAAA="

    private const val DUAL_PAUSED_V4_BASE64 =
        "QlhORwAAAAQAAAAFABJib3hpbmctbGVnYWN5LWR1YWwABkJPWElORwAERFVBTAATYm94aW5nLXJ1bGVz" +
            "LXYzLXB2cAAAAAAAAABDABRib3hpbmctYWktb3JkaW5hbC12MQAAAAEAAAAAAAAAAAAAAAIAAAAAAAAA" +
            "AAAGUEFVU0VEAQAOQVBQX0JBQ0tHUk9VTkQABVJPVU5EAAAAZAAAAGQAAABkAAAAAAAAA4QAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA////////////////////////////////" +
            "AAAAAAIAAlAxAAAAZAAAAGQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAP//////" +
            "/////////////////////////wACUDIAAABkAAAAZAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAA////////////////////////////////"
}
