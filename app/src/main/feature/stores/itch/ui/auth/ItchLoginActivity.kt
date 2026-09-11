package com.winlator.cmod.feature.stores.itch.ui.auth

import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import androidx.activity.compose.setContent
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.winlator.cmod.feature.stores.epic.ui.component.dialog.AuthWebViewDialog
import com.winlator.cmod.feature.stores.itch.service.ItchAuthManager
import com.winlator.cmod.feature.stores.itch.service.ItchConstants
import com.winlator.cmod.shared.android.FixedFontScaleComponentActivity
import com.winlator.cmod.shared.theme.WinNativeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ItchLoginActivity : FixedFontScaleComponentActivity() {
    private var completed by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().setAcceptCookie(true)

        setContent {
            WinNativeTheme(colorScheme = darkColorScheme()) {
                AuthWebViewDialog(
                    isVisible = !completed,
                    url = ItchConstants.LOGIN_URL,
                    onDismissRequest = {
                        setResult(if (ItchAuthManager.isLoggedIn(this)) Activity.RESULT_OK else Activity.RESULT_CANCELED)
                        finish()
                    },
                    onPageFinished = { _, _ -> captureIfSignedIn() },
                )
            }
        }
    }

    private fun captureIfSignedIn() {
        if (completed) return
        CookieManager.getInstance().flush()
        if (!ItchAuthManager.captureWebViewSession(this)) return
        completed = true
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { ItchAuthManager.refreshProfile(applicationContext) }
            setResult(Activity.RESULT_OK)
            finish()
        }
    }
}
