package com.winlator.cmod.feature.stores.steam.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.winlator.cmod.app.PluviaApp
import com.winlator.cmod.feature.stores.steam.enums.LoginResult
import com.winlator.cmod.feature.stores.steam.enums.LoginScreen
import com.winlator.cmod.feature.stores.steam.events.SteamEvent
import com.winlator.cmod.feature.stores.steam.service.SteamService
import com.winlator.cmod.feature.stores.steam.ui.data.UserLoginState
import com.winlator.cmod.feature.stores.steam.wnsteam.WnAuthenticator
import com.winlator.cmod.runtime.system.SessionKeepAliveService
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.CompletableFuture

class SteamLoginViewModel : ViewModel() {
    private val _loginState = MutableStateFlow(UserLoginState())
    val loginState: StateFlow<UserLoginState> = _loginState.asStateFlow()

    private val _snackEvents = Channel<String>()
    val snackEvents = _snackEvents.receiveAsFlow()

    private val submitChannel = Channel<String>()
    private var credentialLoginJob: Job? = null

    private val authenticator =
        object : WnAuthenticator {
            override fun acceptDeviceConfirmation(): CompletableFuture<Boolean> {
                Timber.tag("SteamLoginViewModel").i("Two-Factor, device confirmation")

                _loginState.update { currentState ->
                    currentState.copy(
                        loginResult = LoginResult.DeviceConfirm,
                        loginScreen = LoginScreen.TWO_FACTOR,
                        isLoggingIn = true,
                        lastTwoFactorMethod = "steam_guard",
                    )
                }

                return CompletableFuture.completedFuture(true)
            }

            override fun getDeviceCode(previousCodeWasIncorrect: Boolean): CompletableFuture<String> {
                Timber.tag("SteamLoginViewModel").d("Two-Factor, device code")

                _loginState.update { currentState ->
                    currentState.copy(
                        loginResult = LoginResult.DeviceAuth,
                        loginScreen = LoginScreen.TWO_FACTOR,
                        isLoggingIn = false,
                        previousCodeIncorrect = previousCodeWasIncorrect,
                        lastTwoFactorMethod = "authenticator_code",
                    )
                }

                return CompletableFuture<String>().apply {
                    viewModelScope.launch {
                        complete(submitChannel.receive())
                    }
                }
            }

            override fun getEmailCode(
                email: String?,
                previousCodeWasIncorrect: Boolean,
            ): CompletableFuture<String> {
                Timber.tag("SteamLoginViewModel").d("Two-Factor, asking for email code")

                _loginState.update { currentState ->
                    currentState.copy(
                        loginResult = LoginResult.EmailAuth,
                        loginScreen = LoginScreen.TWO_FACTOR,
                        isLoggingIn = false,
                        email = email,
                        previousCodeIncorrect = previousCodeWasIncorrect,
                        lastTwoFactorMethod = "email_code",
                    )
                }

                return CompletableFuture<String>().apply {
                    viewModelScope.launch {
                        complete(submitChannel.receive())
                    }
                }
            }
        }

    private val onSteamConnected: (SteamEvent.Connected) -> Unit = {
        Timber.i("Received is connected")
        _loginState.update { currentState ->
            if (it.isAutoLoggingIn) {
                currentState.copy(isLoggingIn = true, isSteamConnected = true)
            } else {
                currentState.copy(isSteamConnected = true)
            }
        }
    }

    private val onLogonStarted: (SteamEvent.LogonStarted) -> Unit = {
        _loginState.update { currentState -> currentState.copy(isLoggingIn = true) }
    }

    private val onLogonEnded: (SteamEvent.LogonEnded) -> Unit = {
        Timber.tag("SteamLoginViewModel").i("Received login result: ${it.loginResult}")
        _loginState.update { currentState ->
            currentState.copy(
                isLoggingIn = false,
                loginResult = it.loginResult,
            )
        }
    }

    private val onQrChallengeReceived: (SteamEvent.QrChallengeReceived) -> Unit = { event ->
        _loginState.update { currentState ->
            currentState.copy(qrCode = event.challengeUrl, isQrFailed = false)
        }
    }

    private val onQrCodeScanned: (SteamEvent.QrCodeScanned) -> Unit = {
        _loginState.update { currentState ->
            currentState.copy(
                qrCode = null,
                isQrFailed = false,
                loginScreen = LoginScreen.TWO_FACTOR,
                loginResult = LoginResult.DeviceConfirm,
                isLoggingIn = true,
                lastTwoFactorMethod = "steam_guard",
            )
        }
    }

    private val onQrAuthEnded: (SteamEvent.QrAuthEnded) -> Unit = {
        _loginState.update { currentState ->
            if (it.success) {
                if (currentState.loginScreen != LoginScreen.TWO_FACTOR) {
                    currentState.copy(
                        qrCode = null,
                        isQrFailed = false,
                        loginScreen = LoginScreen.TWO_FACTOR,
                        loginResult = LoginResult.DeviceConfirm,
                        isLoggingIn = true,
                        lastTwoFactorMethod = "steam_guard",
                    )
                } else {
                    currentState
                }
            } else {
                currentState.copy(
                    isQrFailed = true,
                    qrCode = null,
                    loginScreen = LoginScreen.CREDENTIAL,
                    isLoggingIn = false,
                    lastTwoFactorMethod = null,
                )
            }
        }
    }

    private val onLoggedOut: (SteamEvent.LoggedOut) -> Unit = {
        Timber.tag("SteamLoginViewModel").i("Received logged out")
        _loginState.update {
            it.copy(
                isSteamConnected = false,
                isLoggingIn = false,
                isQrFailed = false,
                loginResult = LoginResult.Failed,
                loginScreen = LoginScreen.CREDENTIAL,
            )
        }
    }

    init {
        SteamService.syncStates()

        PluviaApp.events.on<SteamEvent.Connected, Unit>(onSteamConnected)
        PluviaApp.events.on<SteamEvent.LogonStarted, Unit>(onLogonStarted)
        PluviaApp.events.on<SteamEvent.LogonEnded, Unit>(onLogonEnded)
        PluviaApp.events.on<SteamEvent.QrChallengeReceived, Unit>(onQrChallengeReceived)
        PluviaApp.events.on<SteamEvent.QrCodeScanned, Unit>(onQrCodeScanned)
        PluviaApp.events.on<SteamEvent.QrAuthEnded, Unit>(onQrAuthEnded)
        PluviaApp.events.on<SteamEvent.LoggedOut, Unit>(onLoggedOut)

        viewModelScope.launch {
            SteamService.isLoggedInFlow.collect { loggedIn ->
                if (loggedIn) {
                    _loginState.update { it.copy(loginResult = LoginResult.Success) }
                } else if (_loginState.value.loginResult == LoginResult.Success) {
                    _loginState.update { it.copy(loginResult = LoginResult.Failed) }
                }
            }
        }

        viewModelScope.launch {
            SteamService.isConnectedFlow.collect { connected ->
                _loginState.update { it.copy(isSteamConnected = connected) }
            }
        }
    }

    override fun onCleared() {
        PluviaApp.events.off<SteamEvent.Connected, Unit>(onSteamConnected)
        PluviaApp.events.off<SteamEvent.LogonStarted, Unit>(onLogonStarted)
        PluviaApp.events.off<SteamEvent.LogonEnded, Unit>(onLogonEnded)
        PluviaApp.events.off<SteamEvent.QrChallengeReceived, Unit>(onQrChallengeReceived)
        PluviaApp.events.off<SteamEvent.QrCodeScanned, Unit>(onQrCodeScanned)
        PluviaApp.events.off<SteamEvent.QrAuthEnded, Unit>(onQrAuthEnded)
        PluviaApp.events.off<SteamEvent.LoggedOut, Unit>(onLoggedOut)
        SteamService.stopLoginWithQr()
    }

    fun onCredentialLogin() {
        with(_loginState.value) {
            if (username.isEmpty() || password.isEmpty()) return@with
            credentialLoginJob?.cancel()
            credentialLoginJob =
                viewModelScope.launch {
                    SteamService.startLoginWithCredentials(
                        username = username,
                        password = password,
                        rememberSession = rememberSession,
                        authenticator = authenticator,
                    )
                }
        }
    }

    fun submitTwoFactor() {
        viewModelScope.launch {
            submitChannel.send(
                _loginState.value.twoFactorCode
                    .uppercase()
                    .trim(),
            )
            _loginState.update { it.copy(isLoggingIn = true) }
        }
    }

    fun onShowLoginScreen(loginScreen: LoginScreen) {
        credentialLoginJob?.cancel()
        credentialLoginJob = null

        _loginState.update {
            it.copy(
                loginScreen = loginScreen,
                isLoggingIn = false,
                isQrFailed = false,
                qrCode = null,
                twoFactorCode = "",
                password = "",
            )
        }
        if (loginScreen == LoginScreen.QR) {
            viewModelScope.launch { SteamService.startLoginWithQr() }
        } else {
            SteamService.stopLoginWithQr()
        }
    }

    fun onStartQrLogin() {
        if (_loginState.value.qrCode == null && !_loginState.value.isQrFailed) {
            viewModelScope.launch { SteamService.startLoginWithQr() }
        }
    }

    fun onQrRetry() {
        _loginState.update { it.copy(isQrFailed = false, qrCode = null) }
        viewModelScope.launch { SteamService.startLoginWithQr() }
    }

    fun retryConnection() {
        val context = PluviaApp.instance
        val intent = android.content.Intent(context, SteamService::class.java)
        try {
            context.stopService(intent)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
//                    context.startForegroundService(intent)
                    context.startService(intent)
                    SessionKeepAliveService.startComponent(context, SessionKeepAliveService.COMPONENT_STEAM, "Restarting SteamService in retryConnection")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to restart SteamService in retryConnection")
                    // Safety: unregister if the service failed to even attempt starting
                    SessionKeepAliveService.stopComponent(context, SessionKeepAliveService.COMPONENT_STEAM)
                }
            }, 1000)
        } catch (e: Exception) {
            Timber.e(e, "Error in retryConnection")
        }
    }

    fun setUsername(username: String) {
        _loginState.update { it.copy(username = username) }
    }

    fun setPassword(password: String) {
        _loginState.update { it.copy(password = password) }
    }

    fun setRememberSession(remember: Boolean) {
        _loginState.update { it.copy(rememberSession = remember) }
    }

    fun setTwoFactorCode(code: String) {
        _loginState.update { it.copy(twoFactorCode = code) }
    }
}
