package org.tensorflow.lite.examples.objectdetection

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.tensorflow.lite.examples.objectdetection.databinding.ActivitySplashBinding

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySplashBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.apply {
            lottieAnimationView.setAnimation("Animation Splash.json")
            lottieAnimationView.loop(true)
            lottieAnimationView.playAnimation()
        }

        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)

        /*
        Handler(Looper.getMainLooper()).postDelayed({

            finish()
        }, 5000)
        */


    }
}