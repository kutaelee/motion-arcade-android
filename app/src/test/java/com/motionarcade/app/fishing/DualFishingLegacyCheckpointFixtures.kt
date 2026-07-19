package com.motionarcade.app.fishing

import java.util.Base64

/** Exact bytes emitted by the pre-envelope dual-fishing encoder at commit dece589. */
internal object DualFishingLegacyCheckpointFixtures {
    val pausedV2: ByteArray
        get() = Base64.getDecoder().decode(PAUSED_V2_BASE64)

    private const val PAUSED_V2_BASE64 =
        "REZTSAAAAAIAAAABABRkdWFsLWxlZ2FjeS1lbnZlbG9wZQAHRklTSElORwAERFVBTAAaZHVhbC1maXNo" +
            "aW5nLWNvb3AtcnVsZXMtdjMAAAAAAAAANQAWc3BsaXRtaXg2NC1wbGF5ZXItc2FsdAAAAAEAAAAAAAAA" +
            "NQAAAAIAAAAAAAAAAAAGUEFVU0VEAQAOQVBQX0JBQ0tHUk9VTkQAAlAxAAAAAAAAAAAAAlAxAAVSRUFE" +
            "WQAAAAAAAAAAAAAAAAAAAAEAAAAAAP///////////////////////////////wACUDIABVJFQURZAAAA" +
            "AAAAAAAAAAAAAAAAAQAAAAAA////////////////////////////////"
}
