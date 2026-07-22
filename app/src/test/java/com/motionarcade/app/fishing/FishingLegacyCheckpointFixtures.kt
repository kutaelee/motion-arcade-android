package com.motionarcade.app.fishing

import java.util.Base64

/** Exact bytes emitted by the pre-envelope encoder at commit a09ad70. */
internal object FishingLegacyCheckpointFixtures {
    val pausedV1: ByteArray
        get() = Base64.getDecoder().decode(PAUSED_V1_BASE64)

    private const val PAUSED_V1_BASE64 =
        "TUFGQwAAAAEAAAADABpmaXNoaW5nLWVudmVsb3BlLW1pZ3JhdGlvbgAHRklTSElORwAEU09MTwAQZmlz" +
            "aGluZy1ydWxlcy12MQAAAAAAAAAdAApzcGxpdG1peDY0AAAAAQAAAAAAAAAdAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAAAAAAAAAQAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADAAAAAAAAAAAABlBBVVNF" +
            "RAEADkFQUF9CQUNLR1JPVU5EAAVSRUFEWQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAQAAAAAAAAABAAAAAAD//////////wAAAAAAAAAA//////////8AAAAAAAAAAAAAAAAAAAAAAAAA" +
            "//////////8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
}
