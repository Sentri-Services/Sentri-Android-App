package com.sentri.nativeapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sentri.nativeapp.DiagnosticLog
import com.sentri.nativeapp.auth.AuthRepository
import com.sentri.nativeapp.auth.AuthUnavailable
import com.sentri.nativeapp.auth.LoginRejected
import com.sentri.nativeapp.auth.SessionExpired
import com.sentri.nativeapp.network.AccountContext
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.FarmChoice
import com.sentri.nativeapp.network.FarmRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class Page { LOGIN, FARMS, ACCOUNT }
enum class Failure { NETWORK, AUTHENTICATION, LOGIN, BUSINESS, UNKNOWN }
data class UiFailure(val kind: Failure, val detail: String? = null)
data class ConnectionUiState(
    val page: Page = Page.LOGIN,
    val busy: Boolean = false,
    val failure: UiFailure? = null,
    val farms: List<FarmChoice> = emptyList(),
    val account: AccountContext? = null,
    val webUrl: String? = null,
    val loggingOut: Boolean = false,
)

class ConnectionViewModel(private val auth: AuthRepository, private val farms: FarmRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(ConnectionUiState())
    val state = mutableState.asStateFlow()
    private var forcePrompt = false

    init {
        auth.pendingLogin?.let { mutableState.value = ConnectionUiState(webUrl = it.url) }
            ?: refresh()
    }

    fun login() = task {
        val request = auth.beginLogin(forcePrompt)
        mutableState.value = mutableState.value.copy(webUrl = request.url, loggingOut = false)
    }

    fun callback(url: String) {
        if (state.value.loggingOut) closeWeb() else task {
            mutableState.value = mutableState.value.copy(webUrl = null)
            auth.completeLogin(url)
            forcePrompt = false
            loadContext()
        }
    }

    fun closeWeb() = task {
        auth.cancelLogin()
        mutableState.value = mutableState.value.copy(webUrl = null, loggingOut = false)
    }

    fun webFailure() = task {
        auth.cancelLogin()
        mutableState.value = mutableState.value.copy(webUrl = null, loggingOut = false)
        throw AuthUnavailable()
    }

    fun refresh() = task { loadContext() }

    fun selectFarm(choice: FarmChoice) = task {
        val current = auth.session.value ?: throw SessionExpired()
        require(choice.oauthId == current.oauthId && state.value.farms.contains(choice))
        auth.clearFarm()
        mutableState.value = mutableState.value.copy(account = null, page = Page.FARMS)
        farms.choose(choice)
        auth.refreshForFarm(choice.farmId)
        loadContext()
    }

    fun switchFarm() = task {
        auth.clearFarm()
        mutableState.value = mutableState.value.copy(account = null)
        loadContext(autoSelect = false)
    }

    fun signOut() = task {
        mutableState.value = ConnectionUiState(busy = true)
        val url = auth.signOut()
        DiagnosticLog.event("local_session_cleared")
        forcePrompt = true
        mutableState.value = ConnectionUiState(webUrl = url, loggingOut = url != null)
    }

    private suspend fun loadContext(autoSelect: Boolean = true) {
        val session = auth.session.value
        if (session == null) {
            mutableState.value = ConnectionUiState(busy = true)
            return
        }
        if (session.farmId != null) {
            mutableState.value = mutableState.value.copy(page = Page.ACCOUNT, farms = emptyList())
            val account = farms.account()
            // Never display an account whose backend farm differs from the persisted selection.
            if (account.farm.id != session.farmId) {
                auth.clearFarm()
                mutableState.value = mutableState.value.copy(account = null, page = Page.FARMS)
                throw ApiFailure.Protocol()
            }
            mutableState.value = mutableState.value.copy(account = account)
            DiagnosticLog.event("native_account_query_succeeded")
        } else {
            mutableState.value = mutableState.value.copy(page = Page.FARMS, account = null)
            val choices = farms.choices(session.oauthId)
            mutableState.value = mutableState.value.copy(farms = choices)
            if (autoSelect && choices.size == 1) {
                val only = choices.single()
                farms.choose(only)
                auth.refreshForFarm(only.farmId)
                loadContext(autoSelect = false)
            }
        }
    }

    private fun task(action: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true, failure = null)
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val failure = when (error) {
                    is SessionExpired -> UiFailure(Failure.AUTHENTICATION)
                    is AuthUnavailable, is ApiFailure.Network -> UiFailure(Failure.NETWORK)
                    is ApiFailure.Authentication -> UiFailure(Failure.AUTHENTICATION)
                    is LoginRejected -> UiFailure(Failure.LOGIN)
                    is ApiFailure.Business -> UiFailure(Failure.BUSINESS, error.detail.take(240))
                    else -> UiFailure(Failure.UNKNOWN)
                }
                DiagnosticLog.event("failure_${failure.kind.name.lowercase()}")
                if (auth.session.value == null) {
                    forcePrompt = failure.kind == Failure.AUTHENTICATION
                    mutableState.value = ConnectionUiState(failure = failure)
                } else mutableState.value = mutableState.value.copy(failure = failure)
            } finally {
                mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }
}
