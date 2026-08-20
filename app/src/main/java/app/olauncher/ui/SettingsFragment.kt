package app.olauncher.ui

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import app.olauncher.BuildConfig
import app.olauncher.MainActivity
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentSettingsBinding
import androidx.lifecycle.lifecycleScope
import app.olauncher.helper.animateAlpha
import app.olauncher.helper.appUsagePermissionGranted
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.isAccessServiceEnabled
import app.olauncher.helper.isNotificationAccessGranted
import app.olauncher.helper.isDarkThemeOn
import app.olauncher.helper.isEinkDisplay
import app.olauncher.helper.isOlauncherDefault
import app.olauncher.helper.isTablet
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.openUrl
import app.olauncher.helper.refreshReadingList
import kotlinx.coroutines.launch
import app.olauncher.helper.setPlainWallpaper
import app.olauncher.helper.showToast
import app.olauncher.listener.DeviceAdmin

class SettingsFragment : BaseFragment(), View.OnClickListener, View.OnLongClickListener {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var deviceManager: DevicePolicyManager
    private lateinit var componentName: ComponentName

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val pickBackgroundImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            // without this the uri dies with the process and the background goes blank on reboot
            requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
        prefs.homeBgImage = uri.toString()
        prefs.homeBgType = Constants.HomeBackground.IMAGE
        applyBackground()
        if (prefs.homeBgType != Constants.HomeBackground.IMAGE)
            requireContext().showToast(getString(R.string.unable_to_load_image))
    }

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) showBlockedApps()
        else requireContext().showToast(getString(R.string.vpn_permission_needed))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")
        viewModel.isOlauncherDefault()

        deviceManager = requireContext().getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        componentName = ComponentName(requireContext(), DeviceAdmin::class.java)
        checkAdminPermission()

        binding.homeAppsNum.text = prefs.homeAppsNum.toString()
        populateKeyboardText()
        populateScreenTimeOnOff()
        populateBlockedApps()
        populateLockSettings()
        // Home button for recents feature disabled
        // populateHomeButtonRecents()
        populateWallpaperText()
        populateAppThemeText()
        populateBackgroundText()
        populateTextSize()
        populateAlignment()
        populateStatusBar()
        populateDateTime()
        populateSwipeApps()
        populateSwipeDownAction()
        populateOrientation()
        populateReading()
        populateNotifyLine()
        populateActionHints()
        initClickListeners()
        initObservers()
    }

    override fun onClick(view: View) {
        binding.appsNumSelectLayout.visibility = View.GONE
        binding.dateTimeSelectLayout.visibility = View.GONE
        binding.appThemeSelectLayout.visibility = View.GONE
        binding.swipeDownSelectLayout.visibility = View.GONE
        if (view.id != R.id.orientation) binding.orientationSelectLayout.visibility = View.GONE
        if (view.id != R.id.textSizeMinus && view.id != R.id.textSizePlus) {
            if (binding.textSizesLayout.isVisible) {
                binding.textSizesLayout.visibility = View.GONE
                applyTextSizeScale()
            }
        }
        if (view.id != R.id.alignmentBottom)
            binding.alignmentSelectLayout.visibility = View.GONE
        // stays open so a second tap cycles to the next preset
        if (view.id != R.id.backgroundColor && view.id != R.id.backgroundGradient)
            binding.backgroundSelectLayout.visibility = View.GONE

        when (view.id) {
            R.id.olauncherHiddenApps -> showHiddenApps()
            R.id.blockedApps -> showBlockedApps()
            R.id.screenTimeOnOff -> viewModel.showDialog.postValue(Constants.Dialog.DIGITAL_WELLBEING)
            R.id.appInfo -> openAppInfo(requireContext(), Process.myUserHandle(), BuildConfig.APPLICATION_ID)
            R.id.setLauncher -> viewModel.resetLauncherLiveData.call()
            R.id.toggleLock -> toggleLockMode()
            // Home button for recents feature disabled
            // R.id.homeButtonRecents -> toggleHomeButtonRecents()
            R.id.autoShowKeyboard -> toggleKeyboardText()
            R.id.homeAppsNum -> binding.appsNumSelectLayout.visibility = View.VISIBLE
            R.id.dailyWallpaperUrl -> requireContext().openUrl(prefs.dailyWallpaperUrl)
            R.id.dailyWallpaper -> toggleDailyWallpaperUpdate()
            R.id.alignment -> binding.alignmentSelectLayout.visibility = View.VISIBLE
            R.id.alignmentLeft -> viewModel.updateHomeAlignment(Gravity.START)
            R.id.alignmentCenter -> viewModel.updateHomeAlignment(Gravity.CENTER)
            R.id.alignmentRight -> viewModel.updateHomeAlignment(Gravity.END)
            R.id.alignmentBottom -> updateHomeBottomAlignment()
            R.id.statusBar -> toggleStatusBar()
            R.id.dateTime -> binding.dateTimeSelectLayout.visibility = View.VISIBLE
            R.id.dateTimeOn -> toggleDateTime(Constants.DateTime.ON)
            R.id.dateTimeOff -> toggleDateTime(Constants.DateTime.OFF)
            R.id.dateOnly -> toggleDateTime(Constants.DateTime.DATE_ONLY)
            R.id.backgroundText -> binding.backgroundSelectLayout.visibility = View.VISIBLE
            R.id.backgroundWallpaper -> updateBackground(Constants.HomeBackground.WALLPAPER)
            R.id.backgroundColor -> updateBackground(Constants.HomeBackground.COLOR)
            R.id.backgroundGradient -> updateBackground(Constants.HomeBackground.GRADIENT)
            R.id.backgroundImage -> pickBackgroundImage.launch(arrayOf("image/*"))
            R.id.appThemeText -> binding.appThemeSelectLayout.visibility = View.VISIBLE
            R.id.themeLight -> updateTheme(AppCompatDelegate.MODE_NIGHT_NO)
            R.id.themeDark -> updateTheme(AppCompatDelegate.MODE_NIGHT_YES)
            R.id.themeSystem -> updateTheme(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            R.id.textSizeValue -> binding.textSizesLayout.visibility = View.VISIBLE
            R.id.actionAccessibility -> openAccessibilityService()
            R.id.closeAccessibility -> toggleAccessibilityVisibility(false)
            R.id.notWorking -> requireContext().openUrl(Constants.URL_DOUBLE_TAP)

            R.id.tvGestures -> binding.flSwipeDown.visibility = View.VISIBLE

            R.id.maxApps0 -> updateHomeAppsNum(0)
            R.id.maxApps1 -> updateHomeAppsNum(1)
            R.id.maxApps2 -> updateHomeAppsNum(2)
            R.id.maxApps3 -> updateHomeAppsNum(3)
            R.id.maxApps4 -> updateHomeAppsNum(4)
            R.id.maxApps5 -> updateHomeAppsNum(5)
            R.id.maxApps6 -> updateHomeAppsNum(6)
            R.id.maxApps7 -> updateHomeAppsNum(7)
            R.id.maxApps8 -> updateHomeAppsNum(8)

            R.id.textSizeMinus -> adjustTextSizePreview(-0.1f)
            R.id.textSizePlus -> adjustTextSizePreview(0.1f)

            R.id.swipeLeftApp -> showAppListIfEnabled(Constants.FLAG_SET_SWIPE_LEFT_APP)
            R.id.swipeRightApp -> showAppListIfEnabled(Constants.FLAG_SET_SWIPE_RIGHT_APP)
            R.id.swipeDownAction -> binding.swipeDownSelectLayout.visibility = View.VISIBLE
            R.id.notifications -> updateSwipeDownAction(Constants.SwipeDownAction.NOTIFICATIONS)
            R.id.search -> updateSwipeDownAction(Constants.SwipeDownAction.SEARCH)
            R.id.dial -> updateSwipeDownAction(Constants.SwipeDownAction.DIAL)
            R.id.orientation -> binding.orientationSelectLayout.visibility = View.VISIBLE
            R.id.orientationAuto -> updateOrientation(Constants.Orientation.AUTO)
            R.id.orientationPortrait -> updateOrientation(Constants.Orientation.PORTRAIT)
            R.id.orientationLandscape -> updateOrientation(Constants.Orientation.LANDSCAPE)
            R.id.notifyLineToggle -> toggleNotifyLine()
            R.id.readingToggle -> toggleReading()
            R.id.readingRefresh -> refreshReading()
            R.id.readingTechnology -> toggleTopic(Constants.Topic.TECHNOLOGY)
            R.id.readingPhilosophy -> toggleTopic(Constants.Topic.PHILOSOPHY)
            R.id.readingScience -> toggleTopic(Constants.Topic.SCIENCE)
            R.id.readingBusiness -> toggleTopic(Constants.Topic.BUSINESS)
            R.id.readingCulture -> toggleTopic(Constants.Topic.CULTURE)
            R.id.readingHealth -> toggleTopic(Constants.Topic.HEALTH)
            R.id.readingLiterature -> toggleTopic(Constants.Topic.LITERATURE)
            R.id.readingFaith -> toggleTopic(Constants.Topic.FAITH)

            R.id.aboutOlauncher -> {
                prefs.aboutClicked = true
                requireContext().openUrl(Constants.URL_ABOUT_OLAUNCHER)
            }

            R.id.github -> requireContext().openUrl(Constants.URL_GITHUB)
            R.id.privacy -> requireContext().openUrl(Constants.URL_OLAUNCHER_PRIVACY)
            R.id.footer -> requireContext().openUrl(Constants.URL_SMARTSHOTS)
        }
    }

    override fun onLongClick(view: View): Boolean {
        when (view.id) {
            R.id.alignment -> {
                prefs.appLabelAlignment = prefs.homeAlignment
                findNavController().navigate(R.id.action_settingsFragment_to_appListFragment)
                requireContext().showToast(getString(R.string.alignment_changed))
            }

            R.id.backgroundText -> updateBackground(Constants.HomeBackground.WALLPAPER)
            R.id.dailyWallpaper -> removeWallpaper()
            R.id.appThemeText -> {
                binding.appThemeSelectLayout.visibility = View.VISIBLE
                binding.themeSystem.visibility = View.VISIBLE
            }

            R.id.swipeLeftApp -> toggleSwipeLeft()
            R.id.swipeRightApp -> toggleSwipeRight()
            R.id.toggleLock -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        return true
    }

    private fun initClickListeners() {
        binding.olauncherHiddenApps.setOnClickListener(this)
        binding.scrollLayout.setOnClickListener(this)
        binding.appInfo.setOnClickListener(this)
        binding.setLauncher.setOnClickListener(this)
        binding.aboutOlauncher.setOnClickListener(this)
        binding.autoShowKeyboard.setOnClickListener(this)
        binding.toggleLock.setOnClickListener(this)
        // Home button for recents feature disabled
        // binding.homeButtonRecents.setOnClickListener(this)
        binding.homeAppsNum.setOnClickListener(this)
        binding.screenTimeOnOff.setOnClickListener(this)
        binding.blockedApps.setOnClickListener(this)
        binding.dailyWallpaperUrl.setOnClickListener(this)
        binding.dailyWallpaper.setOnClickListener(this)
        binding.alignment.setOnClickListener(this)
        binding.alignmentLeft.setOnClickListener(this)
        binding.alignmentCenter.setOnClickListener(this)
        binding.alignmentRight.setOnClickListener(this)
        binding.alignmentBottom.setOnClickListener(this)
        binding.statusBar.setOnClickListener(this)
        binding.dateTime.setOnClickListener(this)
        binding.dateTimeOn.setOnClickListener(this)
        binding.dateTimeOff.setOnClickListener(this)
        binding.dateOnly.setOnClickListener(this)
        binding.swipeLeftApp.setOnClickListener(this)
        binding.swipeRightApp.setOnClickListener(this)
        binding.swipeDownAction.setOnClickListener(this)
        binding.search.setOnClickListener(this)
        binding.notifications.setOnClickListener(this)
        binding.dial.setOnClickListener(this)
        binding.orientation.setOnClickListener(this)
        binding.orientationAuto.setOnClickListener(this)
        binding.orientationPortrait.setOnClickListener(this)
        binding.orientationLandscape.setOnClickListener(this)
        binding.notifyLineToggle.setOnClickListener(this)
        binding.readingToggle.setOnClickListener(this)
        binding.readingRefresh.setOnClickListener(this)
        binding.readingTechnology.setOnClickListener(this)
        binding.readingPhilosophy.setOnClickListener(this)
        binding.readingScience.setOnClickListener(this)
        binding.readingBusiness.setOnClickListener(this)
        binding.readingCulture.setOnClickListener(this)
        binding.readingHealth.setOnClickListener(this)
        binding.readingLiterature.setOnClickListener(this)
        binding.readingFaith.setOnClickListener(this)
        binding.backgroundText.setOnClickListener(this)
        binding.backgroundWallpaper.setOnClickListener(this)
        binding.backgroundColor.setOnClickListener(this)
        binding.backgroundGradient.setOnClickListener(this)
        binding.backgroundImage.setOnClickListener(this)
        binding.appThemeText.setOnClickListener(this)
        binding.themeLight.setOnClickListener(this)
        binding.themeDark.setOnClickListener(this)
        binding.themeSystem.setOnClickListener(this)
        binding.textSizeValue.setOnClickListener(this)
        binding.actionAccessibility.setOnClickListener(this)
        binding.closeAccessibility.setOnClickListener(this)
        binding.notWorking.setOnClickListener(this)

        binding.github.setOnClickListener(this)
        binding.privacy.setOnClickListener(this)
        binding.footer.setOnClickListener(this)

        binding.maxApps0.setOnClickListener(this)
        binding.maxApps1.setOnClickListener(this)
        binding.maxApps2.setOnClickListener(this)
        binding.maxApps3.setOnClickListener(this)
        binding.maxApps4.setOnClickListener(this)
        binding.maxApps5.setOnClickListener(this)
        binding.maxApps6.setOnClickListener(this)
        binding.maxApps7.setOnClickListener(this)
        binding.maxApps8.setOnClickListener(this)

        binding.textSizeMinus.setOnClickListener(this)
        binding.textSizePlus.setOnClickListener(this)

        binding.dailyWallpaper.setOnLongClickListener(this)
        binding.alignment.setOnLongClickListener(this)
        binding.appThemeText.setOnLongClickListener(this)
        binding.backgroundText.setOnLongClickListener(this)
        binding.swipeLeftApp.setOnLongClickListener(this)
        binding.swipeRightApp.setOnLongClickListener(this)
        binding.toggleLock.setOnLongClickListener(this)
    }

    private fun initObservers() {
        if (prefs.firstSettingsOpen) {
            viewModel.showDialog.postValue(Constants.Dialog.ABOUT)
            prefs.firstSettingsOpen = false
        }
        viewModel.isOlauncherDefault.observe(viewLifecycleOwner) {
            if (it) {
                binding.setLauncher.text = getString(R.string.change_default_launcher)
                prefs.toShowHintCounter += 1
            }
        }
        viewModel.homeAppAlignment.observe(viewLifecycleOwner) {
            populateAlignment()
        }
        viewModel.updateSwipeApps.observe(viewLifecycleOwner) {
            populateSwipeApps()
        }
    }

    private fun toggleSwipeLeft() {
        prefs.swipeLeftEnabled = !prefs.swipeLeftEnabled
        if (prefs.swipeLeftEnabled) {
            binding.swipeLeftApp.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColor))
            requireContext().showToast(getString(R.string.swipe_left_app_enabled))
        } else {
            binding.swipeLeftApp.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColorTrans50))
            requireContext().showToast(getString(R.string.swipe_left_app_disabled))
        }
    }

    private fun toggleSwipeRight() {
        prefs.swipeRightEnabled = !prefs.swipeRightEnabled
        if (prefs.swipeRightEnabled) {
            binding.swipeRightApp.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColor))
            requireContext().showToast(getString(R.string.swipe_right_app_enabled))
        } else {
            binding.swipeRightApp.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColorTrans50))
            requireContext().showToast(getString(R.string.swipe_right_app_disabled))
        }
    }

    private fun toggleStatusBar() {
        prefs.showStatusBar = !prefs.showStatusBar
        populateStatusBar()
    }

    private fun populateStatusBar() {
        if (prefs.showStatusBar) {
            showStatusBar()
            binding.statusBar.text = getString(R.string.on)
        } else {
            hideStatusBar()
            binding.statusBar.text = getString(R.string.off)
        }
    }

    private fun toggleDateTime(selected: Int) {
        prefs.dateTimeVisibility = selected
        populateDateTime()
        viewModel.toggleDateTime()
    }

    private fun populateDateTime() {
        binding.dateTime.text = getString(
            when (prefs.dateTimeVisibility) {
                Constants.DateTime.DATE_ONLY -> R.string.date
                Constants.DateTime.ON -> R.string.on
                else -> R.string.off
            }
        )
    }

    private fun showStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.show(WindowInsets.Type.statusBars())
        else
            @Suppress("DEPRECATION", "InlinedApi")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            }
    }

    private fun hideStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.hide(WindowInsets.Type.statusBars())
        else {
            @Suppress("DEPRECATION")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE or View.SYSTEM_UI_FLAG_FULLSCREEN
            }
        }
    }

    private fun showHiddenApps() {
        if (prefs.hiddenApps.isEmpty()) {
            requireContext().showToast(getString(R.string.no_hidden_apps))
            return
        }
        viewModel.getHiddenApps()
        findNavController().navigate(
            R.id.action_settingsFragment_to_appListFragment,
            bundleOf(Constants.Key.FLAG to Constants.FLAG_HIDDEN_APPS)
        )
    }

    private fun populateBlockedApps() {
        val count = prefs.blockedApps.size
        binding.blockedApps.text =
            if (count == 0) getString(R.string.off) else getString(R.string.blocked_apps_count, count)
    }

    private fun showBlockedApps() {
        // consent is a one time system dialog, ask before the list so the first toggle takes effect
        VpnService.prepare(requireContext())?.let {
            vpnConsent.launch(it)
            return
        }
        viewModel.getAppList(includeHiddenApps = true)
        findNavController().navigate(
            R.id.action_settingsFragment_to_appListFragment,
            bundleOf(Constants.Key.FLAG to Constants.FLAG_BLOCKED_APPS)
        )
    }

    private fun checkAdminPermission() {
        val isAdmin: Boolean = deviceManager.isAdminActive(componentName)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P)
            prefs.lockModeOn = isAdmin
    }

    private fun toggleAccessibilityVisibility(show: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            binding.notWorking.visibility = View.VISIBLE
        if (isAccessServiceEnabled(requireContext()))
            binding.actionAccessibility.text = getString(R.string.disable)
        binding.accessibilityLayout.isVisible = show
        binding.scrollView.animateAlpha(if (show) 0.5f else 1f)
    }

    private fun openAccessibilityService() {
        toggleAccessibilityVisibility(false)
        // prefs.lockModeOn = true
        populateLockSettings()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun toggleLockMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (!prefs.lockModeOn && !isAccessServiceEnabled(requireContext())) {
                toggleAccessibilityVisibility(true)
                return
            }
            prefs.lockModeOn = !prefs.lockModeOn
        } else {
            val isAdmin: Boolean = deviceManager.isAdminActive(componentName)
            if (isAdmin) {
                removeActiveAdmin("Admin permission removed.")
                prefs.lockModeOn = false
            } else {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                intent.putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    getString(R.string.admin_permission_message)
                )
                requireActivity().startActivityForResult(intent, Constants.REQUEST_CODE_ENABLE_ADMIN)
            }
        }
        populateLockSettings()
    }

    private fun removeActiveAdmin(toastMessage: String? = null) {
        try {
            deviceManager.removeActiveAdmin(componentName) // for backward compatibility
            requireContext().showToast(toastMessage)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeWallpaper() {
        if (requireContext().isEinkDisplay()) {
            prefs.appTheme = AppCompatDelegate.MODE_NIGHT_NO
            setPlainWallpaper(requireContext(), android.R.color.white)
        } else {
            prefs.appTheme = AppCompatDelegate.MODE_NIGHT_YES
            setPlainWallpaper(requireContext(), android.R.color.black)
        }
        if (!prefs.dailyWallpaper) return
        prefs.dailyWallpaper = false
        populateWallpaperText()
        viewModel.cancelWallpaperWorker()
    }

    private fun toggleDailyWallpaperUpdate() {
        if (prefs.dailyWallpaper.not() && prefs.appTheme == AppCompatDelegate.MODE_NIGHT_YES && viewModel.isOlauncherDefault.value == false) {
            requireContext().showToast(R.string.set_as_default_launcher_first)
            return
        }
        prefs.dailyWallpaper = !prefs.dailyWallpaper
        populateWallpaperText()
        if (prefs.dailyWallpaper) {
            viewModel.setWallpaperWorker()
            showWallpaperToasts()
        } else viewModel.cancelWallpaperWorker()
    }

    private fun showWallpaperToasts() {
        if (isOlauncherDefault(requireContext()))
            requireContext().showToast(getString(R.string.your_wallpaper_will_update_shortly))
        else
            requireContext().showToast(getString(R.string.olauncher_is_not_default_launcher), Toast.LENGTH_LONG)
    }

    private fun updateHomeAppsNum(num: Int) {
        binding.homeAppsNum.text = num.toString()
        binding.appsNumSelectLayout.visibility = View.GONE
        prefs.homeAppsNum = num
        viewModel.refreshHome(true)
    }

    private var pendingTextSizeScale: Float = -1f

    private fun adjustTextSizePreview(delta: Float) {
        val maxScale = if (isTablet(requireContext())) 2.0f else 1.5f
        val current = if (pendingTextSizeScale > 0) pendingTextSizeScale else prefs.textSizeScale
        val newScale = Math.round((current + delta) * 10f) / 10f
        val clamped = newScale.coerceIn(0.5f, maxScale)
        if (clamped == current) return
        pendingTextSizeScale = clamped
        val formatted = String.format("%.1f", clamped)
        binding.textSizeValue.text = formatted
        binding.textSizeCurrent.text = formatted
    }

    private fun applyTextSizeScale() {
        if (pendingTextSizeScale < 0 || prefs.textSizeScale == pendingTextSizeScale) {
            pendingTextSizeScale = -1f
            return
        }
        prefs.textSizeScale = pendingTextSizeScale
        pendingTextSizeScale = -1f
        requireActivity().recreate()
    }

    private fun toggleKeyboardText() {
        if (prefs.autoShowKeyboard && prefs.keyboardMessageShown.not()) {
            viewModel.showDialog.postValue(Constants.Dialog.KEYBOARD)
            prefs.keyboardMessageShown = true
        } else {
            prefs.autoShowKeyboard = !prefs.autoShowKeyboard
            populateKeyboardText()
        }
    }

    private fun updateTheme(appTheme: Int) {
        if (AppCompatDelegate.getDefaultNightMode() == appTheme) return
        prefs.appTheme = appTheme
        populateAppThemeText(appTheme)
        setAppTheme(appTheme)
    }

    private fun setAppTheme(theme: Int) {
        if (AppCompatDelegate.getDefaultNightMode() == theme) return
        if (prefs.dailyWallpaper) {
            setPlainWallpaper(theme)
            viewModel.setWallpaperWorker()
        }
        requireActivity().recreate()
    }

    private fun setPlainWallpaper(appTheme: Int) {
        when (appTheme) {
            AppCompatDelegate.MODE_NIGHT_YES -> setPlainWallpaper(requireContext(), android.R.color.black)
            AppCompatDelegate.MODE_NIGHT_NO -> setPlainWallpaper(requireContext(), android.R.color.white)
            else -> {
                if (requireContext().isDarkThemeOn())
                    setPlainWallpaper(requireContext(), android.R.color.black)
                else setPlainWallpaper(requireContext(), android.R.color.white)
            }
        }
    }

    private fun populateAppThemeText(appTheme: Int = prefs.appTheme) {
        when (appTheme) {
            AppCompatDelegate.MODE_NIGHT_YES -> binding.appThemeText.text = getString(R.string.dark)
            AppCompatDelegate.MODE_NIGHT_NO -> binding.appThemeText.text = getString(R.string.light)
            else -> binding.appThemeText.text = getString(R.string.system_default)
        }
    }

    private fun populateBackgroundText() {
        binding.backgroundText.text = when (prefs.homeBgType) {
            Constants.HomeBackground.COLOR -> getString(R.string.color)
            Constants.HomeBackground.GRADIENT -> getString(R.string.gradient)
            Constants.HomeBackground.IMAGE -> getString(R.string.image)
            else -> getString(R.string.wallpaper)
        }
    }

    private fun updateBackground(type: Int) {
        // same option tapped twice: step to the next preset instead of doing nothing
        if (prefs.homeBgType == type) prefs.homeBgIndex += 1
        else {
            prefs.homeBgType = type
            prefs.homeBgIndex = 0
            if (type == Constants.HomeBackground.COLOR || type == Constants.HomeBackground.GRADIENT)
                requireContext().showToast(getString(R.string.tap_again_for_next))
        }
        applyBackground()
    }

    private fun applyBackground() {
        (activity as? MainActivity)?.refreshBackground()
        populateBackgroundText()
    }

    private fun populateTextSize() {
        val formatted = String.format("%.1f", prefs.textSizeScale)
        binding.textSizeValue.text = formatted
        binding.textSizeCurrent.text = formatted
    }

    private fun populateScreenTimeOnOff() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (requireContext().appUsagePermissionGranted()) binding.screenTimeOnOff.text = getString(R.string.on)
            else binding.screenTimeOnOff.text = getString(R.string.off)
        } else binding.screenTimeLayout.visibility = View.GONE
    }

    private fun populateKeyboardText() {
        if (prefs.autoShowKeyboard) binding.autoShowKeyboard.text = getString(R.string.on)
        else binding.autoShowKeyboard.text = getString(R.string.off)
    }

    private fun populateWallpaperText() {
        if (prefs.dailyWallpaper) binding.dailyWallpaper.text = getString(R.string.on)
        else binding.dailyWallpaper.text = getString(R.string.off)
    }

    private fun updateHomeBottomAlignment() {
        if (viewModel.isOlauncherDefault.value != true) {
            requireContext().showToast(getString(R.string.please_set_olauncher_as_default_first), Toast.LENGTH_LONG)
            return
        }
        prefs.homeBottomAlignment = !prefs.homeBottomAlignment
        populateAlignment()
        viewModel.updateHomeAlignment(prefs.homeAlignment)
    }

    private fun populateAlignment() {
        when (prefs.homeAlignment) {
            Gravity.START -> binding.alignment.text = getString(R.string.left)
            Gravity.CENTER -> binding.alignment.text = getString(R.string.center)
            Gravity.END -> binding.alignment.text = getString(R.string.right)
        }
        binding.alignmentBottom.text = if (prefs.homeBottomAlignment)
            getString(R.string.bottom_on)
        else getString(R.string.bottom_off)
    }

    // Home button for recents feature disabled
    // private fun toggleHomeButtonRecents() {
    //     if (!prefs.homeButtonShowRecents && !isAccessServiceEnabled(requireContext())) {
    //         toggleAccessibilityVisibility(true)
    //         return
    //     }
    //     prefs.homeButtonShowRecents = !prefs.homeButtonShowRecents
    //     populateHomeButtonRecents()
    // }

    // private fun populateHomeButtonRecents() {
    //     binding.homeButtonRecents.text = getString(
    //         if (prefs.homeButtonShowRecents && isAccessServiceEnabled(requireContext())) R.string.on
    //         else R.string.off
    //     )
    // }

    private fun populateLockSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            binding.toggleLock.text = getString(
                if (prefs.lockModeOn && isAccessServiceEnabled(requireContext())) R.string.on
                else R.string.off
            )
        } else {
            binding.toggleLock.text = getString(
                if (prefs.lockModeOn) R.string.on
                else R.string.off
            )
        }
    }

    private fun populateSwipeDownAction() {
        binding.swipeDownAction.text = when (prefs.swipeDownAction) {
            Constants.SwipeDownAction.NOTIFICATIONS -> getString(R.string.notifications)
            Constants.SwipeDownAction.DIAL -> getString(R.string.dial)
            else -> getString(R.string.search)
        }
    }

    private fun updateSwipeDownAction(swipeDownFor: Int) {
        if (prefs.swipeDownAction == swipeDownFor) return
        prefs.swipeDownAction = swipeDownFor
        populateSwipeDownAction()
    }

    private fun populateOrientation() {
        binding.orientation.text = when (prefs.screenOrientation) {
            Constants.Orientation.AUTO -> getString(R.string.auto)
            Constants.Orientation.LANDSCAPE -> getString(R.string.landscape)
            else -> getString(R.string.portrait)
        }
    }

    private fun updateOrientation(orientation: Int) {
        binding.orientationSelectLayout.visibility = View.GONE
        if (prefs.screenOrientation == orientation) return
        prefs.screenOrientation = orientation
        populateOrientation()
        // takes effect straight away rather than on next launch
        (activity as? MainActivity)?.setupOrientation()
    }

    private fun populateNotifyLine() {
        binding.notifyLineToggle.text = getString(if (prefs.notifyLineEnabled) R.string.on else R.string.off)
    }

    private fun toggleNotifyLine() {
        // reading notifications needs system-granted access; send the user there the first time on
        if (!prefs.notifyLineEnabled && !isNotificationAccessGranted(requireContext())) {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {
                requireContext().showToast(R.string.app_not_found)
                return
            }
        }
        prefs.notifyLineEnabled = !prefs.notifyLineEnabled
        populateNotifyLine()
    }

    private fun populateReading() {
        val on = prefs.readingEnabled
        binding.readingToggle.text = getString(if (on) R.string.on else R.string.off)
        // topics only matter once the feature is on, so they stay hidden until then
        binding.readingTopicsLayout.isVisible = on
        val chosen = prefs.readingTopics
        topicViews().forEach { (topicId, view) ->
            view.text = getString(if (chosen.contains(topicId.toString())) R.string.on else R.string.off)
        }
    }

    private fun topicViews() = mapOf(
        Constants.Topic.TECHNOLOGY to binding.readingTechnology,
        Constants.Topic.PHILOSOPHY to binding.readingPhilosophy,
        Constants.Topic.SCIENCE to binding.readingScience,
        Constants.Topic.BUSINESS to binding.readingBusiness,
        Constants.Topic.CULTURE to binding.readingCulture,
        Constants.Topic.HEALTH to binding.readingHealth,
        Constants.Topic.LITERATURE to binding.readingLiterature,
        Constants.Topic.FAITH to binding.readingFaith,
    )

    private fun toggleReading() {
        prefs.readingEnabled = !prefs.readingEnabled
        populateReading()
        viewModel.setReadingWorker()
        if (prefs.readingEnabled && prefs.readingTopics.isEmpty())
            requireContext().showToast(R.string.pick_a_topic)
        else if (prefs.readingEnabled) refreshReading()
    }

    private fun toggleTopic(topicId: Int) {
        val topics = prefs.readingTopics.toMutableSet()
        if (!topics.remove(topicId.toString())) topics.add(topicId.toString())
        prefs.readingTopics = topics
        populateReading()
        viewModel.setReadingWorker()
        if (topics.isNotEmpty()) refreshReading()
    }

    private fun refreshReading() {
        if (prefs.readingTopics.isEmpty()) {
            requireContext().showToast(R.string.pick_a_topic)
            return
        }
        requireContext().showToast(R.string.refreshing)
        viewLifecycleOwner.lifecycleScope.launch {
            val count = refreshReadingList(requireContext().applicationContext)
            if (count == 0) requireContext().showToast(R.string.nothing_to_read)
        }
    }

    private fun populateSwipeApps() {
        binding.swipeLeftApp.text = prefs.appNameSwipeLeft
        binding.swipeRightApp.text = prefs.appNameSwipeRight
        if (!prefs.swipeLeftEnabled)
            binding.swipeLeftApp.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColorTrans50))
        if (!prefs.swipeRightEnabled)
            binding.swipeRightApp.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColorTrans50))
    }

//    private fun populateDigitalWellbeing() {
//        binding.digitalWellbeing.isVisible = requireContext().isPackageInstalled(Constants.DIGITAL_WELLBEING_PACKAGE_NAME).not()
//                && requireContext().isPackageInstalled(Constants.DIGITAL_WELLBEING_SAMSUNG_PACKAGE_NAME).not()
//                && prefs.hideDigitalWellbeing.not()
//    }

    private fun showAppListIfEnabled(flag: Int) {
        if ((flag == Constants.FLAG_SET_SWIPE_LEFT_APP) and !prefs.swipeLeftEnabled) {
            requireContext().showToast(getString(R.string.long_press_to_enable))
            return
        }
        if ((flag == Constants.FLAG_SET_SWIPE_RIGHT_APP) and !prefs.swipeRightEnabled) {
            requireContext().showToast(getString(R.string.long_press_to_enable))
            return
        }
        viewModel.getAppList(true)
        findNavController().navigate(
            R.id.action_settingsFragment_to_appListFragment,
            bundleOf(Constants.Key.FLAG to flag)
        )
    }

    private fun populateActionHints() {
        if (prefs.aboutClicked.not())
            binding.aboutOlauncher.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_info, 0)
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        viewModel.checkForMessages.call()
        super.onDestroy()
    }
}
