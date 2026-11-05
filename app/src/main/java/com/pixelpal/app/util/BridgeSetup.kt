package com.pixelpal.app.util

import com.pixelpal.app.BuildConfig

/**
 * The laptop-side setup command the app shows to users.
 *
 * The download URL is derived from the repository slug at build time
 * (`PIXELPAL_REPO_SLUG`, defaulting to the canonical repo in
 * `app/build.gradle.kts`) so a fork, a rename, or a move to another org does
 * not leave a dead `github.com/...` string baked into three screens.
 */
object BridgeSetup {

    /** Release page with every published artifact. */
    val releasesUrl: String get() = BuildConfig.BRIDGE_RELEASES_URL

    /**
     * Paste-able one-liner: installs the bridge from the newest release, then
     * installs its hooks and starts the daemon. Kept on one line per command so
     * a partial copy never leaves a shell waiting for a continuation.
     */
    val installCommand: String
        get() = "curl -fsSL ${BuildConfig.BRIDGE_INSTALL_URL} | bash\n" +
            "pixelpal-bridge setup"

    /** Short host/label used by the clipboard label and talkback text. */
    const val CLIPBOARD_LABEL = "PixelPal bridge"
}
