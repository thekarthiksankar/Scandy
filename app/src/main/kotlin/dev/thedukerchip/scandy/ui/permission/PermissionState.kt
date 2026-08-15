package dev.thedukerchip.scandy.ui.permission

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.thedukerchip.scandy.extensions.openApplicationSettings

enum class PermissionAction { Request, Rationale, OpenSettings }

/**
 * `shouldShowRationale` is `false` both before the permission has ever been requested and after
 * it has been permanently denied — Android does not distinguish the two. `hasAsked` breaks the
 * tie: `false` on the first rationale, so the app explains itself before re-requesting; `true`
 * on the second, so the app sends the user to Settings instead of requesting again.
 */
fun nextAction(isGranted: Boolean, shouldShowRationale: Boolean, hasAsked: Boolean): PermissionAction =
    when {
        isGranted -> PermissionAction.Request
        !shouldShowRationale -> PermissionAction.Request
        !hasAsked -> PermissionAction.Rationale
        else -> PermissionAction.OpenSettings
    }

@Stable
class PermissionState internal constructor(
    val isGranted: Boolean,
    val shouldShowRationale: Boolean,
    private val onRequest: () -> Unit,
) {
    fun request() = onRequest()
}

/**
 * Re-reads the permission on [Lifecycle.Event.ON_START], not just after the in-app request
 * launcher returns — the same guarantee [dev.thedukerchip.scandy.ui.scanner.ScannerActivity]
 * used to get from reading the permission before `super.onStart()`. Without it, granting the
 * permission from system Settings and returning to the app would not be picked up until a
 * process restart.
 */
@Composable
fun rememberPermissionState(permission: String): PermissionState {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isGranted by remember { mutableStateOf(context.isPermissionGranted(permission)) }
    var hasAsked by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> isGranted = granted }

    DisposableEffect(lifecycleOwner, permission) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                isGranted = context.isPermissionGranted(permission)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val shouldShowRationale = activity?.shouldShowRequestPermissionRationale(permission) ?: false

    return PermissionState(
        isGranted = isGranted,
        shouldShowRationale = shouldShowRationale,
        onRequest = {
            when (nextAction(isGranted, shouldShowRationale, hasAsked)) {
                PermissionAction.Request -> launcher.launch(permission)
                PermissionAction.Rationale -> hasAsked = true
                PermissionAction.OpenSettings -> context.openApplicationSettings()
            }
        },
    )
}

private fun Context.isPermissionGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
