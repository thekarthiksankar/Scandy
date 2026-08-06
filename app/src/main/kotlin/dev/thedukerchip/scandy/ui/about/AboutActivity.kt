package dev.thedukerchip.scandy.ui.about

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import dev.thedukerchip.scandy.ui.BaseActivity
import scandy.databinding.ActivityAboutBinding

class AboutActivity : BaseActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.publisherLogoImv.apply {
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
                v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    topMargin = insets.top
                }
                WindowInsetsCompat.CONSUMED
            }
        }

        binding.privacyPolicyLinkTv.movementMethod = LinkMovementMethod.getInstance()
    }
}