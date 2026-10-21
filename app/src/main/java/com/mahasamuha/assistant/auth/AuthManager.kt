package com.mahasamuha.assistant.auth

import android.content.Context
import android.content.Intent
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Log
import com.mahasamuha.assistant.AppConfig
import com.mahasamuha.assistant.voice.TAG
import javax.crypto.AEADBadTagException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse

class NotSignedInException : Exception("Not signed in")

class SignInException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Sign-in with authorization code + PKCE (AppAuth, Custom Tab), and fresh access tokens on demand.
 * Refresh tokens rotate on use, so the state is persisted after every refresh.
 */
class AuthManager(context: Context, private val config: AppConfig, private val store: TokenStore) {
    private val service = AuthorizationService(context)
    private val mutex = Mutex()
    private var state: AuthState? = null

    private val _signedIn = MutableStateFlow(store.exists())
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    /** The intent to launch for sign-in; pass its result to [completeSignIn]. */
    suspend fun signInIntent(): Intent {
        val serviceConfig = suspendCancellableCoroutine { cont ->
            AuthorizationServiceConfiguration.fetchFromIssuer(config.issuer) { result, ex ->
                if (result != null) cont.resume(result)
                else cont.resumeWithException(SignInException("Couldn't reach the identity provider", ex))
            }
        }
        val request = AuthorizationRequest.Builder(serviceConfig, config.clientId, ResponseTypeValues.CODE, config.redirectUri)
            .setScopes("openid", "offline_access")
            .build()
        return service.getAuthorizationRequestIntent(request)
    }

    suspend fun completeSignIn(data: Intent?) = mutex.withLock {
        val response = data?.let(AuthorizationResponse::fromIntent)
            ?: throw SignInException(data?.let(AuthorizationException::fromIntent)?.errorDescription ?: "Sign-in cancelled")
        val newState = AuthState(response, null)
        newState.update(exchange(response.createTokenExchangeRequest()), null)
        persist(newState)
        _signedIn.value = true
    }

    /** A valid access token, refreshed if needed. Throws [NotSignedInException] if the user must sign in again. */
    suspend fun freshAccessToken(): String = mutex.withLock {
        val current = loadState() ?: throw NotSignedInException()
        val token = try {
            suspendCancellableCoroutine<String> { cont ->
                current.performActionWithFreshTokens(service) { accessToken, _, ex ->
                    when {
                        accessToken != null -> cont.resume(accessToken)
                        ex != null -> cont.resumeWithException(ex)
                        else -> cont.resumeWithException(IllegalStateException("No access token and no error"))
                    }
                }
            }
        } catch (e: AuthorizationException) {
            if (e.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR) {
                Log.w(TAG, "Refresh token rejected (${e.error}); signing out")
                clearLocked()
                throw NotSignedInException()
            }
            throw e
        }
        persist(current) // keeps a rotated refresh token
        token
    }

    suspend fun signOut() = mutex.withLock { clearLocked() }

    fun keySecurityLevel(): String = store.keySecurityLevel()

    private suspend fun loadState(): AuthState? {
        state?.let { return it }
        val json = try {
            withContext(Dispatchers.IO) { store.load() }
        } catch (e: Exception) {
            if (e is KeyPermanentlyInvalidatedException || e is AEADBadTagException) {
                Log.w(TAG, "Stored auth state unreadable; signing out", e)
                clearLocked()
                return null
            }
            throw e // e.g. device locked: not a reason to forget the token
        }
        return json?.let(AuthState::jsonDeserialize)?.also { state = it }
    }

    private suspend fun persist(newState: AuthState) {
        withContext(Dispatchers.IO) { store.save(newState.jsonSerializeString()) }
        state = newState
    }

    private fun clearLocked() {
        store.clear()
        state = null
        _signedIn.value = false
    }

    private suspend fun exchange(request: TokenRequest): TokenResponse = suspendCancellableCoroutine { cont ->
        service.performTokenRequest(request) { response, ex ->
            if (response != null) cont.resume(response)
            else cont.resumeWithException(SignInException(ex?.errorDescription ?: "Token exchange failed", ex))
        }
    }
}
