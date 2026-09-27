package com.munjed.husk.ui

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.munjed.husk.R
import com.munjed.husk.data.Prefs
import com.munjed.husk.databinding.ActivityRecordingsBinding
import com.munjed.husk.databinding.AdapterRecordingBinding
import com.munjed.husk.helper.applyHomeBackground
import com.munjed.husk.helper.inflateFallbackView
import com.munjed.husk.helper.playbackTime
import com.munjed.husk.helper.recordingSize
import com.munjed.husk.helper.recordingTitle
import com.munjed.husk.helper.recordings
import com.munjed.husk.helper.setScriptTypeface
import com.munjed.husk.helper.showToast
import java.io.File

/**
 * Tap to play, long press to share or delete. The only way to reach a recording, by design. The
 * playing row grows a timeline: drag to scrub, or step 10 s either way.
 */
class RecordingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordingsBinding
    private var player: MediaPlayer? = null
    private var playing: File? = null
    private val handler = Handler(Looper.getMainLooper())

    private val adapter = RecordingAdapter(
        onClick = { toggle(it) },
        onShare = { share(it) },
        onDelete = { delete(it) },
        isPlaying = { it == playing },
        player = { player },
    )

    // ponytail: redraws only the playing row's timeline, 4x a second; fine for a list this short
    private val tick = object : Runnable {
        override fun run() {
            val index = adapter.files.indexOf(playing)
            if (index >= 0) adapter.notifyItemChanged(index, RecordingAdapter.PROGRESS)
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? {
        val view = super.onCreateView(parent, name, context, attrs) ?: inflateFallbackView(context, name, attrs)
        if (view is TextView) view.setScriptTypeface()
        return view
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyHomeBackground(binding.recordingsActivityLayout, binding.appBackground, Prefs(this))

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        render()
    }

    override fun onStop() {
        stopPlayback()
        super.onStop()
    }

    private fun render() {
        adapter.files = recordings(this)
        adapter.notifyDataSetChanged()
        binding.empty.isVisible = adapter.files.isEmpty()
    }

    private fun toggle(file: File) {
        val wasPlaying = playing == file
        stopPlayback()
        if (wasPlaying) return
        val mp = MediaPlayer()
        val started = runCatching {
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            mp.setOnCompletionListener { stopPlayback() }
            mp.start()
        }.isSuccess
        if (!started) {
            mp.release()
            showToast(R.string.recorder_failed)
            return
        }
        player = mp
        playing = file
        adapter.notifyDataSetChanged()
        handler.postDelayed(tick, TICK_MS)
    }

    private fun stopPlayback() {
        handler.removeCallbacks(tick)
        player?.let { runCatching { it.stop() } }
        player?.release()
        player = null
        playing = null
        adapter.notifyDataSetChanged()
    }

    /**
     * Private storage has no path another app can open, so sharing hands out a one-shot grant to the
     * single file through Husk's own provider. Nothing else becomes reachable.
     */
    private fun share(file: File) {
        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.files", file)
        }.getOrNull() ?: return
        val intent = Intent(Intent.ACTION_SEND)
            .setType("audio/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(intent, getString(R.string.share))) }
            .onFailure { showToast(R.string.app_not_found) }
    }

    private fun delete(file: File) {
        if (playing == file) stopPlayback()
        file.delete()
        render()
    }
}

private const val TICK_MS = 250L
private const val SKIP_MS = 10_000

private class RecordingAdapter(
    private val onClick: (File) -> Unit,
    private val onShare: (File) -> Unit,
    private val onDelete: (File) -> Unit,
    private val isPlaying: (File) -> Boolean,
    private val player: () -> MediaPlayer?,
) : RecyclerView.Adapter<RecordingAdapter.Holder>() {

    companion object {
        const val PROGRESS = "progress"
    }

    var files: List<File> = emptyList()
    private var menuFile: File? = null // the row whose long-press menu is open, one at a time

    class Holder(val binding: AdapterRecordingBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(AdapterRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = files.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val file = files[position]
        with(holder.binding) {
            rowTitle.text = recordingTitle(file.name)
            rowTitle.setScriptTypeface()
            val size = recordingSize(file.length())
            rowDetail.text =
                if (isPlaying(file)) root.context.getString(R.string.playing, size) else size
            rowLayout.setOnClickListener { onClick(file) }
            rowLayout.setOnLongClickListener { showMenu(file); true }
            menu.isVisible = file == menuFile
            menuShare.setOnClickListener { showMenu(null); onShare(file) }
            menuDelete.setOnClickListener { showMenu(null); onDelete(file) }
            menuClose.setOnClickListener { showMenu(null) }

            controls.isVisible = isPlaying(file)
            if (!controls.isVisible) return
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    player()?.seekTo(progress) // live, so dragging is audible scrubbing
                    time.text = timeLabel(progress, bar.max)
                }

                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
            back.setOnClickListener { skip(-SKIP_MS); bindProgress(holder) }
            forward.setOnClickListener { skip(SKIP_MS); bindProgress(holder) }
            pause.setOnClickListener {
                player()?.let { if (it.isPlaying) it.pause() else it.start() }
                bindProgress(holder)
            }
            bindProgress(holder)
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (PROGRESS in payloads) bindProgress(holder) else onBindViewHolder(holder, position)
    }

    private fun bindProgress(holder: Holder) = with(holder.binding) {
        val mp = player() ?: return
        seek.max = mp.duration
        if (!seek.isPressed) seek.progress = mp.currentPosition // never yank the thumb from a finger
        time.text = timeLabel(mp.currentPosition, mp.duration)
        pause.setImageResource(if (mp.isPlaying) R.drawable.ic_media_pause else R.drawable.ic_media_play)
    }

    private fun showMenu(file: File?) {
        menuFile = file
        notifyDataSetChanged()
    }

    private fun skip(deltaMs: Int) {
        val mp = player() ?: return
        mp.seekTo((mp.currentPosition + deltaMs).coerceIn(0, mp.duration))
    }

    private fun timeLabel(position: Int, duration: Int) = "${playbackTime(position)} / ${playbackTime(duration)}"
}
