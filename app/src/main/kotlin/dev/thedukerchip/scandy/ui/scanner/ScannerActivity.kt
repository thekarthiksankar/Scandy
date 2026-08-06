package dev.thedukerchip.scandy.ui.scanner

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.thedukerchip.scandy.camera.FlashStatus
import dev.thedukerchip.scandy.camera.ScandyBarcode
import dev.thedukerchip.scandy.camera.Scanner
import dev.thedukerchip.scandy.extensions.gone
import dev.thedukerchip.scandy.extensions.setOnClickListener
import dev.thedukerchip.scandy.extensions.visible
import dev.thedukerchip.scandy.permissions.PermissionResult
import dev.thedukerchip.scandy.permissions.permissionWrapperFor
import dev.thedukerchip.scandy.ui.BaseActivity
import dev.thedukerchip.scandy.ui.about.AboutActivity
import dev.thedukerchip.scandy.ui.display.DisplayActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import scandy.R
import scandy.databinding.ActivityScannerBinding

const val CODE = "code"

class ScannerActivity : BaseActivity() {

    private lateinit var binding: ActivityScannerBinding

    private val scanner by lazy { Scanner(applicationContext) }

    private val cameraAccess = MutableStateFlow(false)

    private val onPermissionResult = PermissionResult { accepted ->
        cameraAccess.value = accepted
    }
    private val cameraPermission =
        permissionWrapperFor(Manifest.permission.CAMERA, onPermissionResult)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)

        binding = ActivityScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar?.apply {
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
                v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    topMargin = insets.top
                }
                WindowInsetsCompat.CONSUMED
            }
        }

        initViews()
        observeCameraAccess()

        if (!cameraPermission.isGranted) requestCameraAccess()
    }

    /**
     * Read before [super.onStart] so the scanning loop sees the current answer the moment it
     * re-enters STARTED — a permission granted from system settings does not recreate us.
     */
    override fun onStart() {
        cameraAccess.value = cameraPermission.isGranted
        super.onStart()
    }

    private fun initViews() {
        binding.scannerGrp.gone()

        setFlashStatus(FlashStatus.Unavailable)
        binding.cameraAccessBtn.setOnClickListener(::requestCameraAccess)
        binding.torchBtn.setOnClickListener {
            if (scanner.flash.value == FlashStatus.Unavailable) {
                Toast.makeText(this, R.string.info_flash_unavailable, Toast.LENGTH_SHORT).show()
            } else {
                scanner.toggleFlash()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                scanner.flash.collectLatest { setFlashStatus(it) }
            }
        }

        binding.toolbar?.setOnMenuItemClickListener {
            if (it.itemId == R.id.item_about) {
                onClickAbout()
                true
            } else false
        }
    }

    private fun setFlashStatus(status: FlashStatus) {
        with(binding.torchBtn) {
            backgroundTintList = ColorStateList.valueOf(getColor(status.bgTint()))
            setImageDrawable(ContextCompat.getDrawable(context, status.icon()))
        }
    }

    private fun onClickAbout() {
        startActivity(Intent(this, AboutActivity::class.java))
    }

    private fun requestCameraAccess() {
        cameraPermission.request()
    }

    /**
     * The single entry point into the camera: while this screen is visible and holds camera
     * access, scan into the viewfinder, then show the code.
     *
     * One collector means one [Scanner.scan] in flight — [collectLatest] cancels and joins the
     * previous scan before starting the next, and [repeatOnLifecycle] cancels it on STOP.
     */
    private fun observeCameraAccess() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                cameraAccess.collectLatest { granted ->
                    if (granted) startScanning() else askForCameraAccess()
                }
            }
        }
    }

    private suspend fun startScanning() {
        makeScannerVisible()

        runCatching {
            scanner.scan(
                viewFinder = binding.barcodeFinderPv.surfaceProvider,
                owner = this@ScannerActivity
            )
        }.onFailure {
            // never swallow cancellation; failures are already published on scanner.status
            if (it is CancellationException) throw it
        }.onSuccess {
            onBarcodeDetected(it)
        }
    }

    private fun onBarcodeDetected(code: ScandyBarcode) {
        startActivity(Intent(this, DisplayActivity::class.java).also {
            it.putExtra(CODE, code)
        })
    }

    private fun makeScannerVisible() {
        binding.permissionInfoGrp.gone()
        binding.scannerGrp.visible()
    }

    private fun askForCameraAccess() {
        binding.scannerGrp.gone()
        binding.permissionInfoGrp.visible()
    }
}