package com.whixprs.phonelock

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-screen black lock shown on top of a protected app. The protected app
 * stays hidden behind it until the fingerprint check succeeds.
 */
class LockActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE = "package"
    }

    private var targetPackage: String? = null
    private var promptActive = false
    private lateinit var message: TextView
    private lateinit var retry: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_lock)
        message = findViewById(R.id.message)
        retry = findViewById(R.id.retryButton)
        retry.setOnClickListener { startAuth() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        if (pkg == null) {
            finish()
            return
        }
        if (pkg == targetPackage && promptActive) return
        targetPackage = pkg
        val pm = packageManager
        val label = try {
            val info = pm.getApplicationInfo(pkg, 0)
            findViewById<ImageView>(R.id.appIcon).setImageDrawable(pm.getApplicationIcon(info))
            pm.getApplicationLabel(info).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
        findViewById<TextView>(R.id.appName).text = label
        startAuth()
    }

    private fun startAuth() {
        val pkg = targetPackage ?: return
        if (!Auth.canAuthenticate(this)) {
            message.setText(R.string.no_biometrics)
            retry.setText(android.R.string.ok)
            retry.visibility = View.VISIBLE
            retry.setOnClickListener {
                startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        message.text = ""
        retry.visibility = View.INVISIBLE
        val label = findViewById<TextView>(R.id.appName).text
        promptActive = true
        Auth.prompt(
            this,
            getString(R.string.unlock_title, label),
            getString(R.string.unlock_subtitle),
            onSuccess = {
                promptActive = false
                AppGuardService.markUnlocked(pkg)
                finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            },
            onError = { code, msg ->
                promptActive = false
                if (Auth.isUserCancel(code)) {
                    goHome()
                } else {
                    message.text = msg
                    retry.visibility = View.VISIBLE
                }
            },
        )
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }
}
