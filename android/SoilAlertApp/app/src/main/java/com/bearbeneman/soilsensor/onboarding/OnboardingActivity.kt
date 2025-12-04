package com.bearbeneman.soilsensor.onboarding

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.bearbeneman.soilsensor.MainActivity
import com.bearbeneman.soilsensor.R
import com.bearbeneman.soilsensor.data.OnboardingPrefs
import com.bearbeneman.soilsensor.databinding.ActivityOnboardingBinding
import com.google.android.material.tabs.TabLayoutMediator

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    private val pages = listOf(
        OnboardingPage(
            R.string.onboarding_title_notifications,
            R.string.onboarding_desc_notifications,
            R.drawable.ic_notification
        ),
        OnboardingPage(
            R.string.onboarding_title_network,
            R.string.onboarding_desc_network,
            R.drawable.ic_wifi_signal
        ),
        OnboardingPage(
            R.string.onboarding_title_subscription,
            R.string.onboarding_desc_subscription,
            R.drawable.ic_raw
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.viewPager.adapter = OnboardingPagerAdapter(pages)
        TabLayoutMediator(binding.tabDots, binding.viewPager) { _, _ -> }.attach()

        binding.skipButton.setOnClickListener { completeOnboarding() }
        binding.nextButton.setOnClickListener {
            if (binding.viewPager.currentItem == pages.lastIndex) {
                completeOnboarding()
            } else {
                binding.viewPager.currentItem = binding.viewPager.currentItem + 1
            }
        }

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val text = if (position == pages.lastIndex) {
                    R.string.onboarding_finish
                } else {
                    R.string.onboarding_next
                }
                binding.nextButton.setText(text)
            }
        })
    }

    private fun completeOnboarding() {
        OnboardingPrefs.setCompleted(this, true)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

