package dev.thedukerchip.scandy.ui.permission

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
