package com.munjed.husk.ui

import android.app.ActivityOptions
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.munjed.husk.data.Prefs
import com.munjed.husk.databinding.ActivityNotificationsBinding
import com.munjed.husk.databinding.AdapterNotificationBinding
import com.munjed.husk.databinding.AdapterSectionBinding
import com.munjed.husk.helper.NotifItem
import com.munjed.husk.helper.NotificationService
import com.munjed.husk.helper.applyHomeBackground
import com.munjed.husk.helper.applyScriptTypefaceRecursively
import com.munjed.husk.helper.inflateFallbackView
import com.munjed.husk.helper.setScriptTypeface

private sealed class NotifRow {
    data class Header(val appLabel: String) : NotifRow()
    data class Item(val notif: NotifItem) : NotifRow()
}

class NotificationsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotificationsBinding
    private val adapter = NotifAdapter { open(it) }

    override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? {
        val view = super.onCreateView(parent, name, context, attrs) ?: inflateFallbackView(context, name, attrs)
        if (view is TextView) view.setScriptTypeface()
        return view
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyScriptTypefaceRecursively()
        applyHomeBackground(binding.notifyActivityLayout, binding.appBackground, Prefs(this))

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        attachSwipeToDismiss()
        binding.clearAll.setOnClickListener { NotificationService.clearAll() }
        NotificationService.items.observe(this) { render(it) }
    }

    private fun render(items: List<NotifItem>) {
        val rows = mutableListOf<NotifRow>()
        // one header per app, its notifications underneath — the launcher reads better grouped
        items.groupBy { it.appLabel }.forEach { (label, group) ->
            rows.add(NotifRow.Header(label))
            group.forEach { rows.add(NotifRow.Item(it)) }
        }
        adapter.rows = rows
        adapter.notifyDataSetChanged()
        binding.empty.isVisible = rows.isEmpty()
        binding.clearAll.isVisible = rows.isNotEmpty()
    }

    /** Fire the notification's own tap action, then clear it. */
    private fun open(notif: NotifItem) {
        if (!sendContentIntent(notif) && !launchApp(notif.packageName)) return
        NotificationService.dismiss(notif.key)
        finish()
    }

    /**
     * The notification's own intent was created by an app that is now in the background, so on
     * Android 14+ it only starts an activity if we, the sender, hand over our foreground privileges.
     * Without that the send() succeeds silently and nothing opens.
     */
    private fun sendContentIntent(notif: NotifItem): Boolean {
        val intent = notif.intent ?: return false
        val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        else null
        // a canceled intent (notification rebuilt since we read it) falls through to the app launch
        return runCatching { intent.send(this, 0, null, null, null, null, options) }.isSuccess
    }

    /** Fallback for notifications with no tap action of their own: just open the app. */
    private fun launchApp(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return runCatching { startActivity(intent) }.isSuccess
    }

    private fun attachSwipeToDismiss() {
        val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

            override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
                if (adapter.rows.getOrNull(vh.bindingAdapterPosition) is NotifRow.Item)
                    ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT else 0

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val row = adapter.rows.getOrNull(vh.bindingAdapterPosition) as? NotifRow.Item ?: return
                // dismiss triggers a service refresh, which re-renders the list without this row
                NotificationService.dismiss(row.notif.key)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.recyclerView)
    }
}

private class NotifAdapter(
    private val onClick: (NotifItem) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var rows: List<NotifRow> = emptyList()

    class HeaderHolder(val binding: AdapterSectionBinding) : RecyclerView.ViewHolder(binding.root)
    class ItemHolder(val binding: AdapterNotificationBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int) = if (rows[position] is NotifRow.Header) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 1) HeaderHolder(AdapterSectionBinding.inflate(inflater, parent, false))
        else ItemHolder(AdapterNotificationBinding.inflate(inflater, parent, false))
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is NotifRow.Header -> (holder as HeaderHolder).binding.sectionTitle.apply {
                text = row.appLabel
                setScriptTypeface()
            }
            is NotifRow.Item -> with((holder as ItemHolder).binding) {
                notifTitle.text = row.notif.title.ifBlank { row.notif.appLabel }
                notifTitle.setScriptTypeface()
                notifText.text = row.notif.text
                notifText.setScriptTypeface()
                notifText.isVisible = row.notif.text.isNotBlank()
                notifRow.setOnClickListener { onClick(row.notif) }
            }
        }
    }
}
