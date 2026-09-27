package com.munjed.husk.ui

import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Filter
import android.widget.Filterable
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.munjed.husk.R
import com.munjed.husk.data.AppModel
import com.munjed.husk.data.Constants
import com.munjed.husk.databinding.AdapterAppDrawerBinding
import com.munjed.husk.databinding.AdapterPrivateSpaceHeaderBinding
import com.munjed.husk.databinding.AdapterSectionBinding
import com.munjed.husk.helper.dpToPx
import com.munjed.husk.helper.hideKeyboard
import com.munjed.husk.helper.iconRes
import com.munjed.husk.helper.isSystemApp
import com.munjed.husk.helper.setScriptTypeface
import com.munjed.husk.helper.showKeyboard
import java.text.Normalizer

class AppDrawerAdapter(
    private var flag: Int,
    private val appLabelGravity: Int,
    private val suggestionLabel: (isCall: Boolean, query: String) -> String,
    private val appClickListener: (AppModel) -> Unit,
    private val appInfoListener: (AppModel) -> Unit,
    private val appDeleteListener: (AppModel) -> Unit,
    private val appHideListener: (AppModel, Int) -> Unit,
    private val appRenameListener: (AppModel, String) -> Unit,
    private val appShortcutsProvider: (AppModel) -> List<AppModel> = { emptyList() },
    private val privateSpaceToggleListener: () -> Unit = {},
    private val privateSpaceSettingsListener: () -> Unit = {},
) : ListAdapter<AppModel, RecyclerView.ViewHolder>(DIFF_CALLBACK), Filterable {

    companion object {
        const val VIEW_TYPE_APP = 0
        const val VIEW_TYPE_PRIVATE_HEADER = 1
        const val VIEW_TYPE_SECTION = 2
        private const val CHILD_INDENT_DP = 20
        private const val TITLE_PADDING_DP = 24

        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AppModel>() {
            override fun areItemsTheSame(oldItem: AppModel, newItem: AppModel): Boolean = when {
                oldItem is AppModel.App && newItem is AppModel.App ->
                    oldItem.appPackage == newItem.appPackage && oldItem.user == newItem.user
                        && oldItem.isRecent == newItem.isRecent

                oldItem is AppModel.PinnedShortcut && newItem is AppModel.PinnedShortcut ->
                    oldItem.shortcutId == newItem.shortcutId && oldItem.user == newItem.user

                oldItem is AppModel.PrivateSpaceHeader && newItem is AppModel.PrivateSpaceHeader -> true

                oldItem is AppModel.SectionHeader && newItem is AppModel.SectionHeader ->
                    oldItem.title == newItem.title

                oldItem is AppModel.Suggestion && newItem is AppModel.Suggestion ->
                    oldItem.query == newItem.query && oldItem.isCall == newItem.isCall

                else -> false
            }

            override fun areContentsTheSame(oldItem: AppModel, newItem: AppModel): Boolean =
                oldItem == newItem
        }
    }

    private var autoLaunch = true
    private var isBangSearch = false
    var allowAutoLaunch = true
    private val diacriticsRegex = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val separatorsRegex = Regex("[-_+,.`'\\s\\p{Z}]")
    private val appFilter = createAppFilter()
    private val myUserHandle = android.os.Process.myUserHandle()

    var appsList: MutableList<AppModel> = mutableListOf()
    var appFilteredList: MutableList<AppModel> = mutableListOf()

    override fun getItemViewType(position: Int): Int {
        return when (appFilteredList.getOrNull(position)) {
            is AppModel.PrivateSpaceHeader -> VIEW_TYPE_PRIVATE_HEADER
            is AppModel.SectionHeader -> VIEW_TYPE_SECTION
            else -> VIEW_TYPE_APP
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_PRIVATE_HEADER -> PrivateSpaceHeaderViewHolder(
                AdapterPrivateSpaceHeaderBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            VIEW_TYPE_SECTION -> SectionViewHolder(
                AdapterSectionBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            else -> ViewHolder(
                AdapterAppDrawerBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        try {
            if (appFilteredList.isEmpty() || position == RecyclerView.NO_POSITION) return
            val appModel = appFilteredList[holder.bindingAdapterPosition]
            when (holder) {
                is PrivateSpaceHeaderViewHolder -> {
                    holder.bind(
                        appLabelGravity,
                        privateSpaceToggleListener,
                        privateSpaceSettingsListener,
                    )
                }

                is SectionViewHolder -> holder.binding.sectionTitle.apply {
                    text = (appModel as AppModel.SectionHeader).title
                    setScriptTypeface()
                }

                is ViewHolder -> holder.bind(
                    flag,
                    appLabelGravity,
                    myUserHandle,
                    appModel,
                    appClickListener,
                    appDeleteListener,
                    appInfoListener,
                    appHideListener,
                    appRenameListener,
                    appShortcutsProvider,
                ) { app -> showShortcuts(app) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun getFilter(): Filter = this.appFilter

    private fun createAppFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(charSearch: CharSequence?): FilterResults {
                isBangSearch = charSearch?.startsWith("!") ?: false
                autoLaunch = allowAutoLaunch && (charSearch?.startsWith(" ")?.not() ?: true)

                val appFilteredList = (if (charSearch.isNullOrBlank()) appsList
                else appsList.filter { app ->
                    app !is AppModel.PrivateSpaceHeader && app !is AppModel.SectionHeader
                        && (app as? AppModel.App)?.isRecent != true
                        && appLabelMatches(app.appLabel, charSearch)
                } as MutableList<AppModel>)

                // a dead end is a wasted screen, offer the obvious next move instead
                if (appFilteredList.isEmpty() && !charSearch.isNullOrBlank() && !isBangSearch)
                    appFilteredList.add(suggestionFor(charSearch.trim().toString()))

                val filterResults = FilterResults()
                filterResults.values = appFilteredList
                return filterResults
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                results?.values?.let {
                    val items = it as MutableList<AppModel>
                    appFilteredList = items
                    submitList(appFilteredList) {
                        autoLaunch()
                    }
                }
            }
        }
    }

    private fun autoLaunch() {
        try {
            if (itemCount == 1
                && autoLaunch
                && isBangSearch.not()
                && flag == Constants.FLAG_LAUNCH_APP
                && appFilteredList.isNotEmpty()
                && appFilteredList[0] !is AppModel.PrivateSpaceHeader
                && appFilteredList[0] !is AppModel.SectionHeader
                // a fallback row is a suggestion, never something to fire off mid typing
                && appFilteredList[0] !is AppModel.Suggestion
            ) appClickListener(appFilteredList[0])
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun suggestionFor(query: String): AppModel.Suggestion {
        val digits = query.filter { it.isDigit() }
        val isCall = digits.length >= 3 && digits.length == query.filter { !it.isWhitespace() && it != '+' }.length
        return AppModel.Suggestion(
            query = query,
            isCall = isCall,
            appLabel = suggestionLabel(isCall, query),
        )
    }

    private fun appLabelMatches(appLabel: String, charSearch: CharSequence): Boolean {
        if (appLabel.contains(charSearch.trim(), true)) return true
        val query = charSearch.normalizeForSearch()
        return query.isNotEmpty() && appLabel.normalizeForSearch().contains(query, true)
    }

    private fun CharSequence.normalizeForSearch(): String =
        Normalizer.normalize(this, Normalizer.Form.NFD)
            .replace(diacriticsRegex, "")
            .replace(separatorsRegex, "")

    fun setAppList(appsList: MutableList<AppModel>) {
        // Add empty app for bottom padding in recyclerview and assign to list
        appsList.add(
            AppModel.App(
                appLabel = "",
                key = null,
                appPackage = "",
                activityClassName = "",
                isNew = false,
                user = android.os.Process.myUserHandle()
            )
        )
        this.appsList = appsList
        this.appFilteredList = appsList
        submitList(appsList)
    }

    /** Drops the app's own shortcuts into the list directly under it, as plain rows. */
    private fun showShortcuts(appModel: AppModel) {
        val shortcuts = appShortcutsProvider(appModel)
        if (shortcuts.isEmpty()) return
        val position = appFilteredList.indexOf(appModel)
        if (position == -1) return
        val updated = appFilteredList.toMutableList()
        // toggle: tapping again on an app that already listed its shortcuts removes them
        val existing = updated.filterIsInstance<AppModel.PinnedShortcut>()
            .filter { shortcut -> shortcuts.any { it.appLabel == shortcut.appLabel && it.appPackage == shortcut.appPackage } }
        if (existing.isNotEmpty() && updated.getOrNull(position + 1) in existing) updated.removeAll(existing)
        else updated.addAll(position + 1, shortcuts)
        appFilteredList = updated
        submitList(updated)
    }

    fun launchFirstInList() {
        val first = appFilteredList.firstOrNull {
            it !is AppModel.PrivateSpaceHeader && it !is AppModel.SectionHeader
        }
        if (first != null) appClickListener(first)
    }

    class PrivateSpaceHeaderViewHolder(private val binding: AdapterPrivateSpaceHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(
            appLabelGravity: Int,
            toggleListener: () -> Unit,
            settingsListener: () -> Unit,
        ) = with(binding) {
            privateSpaceTitle.gravity = appLabelGravity
            privateSpaceTitle.setOnClickListener { toggleListener() }
            privateSpaceTitle.setOnLongClickListener {
                settingsListener()
                true
            }
        }
    }

    class SectionViewHolder(val binding: AdapterSectionBinding) : RecyclerView.ViewHolder(binding.root)

    class ViewHolder(private val binding: AdapterAppDrawerBinding) :
        RecyclerView.ViewHolder(binding.root) {
        // one per holder: bind runs on every reuse, and stacking a new watcher each time leaks
        private var renameWatcher: TextWatcher? = null

        fun bind(
            flag: Int,
            appLabelGravity: Int,
            myUserHandle: UserHandle,
            appModel: AppModel,
            clickListener: (AppModel) -> Unit,
            appDeleteListener: (AppModel) -> Unit,
            appInfoListener: (AppModel) -> Unit,
            appHideListener: (AppModel, Int) -> Unit,
            appRenameListener: (AppModel, String) -> Unit,
            appShortcutsProvider: (AppModel) -> List<AppModel>,
            showShortcuts: (AppModel) -> Unit,
        ) = with(binding) {
            appHideLayout.visibility = View.GONE
            renameLayout.visibility = View.GONE
            appTitle.visibility = View.VISIBLE

            // Show indicators in title based on app type and state
            appTitle.text = buildString {
                append(appModel.appLabel)
                if (appModel.isNew) append(" ✦")
            }
            appTitle.setScriptTypeface()
            appTitle.gravity = appLabelGravity
            // a shortcut listed under its app reads as a continuation of it: dimmer, and indented
            // on whichever side the label is anchored to
            val isChild = appModel is AppModel.PinnedShortcut && appModel.isChild
            appTitle.alpha = if (isChild) 0.55f else 1f
            val indent = if (isChild) CHILD_INDENT_DP.dpToPx() else 0
            val base = TITLE_PADDING_DP.dpToPx()
            if (appLabelGravity == android.view.Gravity.END)
                appTitle.setPaddingRelative(base, appTitle.paddingTop, base + indent, appTitle.paddingBottom)
            else
                appTitle.setPaddingRelative(base + indent, appTitle.paddingTop, base, appTitle.paddingBottom)
            otherProfileIndicator.isVisible = appModel.user != myUserHandle

            appTitle.setOnClickListener { clickListener(appModel) }

            appTitle.setOnLongClickListener {
                if (appModel is AppModel.Suggestion) return@setOnLongClickListener true
                if (appModel.appPackage.isNotEmpty()) {
                    appDelete.alpha = when (
                        appModel is AppModel.PinnedShortcut || !root.context.isSystemApp(appModel.appPackage, appModel.user)
                    ) {
                        true -> 1.0f
                        false -> 0.5f
                    }
                    appHide.text = if (flag == Constants.FLAG_HIDDEN_APPS)
                        root.context.getString(R.string.adapter_show)
                    else
                        root.context.getString(R.string.adapter_hide)
                    appTitle.visibility = View.INVISIBLE
                    appHide.alpha = when (appModel is AppModel.PinnedShortcut) {
                        true -> 0.5f
                        false -> 1.0f
                    }
                    appHideLayout.visibility = View.VISIBLE
                    // Only allow renaming non hidden apps
                    appRename.isVisible = flag != Constants.FLAG_HIDDEN_APPS
                    // most apps publish none, so the button only shows when there is something behind it
                    appShortcuts.isVisible = appShortcutsProvider(appModel).isNotEmpty()
                }
                true
            }

            appShortcuts.setOnClickListener {
                appHideLayout.visibility = View.GONE
                appTitle.visibility = View.VISIBLE
                showShortcuts(appModel)
            }

            // Configure rename behavior
            appRename.setOnClickListener {
                if (appModel.appPackage.isNotEmpty()) {
                    etAppRename.hint = getAppName(etAppRename.context, appModel.appPackage, appModel.user)
                    etAppRename.setText(appModel.appLabel)
                    etAppRename.setSelectAllOnFocus(true)
                    renameLayout.visibility = View.VISIBLE
                    appHideLayout.visibility = View.GONE
                    etAppRename.showKeyboard()
                    etAppRename.imeOptions = EditorInfo.IME_ACTION_DONE
                }
            }
            etAppRename.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                appTitle.visibility = if (hasFocus) View.INVISIBLE else View.VISIBLE
            }
            renameWatcher?.let(etAppRename::removeTextChangedListener)
            renameWatcher = object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    etAppRename.hint = getAppName(etAppRename.context, appModel.appPackage, appModel.user)
                }

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    etAppRename.hint = ""
                }
            }
            etAppRename.addTextChangedListener(renameWatcher)
            etAppRename.setOnEditorActionListener { _, actionCode, _ ->
                if (actionCode == EditorInfo.IME_ACTION_DONE) {
                    val renameLabel = etAppRename.text.toString().trim()
                    if (renameLabel.isNotBlank() && appModel.appPackage.isNotBlank()) {
                        appRenameListener(appModel, renameLabel)
                        renameLayout.visibility = View.GONE
                    }
                    true
                }
                false
            }
            tvSaveRename.setOnClickListener {
                etAppRename.hideKeyboard()
                val renameLabel = etAppRename.text.toString().trim()
                if (renameLabel.isNotBlank() && appModel.appPackage.isNotBlank()) {
                    appRenameListener(appModel, renameLabel)
                    renameLayout.visibility = View.GONE
                } else {
                    appRenameListener(
                        appModel,
                        getAppName(etAppRename.context, appModel.appPackage, appModel.user)
                    )
                    renameLayout.visibility = View.GONE
                }
            }
            appInfo.setCompoundDrawablesWithIntrinsicBounds(0, iconRes(appInfo.context, R.drawable.ic_info, R.drawable.ic_info_normal), 0, 0)
            appInfo.setOnClickListener { appInfoListener(appModel) }
            appDelete.setOnClickListener { appDeleteListener(appModel) }
            appMenuClose.setOnClickListener {
                appHideLayout.visibility = View.GONE
                appTitle.visibility = View.VISIBLE
            }
            appRenameClose.setOnClickListener {
                renameLayout.visibility = View.GONE
                appTitle.visibility = View.VISIBLE
            }
            appHide.setOnClickListener { appHideListener(appModel, bindingAdapterPosition) }
        }

        private fun getAppName(context: Context, appPackage: String, user: UserHandle): String {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            return try {
                val activityList = launcherApps.getActivityList(appPackage, user)
                if (activityList.isNotEmpty()) {
                    activityList.first().label.toString()
                } else {
                    val packageManager = context.packageManager
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(appPackage, 0)
                    ).toString()
                }
            } catch (_: Exception) {
                "" // As a fallback, display an empty string.
            }
        }
    }
}
