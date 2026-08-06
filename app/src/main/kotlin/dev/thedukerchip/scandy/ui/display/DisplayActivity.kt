package dev.thedukerchip.scandy.ui.display

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import dev.thedukerchip.scandy.camera.ScandyBarcode
import dev.thedukerchip.scandy.extensions.copyToClipboard
import dev.thedukerchip.scandy.ui.BaseActivity
import dev.thedukerchip.scandy.ui.scanner.CODE
import scandy.databinding.ActivityDisplayBinding


class DisplayActivity : BaseActivity() {

    private lateinit var binding: ActivityDisplayBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDisplayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.apply {
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
                v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    topMargin = insets.top
                }
                WindowInsetsCompat.CONSUMED
            }
        }

        initViews()
    }

    private fun initViews() {
        binding.copy.setOnClickListener {
            copyToClipboard("Scanned text", binding.displayValue.text.toString())
        }

        val code = IntentCompat.getSerializableExtra(intent, CODE, ScandyBarcode::class.java)
        if (code != null) {
            when (code) {
                is ScandyBarcode.Link -> {
                    showBrowse(code.url)
                    Log.i("Display", "Link: ${code.url}")
                }
                is ScandyBarcode.Text -> {
                    binding.displayValue.text = code.text
                    Log.i("Display", "Text: ${code.text}")
                }
                ScandyBarcode.Unknown -> {
                    Log.w("Display", "Unknown")
                }
            }
        } else {
            Log.e("Display", "Invalid details")
        }
    }

    private fun showBrowse(url: String?) {
        val browseIntent = Intent(Intent.ACTION_VIEW, url?.toUri())
        val isResolved = packageManager
            .queryIntentActivities(browseIntent, PackageManager.MATCH_ALL)
            .isNotEmpty()

        if (isResolved && url != null) {
            binding.displayValue.text = url
            binding.browse.show()
            binding.browse.setOnClickListener {
                startActivity(browseIntent)
            }
            startActivity(browseIntent)
        }

        // TODO Handle not resolved scenario
        // TODO Handle url empty scenario
    }
}