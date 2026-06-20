package dev.thedukerchip.scandy.ui.display

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
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
        if (browseIntent.resolveActivity(packageManager) != null) {
            if (url != null) {
                binding.displayValue.text = url
                binding.browse.show()
                binding.browse.setOnClickListener {
                    startActivity(browseIntent)
                }
                startActivity(browseIntent)
            }
        }
    }
}