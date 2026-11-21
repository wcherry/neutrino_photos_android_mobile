package com.neutrino.photos

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.neutrino.photos.ui.PhotosBrand
import com.neutrino.photos.ui.Root
import com.neutrino.shared.android.LocalNeutrinoAccount
import com.neutrino.shared.android.security.AppLock
import com.neutrino.shared.android.ui.NeutrinoTheme
import com.neutrino.shared.android.ui.neutrinoColorScheme
import kotlinx.coroutines.launch

/** The one activity. A `FragmentActivity` because `BiometricPrompt` needs one; everything on screen is Compose. */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val settings = graph.account.settings
        if (savedInstanceState == null) {
            AppLock.lockOnLaunch(settings.appLock.value)
            handle(intent)
        }
        // With the lock on, the recents screen shows a blank card rather than a photo.
        lifecycleScope.launch {
            settings.appLock.collect { on ->
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        setContent {
            val theme by graph.prefs.theme.flow.collectAsState()
            val dark = when (theme) {
                AppTheme.SYSTEM -> isSystemInDarkTheme()
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
            }
            CompositionLocalProvider(LocalNeutrinoAccount provides graph.account) {
                NeutrinoTheme(PhotosBrand, neutrinoColorScheme(PhotosBrand, dark)) {
                    Root()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        AppLock.onForeground(graph.account.settings.appLock.value)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) AppLock.onBackground()
    }

    /**
     * `https://www.getneutrino.app/open/photo/<file id>` names a photo by its Drive file id, as on iOS
     * and the web; only the id travels, and the library says whether it holds that photo. A share from
     * another app (one picture or several) is imported. Both are held by the router until the app is
     * signed in and showing.
     */
    private fun handle(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let(PhotoLinks::fileId)?.let { graph.router.pendingLink = it }
            Intent.ACTION_SEND -> intent.streamUri()?.let { graph.router.pendingShares = graph.router.pendingShares + it }
            Intent.ACTION_SEND_MULTIPLE -> graph.router.pendingShares = graph.router.pendingShares + intent.streamUris()
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamUri(): Uri? =
        if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun Intent.streamUris(): List<Uri> =
        (if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
}

/** The photo links this app answers. `/open/note/…` and the like belong to the sibling apps. */
object PhotoLinks {
    fun fileId(uri: Uri): String? {
        val segments = uri.pathSegments
        return if (uri.scheme == "https" && (uri.host == "www.getneutrino.app" || uri.host == "getneutrino.app") &&
            segments.size == 3 && segments[0] == "open" && segments[1] == "photo") segments[2].trim().takeIf { it.isNotEmpty() } else null
    }

    fun link(fileId: String) = "https://www.getneutrino.app/open/photo/$fileId"
}
