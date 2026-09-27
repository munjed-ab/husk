package com.munjed.husk

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.munjed.husk.data.Constants
import com.munjed.husk.data.Prefs
import com.munjed.husk.databinding.ActivityMainBinding
import com.munjed.husk.helper.applyHomeBackground
import com.munjed.husk.helper.getColorFromAttr
import com.munjed.husk.helper.hasBeenDays
import com.munjed.husk.helper.hasBeenMinutes
import com.munjed.husk.helper.isDarkThemeOn
import com.munjed.husk.helper.isDaySince
import com.munjed.husk.helper.isDefaultLauncher
import com.munjed.husk.helper.isEinkDisplay
import com.munjed.husk.helper.isHuskDefault
import com.munjed.husk.helper.nextQuote
import com.munjed.husk.helper.isTablet
import com.munjed.husk.helper.openUrl
import com.munjed.husk.helper.inflateFallbackView
import com.munjed.husk.helper.resetLauncherViaFakeActivity
import com.munjed.husk.helper.setScriptTypeface
import com.munjed.husk.helper.showLauncherSelector
import com.munjed.husk.helper.showToast
import com.munjed.husk.helper.syncAppBlocker
import com.munjed.husk.ui.AppDrawerAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var navController: NavController
    private lateinit var viewModel: MainViewModel
    private lateinit var binding: ActivityMainBinding
    private var timerJob: Job? = null
    private var isResumed = false
    private var profileReceiver: BroadcastReceiver? = null

    // Outlives each drawer visit, so app rows are inflated once instead of on every swipe up.
    // Lives here and not in the ViewModel: rows hold this activity's themed context.
    val appRowPool = RecyclerView.RecycledViewPool().apply {
        setMaxRecycledViews(AppDrawerAdapter.VIEW_TYPE_APP, 40)
    }

//    override fun onBackPressed() {
//        if (navController.currentDestination?.id != R.id.mainFragment)
//            super.onBackPressed()
//    }

    override fun attachBaseContext(context: Context) {
        val newConfig = Configuration(context.resources.configuration)
        newConfig.fontScale = Prefs(context).textSizeScale
        applyOverrideConfiguration(newConfig)
        super.attachBaseContext(context)
    }

    // applies the chosen font pair to every TextView (and its subclasses) as it inflates, static
    // strings included, so the picker in settings does not need a call site in every screen
    override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? {
        val view = super.onCreateView(parent, name, context, attrs) ?: inflateFallbackView(context, name, attrs)
        if (view is TextView) view.setScriptTypeface()
        return view
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        if (isEinkDisplay()) prefs.appTheme = AppCompatDelegate.MODE_NIGHT_NO
        AppCompatDelegate.setDefaultNightMode(prefs.appTheme)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        navController = this.findNavController(R.id.nav_host_fragment)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        // the launcher starts at boot, so this is where blocked apps get cut off again
        syncAppBlocker()

        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (navController.currentDestination?.id != R.id.mainFragment) {
                    // then we might want to finish the activity or disable this callback.
                    if (navController.popBackStack()) {
                        // Successfully popped back
                    } else {
                        // if you want other system/activity level handling
                    }
                } else {
                    binding.messageLayout.visibility = View.GONE
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        if (prefs.firstOpen) {
            viewModel.firstOpen(true)
            prefs.firstOpen = false
            prefs.firstOpenTime = System.currentTimeMillis()
            viewModel.setDefaultClockApp()
            viewModel.resetLauncherLiveData.call()
        }

        initClickListeners()
        initObservers(viewModel)
        viewModel.getAppList()
        setupOrientation()

        window.addFlags(FLAG_LAYOUT_NO_LIMITS)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            profileReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    viewModel.isPrivateSpaceToggling = false
                    viewModel.getPrivateSpaceAppList()
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
            }
            registerReceiver(profileReceiver, filter)
        }
    }

    override fun onStart() {
        super.onStart()
        // onStart, not onResume: a new quote per unlock or return from an app, not per drawer visit
        nextQuote(prefs)
        checkTheme()
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        viewModel.isPrivateSpaceToggling = false
        viewModel.awaitingPicker = false
        refreshBackground()
    }

    fun refreshBackground() {
        applyHomeBackground(binding.mainActivityLayout, binding.appBackground, prefs)
    }

    override fun onStop() {
        isResumed = false
        backToHomeScreen()
        super.onStop()
    }

    override fun onUserLeaveHint() {
        backToHomeScreen()
        super.onUserLeaveHint()
    }

    override fun onNewIntent(intent: Intent?) {
        // Home button for recents feature disabled
        // val alreadyHome = navController.currentDestination?.id == R.id.mainFragment
        viewModel.awaitingPicker = false // Home always means home, picker or not
        backToHomeScreen()
        // if (alreadyHome && isResumed && prefs.homeButtonShowRecents)
        //     viewModel.showRecentApps.call()
        super.onNewIntent(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppCompatDelegate.setDefaultNightMode(prefs.appTheme)
    }

    private fun initClickListeners() {
        binding.ivClose.setOnClickListener {
            binding.messageLayout.visibility = View.GONE
        }
    }

    private fun initObservers(viewModel: MainViewModel) {
        viewModel.launcherResetFailed.observe(this) {
            openLauncherChooser(it)
        }
        viewModel.resetLauncherLiveData.observe(this) {
            if (isDefaultLauncher() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
                resetLauncherViaFakeActivity()
            else
                showLauncherSelector(Constants.REQUEST_CODE_LAUNCHER_SELECTOR)
        }
        viewModel.checkForMessages.observe(this) {
            checkForMessages()
        }
        viewModel.showDialog.observe(this) {
            when (it) {
                Constants.Dialog.ABOUT -> {
                    showMessageDialog(R.string.app_name, R.string.welcome_to_settings, R.string.okay) {
                        binding.messageLayout.visibility = View.GONE
                    }
                }

                Constants.Dialog.HIDDEN -> {
                    showMessageDialog(R.string.hidden_apps, R.string.hidden_apps_message, R.string.okay) {
                    }
                }

                Constants.Dialog.KEYBOARD -> {
                    showMessageDialog(R.string.app_name, R.string.keyboard_message, R.string.okay) {
                    }
                }

                Constants.Dialog.DIGITAL_WELLBEING -> {
                    showMessageDialog(R.string.screen_time, R.string.app_usage_message, R.string.permission) {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                }

            }
        }
    }

    private fun showMessageDialog(title: Int, message: Int, action: Int, clickListener: () -> Unit) {
        binding.tvTitle.text = getString(title)
        binding.tvMessage.text = getString(message)
        binding.tvAction.text = getString(action)
        binding.tvAction.setOnClickListener {
            clickListener()
            binding.messageLayout.visibility = View.GONE
        }
        binding.messageLayout.visibility = View.VISIBLE
    }

    private fun checkForMessages() {
        if (prefs.firstOpenTime == 0L)
            prefs.firstOpenTime = System.currentTimeMillis()

        val calendar = Calendar.getInstance()
        val dayOfYear = calendar.get(Calendar.DAY_OF_YEAR)
        if (dayOfYear == 1 && dayOfYear != prefs.shownOnDayOfYear) {
            prefs.shownOnDayOfYear = dayOfYear
            showMessageDialog(R.string.hey, R.string.new_year_wish, R.string.cheers) {}
            return
        } else if (dayOfYear == 32 && dayOfYear != prefs.shownOnDayOfYear) {
            prefs.shownOnDayOfYear = dayOfYear
            showMessageDialog(R.string.hey, R.string.new_year_wish_1, R.string.cheers) {}
            return
        }

        when (prefs.userState) {
            Constants.UserState.START -> Unit
        }
    }

    @SuppressLint("SourceLockedOrientationActivity")
    fun setupOrientation() {
        // In Android 8.0, windowIsTranslucent cannot be used with a fixed screenOrientation
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.O) return
        requestedOrientation = when (prefs.screenOrientation) {
            Constants.Orientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            Constants.Orientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun backToHomeScreen() {
        if (viewModel.isPrivateSpaceToggling || viewModel.awaitingPicker) return
        binding.messageLayout.visibility = View.GONE
        if (navController.currentDestination?.id != R.id.mainFragment)
            navController.popBackStack(R.id.mainFragment, false)
    }

    private fun openLauncherChooser(resetFailed: Boolean) {
        if (resetFailed) {
            val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            startActivity(intent)
        }
    }

    private fun checkTheme() {
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            delay(200)
            if ((prefs.appTheme == AppCompatDelegate.MODE_NIGHT_YES && getColorFromAttr(R.attr.primaryColor) != getColor(R.color.white))
                || (prefs.appTheme == AppCompatDelegate.MODE_NIGHT_NO && getColorFromAttr(R.attr.primaryColor) != getColor(R.color.black))
            )
                recreate()
        }
    }

    override fun onDestroy() {
        profileReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            Constants.REQUEST_CODE_ENABLE_ADMIN -> {
                if (resultCode == Activity.RESULT_OK)
                    prefs.lockModeOn = true
            }

            Constants.REQUEST_CODE_LAUNCHER_SELECTOR -> {
                if (resultCode == Activity.RESULT_OK)
                    resetLauncherViaFakeActivity()
            }
        }
    }
}