package com.munjed.husk.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.text.format.DateUtils
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.munjed.husk.R
import com.munjed.husk.data.Prefs
import com.munjed.husk.databinding.ActivityDialBinding
import com.munjed.husk.databinding.AdapterContactBinding
import com.munjed.husk.databinding.AdapterSectionBinding
import com.munjed.husk.helper.applyHomeBackground
import com.munjed.husk.helper.applyScriptTypefaceRecursively
import com.munjed.husk.helper.contactMatches
import com.munjed.husk.helper.inflateFallbackView
import com.munjed.husk.helper.normalizeName
import com.munjed.husk.helper.openUrl
import com.munjed.husk.helper.setScriptTypeface
import com.munjed.husk.helper.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val RECENT_COUNT = 4
private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
private const val ACTIONS_TIMEOUT_MS = 3000L
private const val MIN_DIALABLE_DIGITS = 3

private data class Contact(
    val name: String,
    val number: String,
    val subtitle: String = number,
    val contactId: Long = 0L,
) {
    // match on name or on digits typed, ignoring formatting in the stored number
    val digits = number.filter { it.isDigit() }
}

private sealed class Row {
    data class Section(val title: String) : Row()
    data class Person(val contact: Contact) : Row()
}

class DialActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDialBinding
    private val adapter = ContactAdapter(
        onClick = { call(it) },
        onWhatsapp = { whatsapp(it) },
        onSms = { sms(it) },
        hasWhatsapp = { whatsappPackage() != null },
    )
    private var all: List<Contact> = emptyList()
    private var recent: List<Contact> = emptyList()
    private val searchField by lazy { binding.search.findViewById<TextView>(androidx.appcompat.R.id.search_src_text) }

    // asked together on open, so the first tap on a contact dials straight out
    private val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.READ_CONTACTS] == true) load()
        else {
            showToast(getString(R.string.contacts_permission_needed))
            finish()
        }
    }

    override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? {
        val view = super.onCreateView(parent, name, context, attrs) ?: inflateFallbackView(context, name, attrs)
        if (view is TextView) view.setScriptTypeface()
        return view
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDialBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyScriptTypefaceRecursively()
        applyHomeBackground(binding.dialActivityLayout, binding.appBackground, Prefs(this))

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        attachSwipeActions()
        binding.search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                adapter.rows.filterIsInstance<Row.Person>().firstOrNull()?.let { call(it.contact) }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                searchField?.setScriptTypeface()
                filter(newText.orEmpty())
                return true
            }
        })
        binding.search.requestFocus()
        binding.addContact.setOnClickListener { addContact() }

        val missing = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) load() else ask.launch(missing.toTypedArray())
    }

    /** Swipe a contact right to call, left to WhatsApp. Section headers ignore swipes. */
    private fun attachSwipeActions() {
        val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

            override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                val row = adapter.rows.getOrNull(vh.bindingAdapterPosition)
                if (row !is Row.Person) return 0
                // no point offering WhatsApp when it is not installed
                return if (whatsappPackage() == null) ItemTouchHelper.RIGHT
                else ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val position = vh.bindingAdapterPosition
                val contact = (adapter.rows.getOrNull(position) as? Row.Person)?.contact ?: return
                // the row stays in the list either way, so put it back before acting
                adapter.notifyItemChanged(position)
                if (direction == ItemTouchHelper.RIGHT) call(contact) else whatsapp(contact)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.recyclerView)
    }

    override fun onResume() {
        super.onResume()
        // a contact may have been added, or a call placed, while we were away
        if (all.isNotEmpty()) load()
    }

    private fun load() = lifecycleScope.launch {
        val loaded = withContext(Dispatchers.IO) { readContacts() to readRecent() }
        all = loaded.first
        recent = loaded.second
        filter(binding.search.query?.toString().orEmpty())
    }

    private fun readContacts(): List<Contact> {
        val seen = HashSet<String>()
        val list = mutableListOf<Contact>()
        contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.CONTACT_ID),
            null,
            null,
            "${Phone.DISPLAY_NAME} COLLATE NOCASE ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val number = c.getString(1) ?: continue
                // same contact, same number stored twice (sim + account) is common
                if (seen.add(name + number.filter { it.isDigit() }))
                    list.add(Contact(name, number, contactId = c.getLong(2)))
            }
        }
        return list
    }

    /** Last [RECENT_COUNT] distinct numbers from the call log, newest first. */
    private fun readRecent(): List<Contact> {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED)
            return emptyList()
        val seen = HashSet<String>()
        val list = mutableListOf<Contact>()
        try {
            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE),
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                while (c.moveToNext() && list.size < RECENT_COUNT) {
                    val number = c.getString(0) ?: continue
                    val digits = number.filter { it.isDigit() }
                    if (digits.isEmpty() || !seen.add(digits)) continue
                    val date = DateUtils.getRelativeTimeSpanString(
                        c.getLong(2), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
                    )
                    val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: number
                    list.add(Contact(name, number, "$number · $date"))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun filter(query: String) {
        val q = query.trim().normalizeName()
        val qDigits = q.filter { it.isDigit() }
        // "079", "+962 79", "ahmad", "احمد" and the T9 spelling "262" all work, see ContactMatch.kt
        val matches = { c: Contact -> contactMatches(c.name, c.number, query) }
        val contacts = if (q.isEmpty()) all else all.filter(matches)
        val recents = if (q.isEmpty()) recent else recent.filter(matches)

        val rows = mutableListOf<Row>()
        // nothing matched a number you typed, so offer to dial it as is
        if (contacts.isEmpty() && recents.isEmpty() && qDigits.length >= MIN_DIALABLE_DIGITS) {
            val typed = query.trim()
            rows.add(Row.Person(Contact(getString(R.string.call_number, typed), typed, subtitle = "")))
        }
        if (recents.isNotEmpty()) {
            rows.add(Row.Section(getString(R.string.recent)))
            recents.forEach { rows.add(Row.Person(it)) }
        }
        if (contacts.isNotEmpty()) {
            if (recents.isNotEmpty()) rows.add(Row.Section(getString(R.string.contacts)))
            contacts.forEach { rows.add(Row.Person(it)) }
        }
        adapter.rows = rows
        adapter.collapse()
        adapter.notifyDataSetChanged()
        binding.recyclerView.scrollToPosition(0)
    }

    private fun call(contact: Contact) {
        // call permission denied is not fatal, fall back to the dialer with the number filled in
        val action = if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED)
            Intent.ACTION_CALL else Intent.ACTION_DIAL
        start(Intent(action, Uri.fromParts("tel", contact.number, null)))
    }

    /**
     * WhatsApp hangs its actions off the contact as data rows. Find the row by mimetype rather than
     * hardcoding it, which covers WhatsApp and WhatsApp Business in one path, and fall back to a
     * wa.me link for numbers with no contact behind them (call log entries, typed numbers).
     */
    private fun whatsapp(contact: Contact) {
        val pkg = whatsappPackage() ?: return
        // call log rows carry no contact id, look it up by number so we open the real chat
        val contactId = if (contact.contactId != 0L) contact.contactId else lookupContactId(contact.number)
        val rowId = whatsappRowId(contactId)
        if (rowId != null) {
            val intent = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, rowId))
                .setPackage(pkg)
            try {
                startActivity(intent)
                finish()
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        // wa.me needs a full international number, a local 09xx just opens the contact picker
        val international = toE164(contact.number)
        if (international == null) {
            showToast(getString(R.string.no_whatsapp_for_contact))
            return
        }
        openUrl("https://wa.me/$international")
        finish()
    }

    private fun whatsappRowId(contactId: Long): Long? {
        if (contactId == 0L) return null
        return try {
            contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID, ContactsContract.Data.MIMETYPE),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} LIKE ?",
                arrayOf(contactId.toString(), "%whatsapp%"),
                null
            )?.use { c ->
                var fallback: Long? = null
                while (c.moveToNext()) {
                    val mime = c.getString(1) ?: continue
                    // .profile opens the chat, which is what people mean by "WhatsApp this contact"
                    if (mime.endsWith(".profile")) return c.getLong(0)
                    if (fallback == null) fallback = c.getLong(0)
                }
                fallback
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun lookupContactId(number: String): Long = try {
        contentResolver.query(
            Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
            arrayOf(ContactsContract.PhoneLookup.CONTACT_ID),
            null,
            null,
            null
        )?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
    } catch (e: Exception) {
        e.printStackTrace()
        0L
    }

    /** Digits only, in international form, using the SIM's country for local numbers. */
    private fun toE164(number: String): String? {
        val digits = number.filter { it.isDigit() }
        if (number.trim().startsWith("+")) return digits
        val telephony = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager
        val country = telephony?.simCountryIso?.takeIf { it.isNotBlank() }
            ?: telephony?.networkCountryIso?.takeIf { it.isNotBlank() }
            ?: return null
        val formatted = PhoneNumberUtils.formatNumberToE164(number, country.uppercase())
        return formatted?.filter { it.isDigit() }
    }

    private fun whatsappPackage(): String? = WHATSAPP_PACKAGES.firstOrNull { pkg ->
        try {
            packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun sms(contact: Contact) =
        start(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", contact.number, null)))

    private fun addContact() {
        val query = binding.search.query?.toString()?.trim().orEmpty()
        if (query.isEmpty()) {
            showToast(getString(R.string.type_a_number_to_add))
            return
        }
        val intent = Intent(Intent.ACTION_INSERT).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            // digits go in the phone field, anything else is treated as a name
            if (query.any { it.isDigit() }) putExtra(ContactsContract.Intents.Insert.PHONE, query)
            else putExtra(ContactsContract.Intents.Insert.NAME, query)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            showToast(getString(R.string.app_not_found))
        }
    }

    private fun start(intent: Intent) {
        try {
            startActivity(intent)
            finish()
        } catch (e: Exception) {
            showToast(getString(R.string.app_not_found))
        }
    }
}

private class ContactAdapter(
    private val onClick: (Contact) -> Unit,
    private val onWhatsapp: (Contact) -> Unit,
    private val onSms: (Contact) -> Unit,
    private val hasWhatsapp: () -> Boolean,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var rows: List<Row> = emptyList()

    // which row has its action strip open, adapter state so only ever one is open and a rebind
    // (scroll, filter) cannot leave a stale strip behind
    private var expanded = RecyclerView.NO_POSITION
    private val handler = Handler(Looper.getMainLooper())
    private val autoCollapse = Runnable { collapse() }

    fun collapse() {
        handler.removeCallbacks(autoCollapse)
        val previous = expanded
        expanded = RecyclerView.NO_POSITION
        if (previous != RecyclerView.NO_POSITION) notifyItemChanged(previous)
    }

    class PersonHolder(val binding: AdapterContactBinding) : RecyclerView.ViewHolder(binding.root)
    class SectionHolder(val binding: AdapterSectionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int) = if (rows[position] is Row.Section) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 1) SectionHolder(AdapterSectionBinding.inflate(inflater, parent, false))
        else PersonHolder(AdapterContactBinding.inflate(inflater, parent, false))
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Section -> (holder as SectionHolder).binding.sectionTitle.apply {
                text = row.title
                setScriptTypeface()
            }
            is Row.Person -> with((holder as PersonHolder).binding) {
                contactName.text = row.contact.name
                contactName.setScriptTypeface()
                contactNumber.text = row.contact.subtitle
                contactNumber.setScriptTypeface()
                contactNumber.isVisible = row.contact.subtitle.isNotBlank()
                // the strip replaces the row rather than sitting on top of it, otherwise the name
                // and number read through from behind
                val open = position == expanded
                contactActionsLayout.isVisible = open
                contactRow.visibility = if (open) View.INVISIBLE else View.VISIBLE
                actionWhatsapp.isVisible = hasWhatsapp()
                contactRow.setOnClickListener { onClick(row.contact) }
                contactRow.setOnLongClickListener {
                    val previous = expanded
                    expanded = holder.bindingAdapterPosition
                    if (previous != RecyclerView.NO_POSITION) notifyItemChanged(previous)
                    notifyItemChanged(expanded)
                    // put the name back by itself if the strip goes unused
                    handler.removeCallbacks(autoCollapse)
                    handler.postDelayed(autoCollapse, ACTIONS_TIMEOUT_MS)
                    true
                }
                actionCall.setOnClickListener { collapse(); onClick(row.contact) }
                actionWhatsapp.setOnClickListener { collapse(); onWhatsapp(row.contact) }
                actionSms.setOnClickListener { collapse(); onSms(row.contact) }
                // tapping the strip anywhere else dismisses it
                contactActionsLayout.setOnClickListener { collapse() }
            }
        }
    }
}
