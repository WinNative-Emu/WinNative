package com.winlator.cmod.feature.stores.steam
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlator.cmod.R
import com.winlator.cmod.shared.ui.layout.isPortraitLayout
import com.winlator.cmod.feature.stores.steam.enums.LoginResult
import com.winlator.cmod.feature.stores.steam.enums.LoginScreen
import com.winlator.cmod.feature.stores.steam.service.SteamService
import com.winlator.cmod.feature.stores.steam.ui.SteamLoginViewModel
import com.winlator.cmod.feature.stores.steam.ui.components.QrCodeImage
import com.winlator.cmod.feature.stores.steam.ui.data.UserLoginState
import com.winlator.cmod.runtime.system.SessionKeepAliveService
import com.winlator.cmod.shared.android.FixedFontScaleComponentActivity
import com.winlator.cmod.shared.theme.WinNativeTheme
import com.winlator.cmod.shared.ui.outlinedSwitchColors
import timber.log.Timber

// Palette (matches Settings > Stores)
private val BgDark = Color(0xFF18181D)
private val CardDark = Color(0xFF1C1C2A)
private val CardBorder = Color(0xFF2A2A3A)
private val IconBoxBg = Color(0xFF242434)
private val Accent = Color(0xFF1A9FFF)
private val TextPrimary = Color(0xFFF0F4FF)
private val TextSecondary = Color(0xFF7A8FA8)
private val DangerRed = Color(0xFFFF7A88)

class SteamLoginActivity : FixedFontScaleComponentActivity() {

    private val viewModel: SteamLoginViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
//            startForegroundService(android.content.Intent(this, com.winlator.cmod.feature.stores.steam.service.SteamService::class.java))
            startService(android.content.Intent(this, com.winlator.cmod.feature.stores.steam.service.SteamService::class.java))
            SessionKeepAliveService.startComponent(this, SessionKeepAliveService.COMPONENT_STEAM, "Steam Login Service")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start SteamService from SteamLoginActivity")
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            WinNativeTheme(
                colorScheme =
                    darkColorScheme(
                        primary = Accent,
                        background = BgDark,
                        surface = CardDark,
                        onSurface = TextPrimary,
                        secondary = TextSecondary,
                        outline = CardBorder,
                    ),
            ) {
                LoginContent(viewModel)
            }
        }
    }

    override fun onDestroy() {
        // Manually unregister this service as reason.
        SessionKeepAliveService.stopComponent(this, SessionKeepAliveService.COMPONENT_STEAM)

        // Stop the background SteamService if we are leaving the login screen
        // without a successful login. This removes the "Steam service active" reason.
        val state = viewModel.loginState.value
        if (state.loginResult != LoginResult.Success) {
            SteamService.Companion.stop()
        }

        super.onDestroy()
    }

    // Root
    @Composable
    fun LoginContent(viewModel: SteamLoginViewModel) {
        val state by viewModel.loginState.collectAsState()
        var passwordVisible by remember { mutableStateOf(false) }

        LaunchedEffect(state.loginResult) {
            if (state.loginResult == LoginResult.Success) {
                Timber.i("User logged in, finishing activity")
                finish()
            }
        }
        LaunchedEffect(state.loginScreen) {
            if (state.loginScreen == LoginScreen.CREDENTIAL) viewModel.onStartQrLogin()
        }

        // Entrance: fade + slide up
        var entered by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { entered = true }

        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

        // Ensure the system Back button finishes the activity properly
        BackHandler {
            finish()
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(BgDark)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
        ) {
            AnimatedVisibility(
                visible = entered,
                enter =
                    fadeIn(tween(360)) +
                        slideInVertically(
                            initialOffsetY = { it / 11 },
                            animationSpec = tween(380, easing = FastOutSlowInEasing),
                        ),
            ) {
                AnimatedContent(
                    targetState = state.loginScreen,
                    transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(160)) },
                    label = "screenSwitch",
                ) { screen ->
                    when (screen) {
                        LoginScreen.TWO_FACTOR -> {
                            TwoFactorLogin(state, viewModel)
                        }

                        else -> {
                            LandscapeLogin(state, viewModel, passwordVisible) { passwordVisible = !passwordVisible }
                        }
                    }
                }
            }
        }
    }

    // Landscape
    @Composable
    private fun LandscapeLogin(
        state: UserLoginState,
        viewModel: SteamLoginViewModel,
        passwordVisible: Boolean,
        onTogglePassword: () -> Unit,
    ) {
        val portraitLogin = isPortraitLayout()

        val backButton: @Composable (Modifier) -> Unit = { backModifier ->
            IconButton(
                onClick = ::finish,
                modifier =
                    backModifier
                        .statusBarsPadding()
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(CardDark.copy(alpha = 0.72f)),
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = TextPrimary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        val credentialsPane: @Composable (Modifier) -> Unit = { credModifier ->
            Column(
                modifier = credModifier,
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Compact header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(bottom = 14.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(Accent.copy(alpha = 0.12f))
                                .border(1.dp, Accent.copy(alpha = 0.3f), RoundedCornerShape(11.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Gamepad, null, tint = Accent, modifier = Modifier.size(22.dp))
                    }
                    Column {
                        Text(
                            stringResource(R.string.stores_accounts_steam_integration_title),
                            color = TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.steam_login_sign_in_to_your_account), color = TextSecondary, fontSize = 11.sp)
                            if (state.isLoggingIn) {
                                CircularProgressIndicator(modifier = Modifier.size(10.dp), color = Accent, strokeWidth = 1.5.dp)
                            }
                        }
                    }
                }
                CredentialForm(state, viewModel, passwordVisible, onTogglePassword, compact = true)
            }
        }

        val qrPane: @Composable (Modifier) -> Unit = { qrModifier ->
            Box(
                modifier = qrModifier,
                contentAlignment = Alignment.Center,
            ) {
                QrCard(state, viewModel, isLandscape = !portraitLogin)
            }
        }

        if (portraitLogin) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .imePadding()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
                        .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                backButton(Modifier.align(Alignment.Start))
                credentialsPane(Modifier.fillMaxWidth())
                qrPane(Modifier.fillMaxWidth())
            }
        } else {
            Row(
                modifier = Modifier.fillMaxSize().imePadding().padding(start = 8.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                backButton(Modifier.align(Alignment.Top))
                credentialsPane(Modifier.weight(1.3f).fillMaxHeight().verticalScroll(rememberScrollState()))

                Box(
                    modifier =
                        Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .padding(vertical = 24.dp)
                            .background(CardBorder),
                )

                qrPane(Modifier.weight(0.9f).fillMaxHeight())
            }
        }
    }

    // Credential form
    @Composable
    private fun CredentialForm(
        state: UserLoginState,
        viewModel: SteamLoginViewModel,
        passwordVisible: Boolean,
        onTogglePassword: () -> Unit,
        compact: Boolean = false,
    ) {
        var hasAttemptedLogin by remember { mutableStateOf(false) }
        // Clear the error when user edits credentials
        LaunchedEffect(state.username, state.password) { hasAttemptedLogin = false }

        val showLoginError = hasAttemptedLogin && !state.isLoggingIn && state.loginResult == LoginResult.Failed
        val spacing = if (compact) 10.dp else 14.dp
        val passwordFocus = remember { FocusRequester() }
        // Credentials login connects on demand; no pre-login connection is needed.
        val canSubmit =
            state.username.isNotEmpty() && state.password.isNotEmpty() &&
                !state.isLoggingIn

        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
            OutlinedTextField(
                value = state.username,
                onValueChange = { viewModel.setUsername(it) },
                label = { Text(stringResource(R.string.steam_login_username)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isLoggingIn,
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = darkFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
            )
            Box {
                OutlinedTextField(
                    value = state.password,
                    onValueChange = { viewModel.setPassword(it) },
                    label = { Text(stringResource(R.string.steam_login_password)) },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = onTogglePassword) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                                contentDescription = null,
                                tint = TextSecondary,
                            )
                        }
                    },
                    isError = showLoginError,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(passwordFocus)
                            .onFocusChanged { if (!it.hasFocus && passwordVisible) onTogglePassword() },
                    enabled = !state.isLoggingIn,
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = darkFieldColors(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions =
                        KeyboardActions(onDone = {
                            if (canSubmit) {
                                hasAttemptedLogin = true
                                viewModel.onCredentialLogin()
                            }
                        }),
                )
                androidx.compose.animation.AnimatedVisibility(
                    visible = showLoginError,
                    enter = fadeIn(tween(200)),
                    exit = fadeOut(tween(150)),
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .offset(y = (-2).dp)
                            .padding(horizontal = 12.dp),
                ) {
                    Text(
                        stringResource(R.string.steam_login_invalid_username_or_password),
                        color = DangerRed,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // Remember me
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                Text(stringResource(R.string.steam_login_remember_me), color = TextSecondary, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = state.rememberSession,
                    onCheckedChange = {
                        if (passwordVisible) onTogglePassword()
                        viewModel.setRememberSession(it)
                    },
                    enabled = !state.isLoggingIn,
                    colors =
                        outlinedSwitchColors(
                            accentColor = Accent,
                            textSecondaryColor = TextSecondary,
                        ),
                )
            }

            LoginButton(
                text = stringResource(R.string.common_ui_sign_in),
                enabled = canSubmit,
                loading = state.isLoggingIn,
                onClick = {
                    hasAttemptedLogin = true
                    viewModel.onCredentialLogin()
                },
            )
        }
    }

    // QR card
    @Composable
    private fun QrCard(
        state: UserLoginState,
        viewModel: SteamLoginViewModel,
        isLandscape: Boolean,
    ) {
        val isLoading = state.qrCode == null && !state.isQrFailed

        // Border pulses while QR loads
        val pulse = rememberInfiniteTransition(label = "qrPulse")
        val borderAlpha by pulse.animateFloat(
            initialValue = 0.14f,
            targetValue = 0.55f,
            animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "qrBorder",
        )

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CardDark)
                    .border(1.dp, if (isLoading) Accent.copy(alpha = borderAlpha) else CardBorder, RoundedCornerShape(16.dp))
                    .padding(if (isLandscape) 16.dp else 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.steam_login_sign_in_with_qr_code),
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )

                when {
                    state.qrCode != null -> {
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White).padding(6.dp),
                        ) {
                            QrCodeImage(content = state.qrCode!!, size = if (isLandscape) 130.dp else 190.dp)
                        }
                        Text(
                            stringResource(R.string.steam_login_open_steam_app_qr_hint),
                            color = TextSecondary,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                        )
                    }

                    state.isQrFailed -> {
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.steam_login_failed_to_load_qr_code), color = DangerRed, fontSize = 13.sp)
                        Spacer(Modifier.height(4.dp))
                        SmallActionButton(stringResource(R.string.steam_login_retry), Accent) { viewModel.onQrRetry() }
                    }

                    else -> {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), color = Accent, strokeWidth = 2.dp)
                        Text(stringResource(R.string.steam_login_generating_code), color = TextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    // 2FA screen
    @Composable
    private fun TwoFactorLogin(
        state: UserLoginState,
        viewModel: SteamLoginViewModel,
    ) {
        val isSteamGuard = state.lastTwoFactorMethod == "steam_guard"
        val isEmailCode = state.lastTwoFactorMethod == "email_code"

        // Entrance animation for the card
        var cardVisible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { cardVisible = true }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = cardVisible,
                enter =
                    fadeIn(tween(320)) +
                        scaleIn(
                            initialScale = 0.92f,
                            animationSpec = tween(340, easing = FastOutSlowInEasing),
                        ),
            ) {
                Box(
                    modifier =
                        Modifier
                            .width(280.dp)
                            .background(CardDark, RoundedCornerShape(16.dp))
                            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
                            .padding(horizontal = 24.dp, vertical = 24.dp),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        WaitingRipple()

                        Spacer(Modifier.height(14.dp))

                        Text(
                            stringResource(R.string.steam_login_two_factor_auth),
                            color = TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(6.dp))

                        if (isSteamGuard) {
                            Text(
                                stringResource(R.string.steam_login_approve_login_steam_app),
                                color = TextSecondary,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp,
                            )
                        } else {
                            val methodText =
                                if (isEmailCode) {
                                    stringResource(R.string.steam_login_enter_code_sent_to, state.email ?: "")
                                } else {
                                    stringResource(R.string.steam_login_enter_steam_guard_code)
                                }
                            Text(
                                methodText,
                                color = TextSecondary,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp,
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = state.twoFactorCode,
                                onValueChange = { viewModel.setTwoFactorCode(it) },
                                label = { Text(stringResource(R.string.steam_login_code), fontSize = 11.sp) },
                                modifier = Modifier.widthIn(max = 160.dp),
                                keyboardOptions =
                                    KeyboardOptions(
                                        keyboardType = KeyboardType.Text,
                                        imeAction = ImeAction.Done,
                                    ),
                                keyboardActions =
                                    KeyboardActions(
                                        onDone = {
                                            if (state.twoFactorCode.length >= 5 && !state.isLoggingIn) {
                                                viewModel.submitTwoFactor()
                                            }
                                        },
                                    ),
                                enabled = !state.isLoggingIn,
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                colors = darkFieldColors(),
                                textStyle =
                                    androidx.compose.ui.text.TextStyle(
                                        fontSize = 14.sp,
                                        textAlign = TextAlign.Center,
                                        letterSpacing = 4.sp,
                                    ),
                            )
                            Spacer(Modifier.height(10.dp))
                            TwoFactorSubmitButton(
                                enabled = state.twoFactorCode.length >= 5 && !state.isLoggingIn,
                                loading = state.isLoggingIn,
                                onClick = { viewModel.submitTwoFactor() },
                            )
                        }

                        Spacer(Modifier.height(14.dp))
                        SmallActionButton(stringResource(R.string.common_ui_cancel), TextSecondary) {
                            viewModel.onShowLoginScreen(LoginScreen.CREDENTIAL)
                        }
                    }
                }
            }
        }
    }

    // Expanding ripple for Steam Guard wait
    @Composable
    private fun WaitingRipple() {
        val inf = rememberInfiniteTransition(label = "ripple")
        val scale1 by inf.animateFloat(
            initialValue = 1f,
            targetValue = 2.8f,
            animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Restart),
            label = "r1s",
        )
        val alpha1 by inf.animateFloat(
            initialValue = 0.40f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Restart),
            label = "r1a",
        )
        val scale2 by inf.animateFloat(
            initialValue = 1f,
            targetValue = 2.8f,
            animationSpec = infiniteRepeatable(tween(1600, 620, easing = FastOutSlowInEasing), RepeatMode.Restart),
            label = "r2s",
        )
        val alpha2 by inf.animateFloat(
            initialValue = 0.40f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(tween(1600, 620, easing = FastOutSlowInEasing), RepeatMode.Restart),
            label = "r2a",
        )

        Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
            Box(
                modifier =
                    Modifier
                        .size(52.dp)
                        .scale(scale1)
                        .clip(CircleShape)
                        .background(Accent.copy(alpha = alpha1)),
            )
            Box(
                modifier =
                    Modifier
                        .size(52.dp)
                        .scale(scale2)
                        .clip(CircleShape)
                        .background(Accent.copy(alpha = alpha2)),
            )
            Box(
                modifier =
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Accent.copy(alpha = 0.13f))
                        .border(1.dp, Accent.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Lock, null, tint = Accent, modifier = Modifier.size(22.dp))
            }
        }
    }

    // Primary login/submit button
    @Composable
    private fun LoginButton(
        text: String,
        enabled: Boolean,
        loading: Boolean,
        onClick: () -> Unit,
    ) {
        var pressed by remember { mutableStateOf(false) }
        val scale by animateFloatAsState(
            targetValue = if (pressed) 0.97f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessHigh),
            label = "loginScale",
        )
        val bgAlpha by animateFloatAsState(
            targetValue = if (enabled) 1f else 0.32f,
            animationSpec = tween(200),
            label = "loginBgAlpha",
        )

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .scale(scale)
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Accent.copy(alpha = bgAlpha))
                    .then(
                        if (enabled) {
                            Modifier.pointerInput(onClick) {
                                detectTapGestures(
                                    onPress = {
                                        pressed = true
                                        tryAwaitRelease()
                                        pressed = false
                                    },
                                    onTap = { onClick() },
                                )
                            }
                        } else {
                            Modifier
                        },
                    ),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Text(
                    text = text,
                    color = if (enabled) Color.White else TextSecondary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    // 2FA submit button — compact, matches card theme
    @Composable
    private fun TwoFactorSubmitButton(
        enabled: Boolean,
        loading: Boolean,
        onClick: () -> Unit,
    ) {
        var pressed by remember { mutableStateOf(false) }
        val scale by animateFloatAsState(
            targetValue = if (pressed) 0.95f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessHigh),
            label = "2faSubmitScale",
        )
        val bgAlpha by animateFloatAsState(
            targetValue = if (enabled) 0.18f else 0.08f,
            animationSpec = tween(200),
            label = "2faSubmitBg",
        )
        val borderAlpha by animateFloatAsState(
            targetValue = if (enabled) 0.5f else 0.2f,
            animationSpec = tween(200),
            label = "2faSubmitBorder",
        )

        Box(
            modifier =
                Modifier
                    .scale(scale)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Accent.copy(alpha = bgAlpha))
                    .border(1.dp, Accent.copy(alpha = borderAlpha), RoundedCornerShape(10.dp))
                    .padding(horizontal = 28.dp, vertical = 10.dp)
                    .then(
                        if (enabled) {
                            Modifier.pointerInput(onClick) {
                                detectTapGestures(
                                    onPress = {
                                        pressed = true
                                        tryAwaitRelease()
                                        pressed = false
                                    },
                                    onTap = { onClick() },
                                )
                            }
                        } else {
                            Modifier
                        },
                    ),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Accent, strokeWidth = 2.dp)
            } else {
                Text(
                    text = stringResource(R.string.common_ui_submit),
                    color = if (enabled) Accent else TextSecondary.copy(alpha = 0.5f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    // Small action button (matches StoresScreen ActionButton)
    @Composable
    private fun SmallActionButton(
        label: String,
        textColor: Color,
        onClick: () -> Unit,
    ) {
        var pressed by remember { mutableStateOf(false) }
        val scale by animateFloatAsState(
            targetValue = if (pressed) 0.93f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessHigh),
            label = "smallBtnScale",
        )
        Box(
            modifier =
                Modifier
                    .scale(scale)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF222232))
                    .border(1.dp, textColor.copy(alpha = 0.28f), RoundedCornerShape(8.dp))
                    .pointerInput(onClick) {
                        detectTapGestures(
                            onPress = {
                                pressed = true
                                tryAwaitRelease()
                                pressed = false
                            },
                            onTap = { onClick() },
                        )
                    }.padding(horizontal = 12.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = textColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    // Cancel button
    @Composable
    private fun CancelLink(onClick: () -> Unit) {
        var pressed by remember { mutableStateOf(false) }
        val scale by animateFloatAsState(
            targetValue = if (pressed) 0.95f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessHigh),
            label = "cancelScale",
        )
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .scale(scale)
                    .height(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(CardBorder.copy(alpha = 0.5f))
                    .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                    .pointerInput(onClick) {
                        detectTapGestures(
                            onPress = {
                                pressed = true
                                tryAwaitRelease()
                                pressed = false
                            },
                            onTap = { onClick() },
                        )
                    },
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.common_ui_cancel), color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }

    // Dark text field color preset
    @Composable
    private fun darkFieldColors() =
        OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedBorderColor = Accent,
            unfocusedBorderColor = CardBorder,
            disabledBorderColor = CardBorder.copy(alpha = 0.4f),
            focusedLabelColor = Accent,
            unfocusedLabelColor = TextSecondary,
            disabledLabelColor = TextSecondary.copy(alpha = 0.5f),
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            disabledTextColor = TextSecondary,
            cursorColor = Accent,
        )
}
