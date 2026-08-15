package dev.thedukerchip.scandy.ui.scanner

import android.Manifest
import android.content.res.Configuration
import android.widget.Toast
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.SurfaceRequest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.thedukerchip.scandy.camera.FlashStatus
import dev.thedukerchip.scandy.camera.ScandyBarcode
import dev.thedukerchip.scandy.ui.permission.rememberPermissionState
import kotlinx.coroutines.CancellationException
import scandy.R

/**
 * Navigation is hoisted callbacks — never an Activity, never a `NavController` — so the
 * eventual nav-compose step is a call-site-only change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    onAboutClick: () -> Unit,
    onBarcode: (ScandyBarcode) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScannerViewModel = viewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    val flash by viewModel.flash.collectAsStateWithLifecycle()

    LaunchedEffect(cameraPermission.isGranted, lifecycleOwner) {
        if (!cameraPermission.isGranted) return@LaunchedEffect

        // Restart the scan on every re-entry into STARTED (e.g. returning from the display
        // screen after a code is found) instead of only once per key change, and drop the
        // stale surface so the viewfinder doesn't freeze on the last captured frame.
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            surfaceRequest = null

            runCatching {
                viewModel.scanner.scan(
                    viewFinder = { request -> surfaceRequest = request },
                    owner = lifecycleOwner,
                )
            }.onFailure {
                // never swallow cancellation; failures are already published on scanner.status
                if (it is CancellationException) throw it
            }.onSuccess(onBarcode)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            if (!isLandscape) {
                ScannerTopBar(onAboutClick = onAboutClick)
            }
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            if (cameraPermission.isGranted) {
                surfaceRequest?.let { request ->
                    CameraXViewfinder(request, modifier = Modifier.fillMaxSize())
                }

                FlashFab(
                    flash = flash,
                    isLandscape = isLandscape,
                    onClick = {
                        if (flash == FlashStatus.Unavailable) {
                            Toast.makeText(context, R.string.info_flash_unavailable, Toast.LENGTH_SHORT).show()
                        } else {
                            viewModel.toggleFlash()
                        }
                    },
                    modifier = Modifier.align(if (isLandscape) Alignment.CenterEnd else Alignment.BottomCenter),
                )
            } else {
                CameraPermissionPrompt(
                    onTurnOnClick = cameraPermission::request,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.8f),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScannerTopBar(onAboutClick: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        actions = {
            IconButton(onClick = onAboutClick) {
                Icon(
                    painter = painterResource(R.drawable.ic_info),
                    contentDescription = stringResource(R.string.menu_item_about),
                )
            }
        },
    )
}

@Composable
private fun FlashFab(
    flash: FlashStatus,
    isLandscape: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.padding(
            end = if (isLandscape) 56.dp else 0.dp,
            bottom = if (isLandscape) 0.dp else 56.dp,
        ),
        containerColor = colorResource(flash.bgTint()),
        elevation = FloatingActionButtonDefaults.elevation(0.dp),
    ) {
        Icon(
            painter = painterResource(flash.icon()),
            contentDescription = stringResource(R.string.cd_torch_btn),
            tint = Color.Black,
        )
    }
}

@Composable
private fun CameraPermissionPrompt(
    onTurnOnClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.title_camera_permission),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(
                bottom = dimensionResource(R.dimen.permission_title_spacing),
            ),
        )
        Text(
            text = stringResource(R.string.info_camera_permission),
            textAlign = TextAlign.Center,
        )
        TextButton(
            onClick = onTurnOnClick,
            modifier = Modifier.padding(
                top = dimensionResource(R.dimen.permission_action_spacing),
            ),
        ) {
            Text(stringResource(R.string.action_turn_on))
        }
    }
}
