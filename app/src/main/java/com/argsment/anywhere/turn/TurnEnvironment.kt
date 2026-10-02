package com.argsment.anywhere.turn

import android.content.Context
import java.io.File

/**
 * Process-wide access to the TURN settings, metadata store and log location for code
 * that has no `Context` of its own (the lwIP connections, the subscription fetcher).
 *
 * The app is single-process: [install] is called from both `VpnViewModel` and
 * `AnywhereVpnService`, whichever comes first wins and later calls are no-ops. Code
 * reached before installation (only possible in tests) sees `null` and treats TURN as
 * unconfigured.
 */
object TurnEnvironment {

    @Volatile
    var settings: TurnSettings? = null
        private set

    @Volatile
    var metadataStore: TurnMetadataStore? = null
        private set

    /** `cacheDir/turn-core.log` (iOS: `<app group>/Library/Caches/turn-core.log`). */
    @Volatile
    var coreLogFile: File? = null
        private set

    @Synchronized
    fun install(context: Context) {
        if (settings != null) return
        val app = context.applicationContext
        metadataStore = TurnMetadataStore(File(app.filesDir, TurnMetadataStore.FILE_NAME))
        coreLogFile = File(app.cacheDir, "turn-core.log")
        settings = TurnSettings.from(app)
    }

    /** For JVM tests only. */
    @Synchronized
    fun installForTesting(settings: TurnSettings?, metadataStore: TurnMetadataStore?) {
        this.settings = settings
        this.metadataStore = metadataStore
        this.coreLogFile = null
    }
}
