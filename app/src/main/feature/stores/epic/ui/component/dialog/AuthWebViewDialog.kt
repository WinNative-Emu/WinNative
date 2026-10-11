package com.winlator.cmod.feature.stores.epic.ui.component.dialog
import android.content.res.Configuration
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.cmod.R
import com.winlator.cmod.feature.stores.steam.utils.redactUrlForLogging
import com.winlator.cmod.shared.theme.WinNativeTheme
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthWebViewDialog(
    isVisible: Boolean,
    url: String,
    onDismissRequest: () -> Unit,
    onUrlChange: ((String) -> Unit)? = null,
    onPageFinished: ((url: String, webView: WebView) -> Unit)? = null,
    customWebViewClient: WebViewClient? = null,
) {
    if (isVisible) {
        val defaultTitle = stringResource(R.string.steam_login_auth_webview_title)
        var topBarTitle by rememberSaveable { mutableStateOf(defaultTitle) }
        val startingUrl by rememberSaveable(url) { mutableStateOf(url) }
        var webView: WebView? = remember { null }
        val webViewState = rememberSaveable { Bundle() }

        Dialog(
            onDismissRequest = {
                if (webView?.canGoBack() == true) {
                    webView!!.goBack()
                } else {
                    webViewState.clear()
                    onDismissRequest()
                }
            },
            properties =
                DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnClickOutside = false,
                ),
            content = {
                Scaffold(
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = {
                                Text(
                                    text = topBarTitle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            navigationIcon = {
                                IconButton(
                                    onClick = {
                                        webViewState.clear()
                                        onDismissRequest()
                                    },
                                    content = {
                                        Icon(
                                            imageVector = Icons.Outlined.Close,
                                            contentDescription = stringResource(R.string.common_ui_close),
                                        )
                                    },
                                )
                            },
                        )
                    },
                ) { paddingValues ->
                    AndroidView(
                        // Full-screen dialog: nothing else consumes the IME inset, so the
                        // keyboard would cover the password field and the sign-in button.
                        modifier = Modifier.padding(paddingValues).imePadding(),
                        factory = { context ->
                            WebView(context).apply {
                                layoutParams =
                                    ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    )

                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    loadWithOverviewMode = true
                                    useWideViewPort = true
                                    builtInZoomControls = true
                                    displayZoomControls = false
                                    setSupportZoom(true)
                                    allowFileAccess = false
                                    allowContentAccess = false
                                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                }

                                webViewClient = customWebViewClient ?: object : WebViewClient() {
                                    private fun handleUrl(url: String?) {
                                        Timber.d("Auth WebView navigating to: ${redactUrlForLogging(url)}")
                                        url?.let { currentUrl -> onUrlChange?.invoke(currentUrl) }
                                    }

                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                    ): Boolean {
                                        handleUrl(request?.url?.toString())
                                        return super.shouldOverrideUrlLoading(view, request)
                                    }

                                    override fun onPageFinished(
                                        view: WebView?,
                                        url: String?,
                                    ) {
                                        super.onPageFinished(view, url)
                                        Timber.d("Auth WebView page finished loading: ${redactUrlForLogging(url)}")
                                        if (view != null && url != null) {
                                            onPageFinished?.invoke(url, view)
                                        }
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: WebResourceError?,
                                    ) {
                                        super.onReceivedError(view, request, error)
                                        Timber.e(
                                            "Auth WebView error: ${error?.errorCode} - ${error?.description} for URL: ${redactUrlForLogging(
                                                request?.url?.toString(),
                                            )}",
                                        )
                                    }
                                }

                                webChromeClient =
                                    object : WebChromeClient() {
                                        override fun onReceivedTitle(
                                            view: WebView?,
                                            title: String?,
                                        ) {
                                            title?.let { pageTitle ->
                                                topBarTitle = pageTitle
                                                Timber.d("Auth WebView title: $pageTitle")
                                            }
                                        }

                                        override fun onProgressChanged(
                                            view: WebView?,
                                            newProgress: Int,
                                        ) {
                                            super.onProgressChanged(view, newProgress)
                                            Timber.d("Auth WebView progress: $newProgress%")
                                        }
                                    }

                                if (webViewState.size() > 0) {
                                    restoreState(webViewState)
                                } else {
                                    Timber.d("Loading Auth WebView URL: ${redactUrlForLogging(startingUrl)}")
                                    loadUrl(startingUrl)
                                }
                                webView = this
                            }
                        },
                        update = {
                            webView = it
                        },
                        onRelease = { view ->
                            view.saveState(webViewState)
                            view.stopLoading()
                            view.webViewClient = WebViewClient()
                            view.webChromeClient = WebChromeClient()
                            view.removeAllViews()
                            view.destroy()
                        },
                    )
                }
            },
        )
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview
@Composable
private fun Preview_AuthWebView() {
    WinNativeTheme(colorScheme = darkColorScheme()) {
        AuthWebViewDialog(
            isVisible = true,
            url = "https://auth.gog.com/auth?client_id=46899977096215655&redirect_uri=https%3A%2F%2Fembed.gog.com%2Fon_login_success%3Forigin%3Dclient&response_type=code&layout=galaxy",
            onDismissRequest = {},
        )
    }
}
