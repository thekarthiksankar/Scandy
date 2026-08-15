package dev.thedukerchip.scandy.ui.scanner

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.thedukerchip.scandy.ui.BaseActivity
import dev.thedukerchip.scandy.ui.about.AboutActivity
import dev.thedukerchip.scandy.ui.display.DisplayActivity
import dev.thedukerchip.scandy.ui.theme.ScandyTheme

const val CODE = "code"

class ScannerActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScandyTheme {
                ScannerScreen(
                    onAboutClick = { startActivity(Intent(this, AboutActivity::class.java)) },
                    onBarcode = { code ->
                        startActivity(Intent(this, DisplayActivity::class.java).putExtra(CODE, code))
                    },
                )
            }
        }
    }
}
