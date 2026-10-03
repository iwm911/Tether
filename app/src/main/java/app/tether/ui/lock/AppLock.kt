package app.tether.ui.lock

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Outcome of one biometric / device-credential prompt. */
sealed interface AuthOutcome {
    data object Success : AuthOutcome
    /** User backed out; no message needed. */
    data object Cancelled : AuthOutcome
    data class Error(val message: String, val unavailable: Boolean = false) : AuthOutcome
}

/**
 * Process-wide app-lock session state. Survives activity recreation; a fresh process always
 * starts locked (when the setting is on). Tether's foreground service usually keeps the process
 * alive, so re-locking on return is what actually protects the app: after the user's chosen
 * delay ([lockAfterSeconds], 0 = as soon as they leave), or after [EXTERNAL_GRACE_MS] when Tether
 * itself opened another screen (image picker, system settings, browser) and the user comes back.
 */
object AppLock {
    /** Choices offered in Settings, in seconds. */
    val LOCK_AFTER_CHOICES = listOf(0, 60, 300)

    /** A screen Tether opened itself (picker, settings page) doesn't count as leaving, up to this long. */
    const val EXTERNAL_GRACE_MS = 5 * 60_000L

    fun lockAfterLabel(seconds: Int): String = when (seconds) {
        0 -> "Immediately"
        60 -> "After 1 minute"
        else -> "After ${seconds / 60} minutes"
    }

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    /** Bumped every time the app re-locks, so the gate knows to auto-prompt again. */
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    private var backgroundedAt: Long? = null
    private var installed = false
    private var lockAfterSeconds: () -> Int = { 0 }

    /** Set by [launchingExternal] just before Tether starts another activity; consumed on the next stop. */
    @Volatile private var externalLaunch = false
    private var leftForExternal = false

    /** While a prompt that leaves the app (device credential on old Android) is up, don't count it as background time. */
    @Volatile private var authenticating = false

    /** Installs the process lifecycle observer once. Must be called on the main thread. */
    fun install(lockAfterSeconds: () -> Int) {
        if (installed) return
        installed = true
        this.lockAfterSeconds = lockAfterSeconds
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (!authenticating) {
                        backgroundedAt = SystemClock.elapsedRealtime()
                        leftForExternal = externalLaunch
                    }
                    externalLaunch = false
                }
                Lifecycle.Event.ON_START -> {
                    val at = backgroundedAt ?: return@LifecycleEventObserver
                    backgroundedAt = null
                    val chosen = lockAfterSeconds() * 1000L
                    val limit = if (leftForExternal) maxOf(chosen, EXTERNAL_GRACE_MS) else chosen
                    if (SystemClock.elapsedRealtime() - at >= limit) lock()
                }
                else -> Unit
            }
        })
    }

    /** Tether is about to open another activity itself (picker, settings, browser): not the user leaving. */
    fun launchingExternal() {
        externalLaunch = true
    }

    fun markUnlocked() {
        _unlocked.value = true
    }

    fun lock() {
        if (_unlocked.value) {
            _unlocked.value = false
            _generation.value = _generation.value + 1
        }
    }

    /** BIOMETRIC_STRONG | DEVICE_CREDENTIAL on API 30+; that combination is unsupported below 30, so fall back to WEAK. */
    val authenticators: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    fun availability(context: Context): Int = BiometricManager.from(context).canAuthenticate(authenticators)

    fun isAvailable(context: Context): Boolean = availability(context) == BiometricManager.BIOMETRIC_SUCCESS

    fun unavailableReason(context: Context): String = when (availability(context)) {
        BiometricManager.BIOMETRIC_SUCCESS -> ""
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Set up a screen lock or fingerprint on this device first."
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "This device has no biometric hardware or screen lock."
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Biometric hardware is unavailable right now. Try again in a moment."
        BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "A security update is required before biometrics can be used."
        else -> "Set up a screen lock on this device to use app lock."
    }

    /** Gives up on a prompt that never showed or whose result was lost, so a new one can start. */
    fun abandon(prompt: BiometricPrompt?) {
        authenticating = false
        prompt?.cancelAuthentication()
    }

    /**
     * Shows the system prompt. [onResult] is delivered on the main thread. Returns the prompt so the
     * caller can cancel it, or null when it couldn't be shown (already reported through [onResult]).
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String? = null,
        onResult: (AuthOutcome) -> Unit,
    ): BiometricPrompt? {
        // biometric 1.1.0 silently drops a request made after onSaveInstanceState (no prompt, no
        // callback), which would leave the caller waiting forever. Report it as a cancel instead.
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || activity.supportFragmentManager.isStateSaved) {
            onResult(AuthOutcome.Cancelled)
            return null
        }
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                authenticating = false
                onResult(AuthOutcome.Success)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                authenticating = false
                val outcome = when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_CANCELED -> AuthOutcome.Cancelled
                    BiometricPrompt.ERROR_LOCKOUT -> AuthOutcome.Error("Too many attempts. Wait a moment, then try again.")
                    BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> AuthOutcome.Error("Biometrics are locked. Unlock your device with its PIN, pattern or password, then try again.")
                    BiometricPrompt.ERROR_NO_BIOMETRICS,
                    BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
                    BiometricPrompt.ERROR_HW_NOT_PRESENT -> AuthOutcome.Error(errString.toString(), unavailable = true)
                    else -> AuthOutcome.Error(errString.toString())
                }
                onResult(outcome)
            }

            override fun onAuthenticationFailed() {
                // A single unrecognised attempt; the system prompt stays up and lets the user retry.
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { if (subtitle != null) setSubtitle(subtitle) }
            .setAllowedAuthenticators(authenticators)
            .setConfirmationRequired(false)
            .build()
        authenticating = true
        return try {
            prompt.authenticate(info)
            prompt
        } catch (e: Exception) {
            authenticating = false
            onResult(AuthOutcome.Error(e.message ?: "Couldn't show the unlock prompt."))
            null
        }
    }
}

/** Walks the ContextWrapper chain to the hosting FragmentActivity (MainActivity). */
fun Context.findFragmentActivity(): FragmentActivity? {
    var c: Context? = this
    while (c != null) {
        if (c is FragmentActivity) return c
        c = (c as? ContextWrapper)?.baseContext
    }
    return null
}
