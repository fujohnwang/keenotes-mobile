package cn.keevol.keenotes.share

import android.app.Dialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import cn.keevol.keenotes.KeeNotesApp
import cn.keevol.keenotes.R
import cn.keevol.keenotes.data.entity.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class NoteShareDialogFragment : DialogFragment() {
    private lateinit var note: Note
    private lateinit var posterImageView: ImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var refreshButton: ImageButton
    private lateinit var savePosterButton: ImageButton
    private lateinit var sharePosterButton: ImageButton
    private lateinit var saveVideoButton: ImageButton
    private lateinit var closeButton: ImageButton

    private var hiddenMessage: String = ""
    private var inkTheme: PosterInkTheme = PosterInkTheme.MEI
    private var currentPoster: Bitmap? = null
    private var renderJob: Job? = null
    private var statusJob: Job? = null
    private var isBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        note = requireArguments().toNote()
        inkTheme = PosterInkTheme.stableForNoteId(note.id)
        setStyle(STYLE_NO_TITLE, android.R.style.Theme_Material_NoActionBar)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return Dialog(requireContext(), theme).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return createContentView()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadSettingsAndRender()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    override fun onDestroyView() {
        statusJob?.cancel()
        renderJob?.cancel()
        currentPoster = null
        super.onDestroyView()
    }

    private fun createContentView(): View {
        val context = requireContext()
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setOnClickListener { dismissAllowingStateLoss() }
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        root.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        column.addView(toolbar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48)
        ))

        refreshButton = createIconButton(R.drawable.ic_refresh, "Change poster background") {
            if (!isBusy) {
                inkTheme = inkTheme.next()
                refreshPreview()
            }
        }
        toolbar.addView(refreshButton)

        toolbar.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))

        savePosterButton = createIconButton(R.drawable.ic_download, "Save poster") {
            savePoster()
        }
        toolbar.addView(savePosterButton)

        sharePosterButton = createIconButton(R.drawable.ic_share, "Share poster") {
            sharePoster()
        }
        toolbar.addView(sharePosterButton)

        saveVideoButton = createIconButton(R.drawable.ic_video, "Save video") {
            saveVideo()
        }
        toolbar.addView(saveVideoButton)

        closeButton = createIconButton(R.drawable.ic_close_fullscreen, "Close") {
            dismissAllowingStateLoss()
        }
        toolbar.addView(closeButton)

        val scrollView = ScrollView(context).apply {
            overScrollMode = ScrollView.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = false
            setPadding(0, dp(12), 0, dp(56))
        }
        val previewFrame = FrameLayout(context)
        scrollView.addView(previewFrame, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val previewWidth = (resources.displayMetrics.widthPixels - dp(40)).coerceAtMost(dp(390))
        posterImageView = ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        previewFrame.addView(posterImageView, FrameLayout.LayoutParams(
            previewWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ))
        column.addView(scrollView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        statusText = TextView(context).apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(context, R.drawable.bg_share_status)
        }
        root.addView(statusText, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            bottomMargin = dp(36)
            leftMargin = dp(20)
            rightMargin = dp(20)
        })

        progressBar = ProgressBar(context).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        root.addView(progressBar, FrameLayout.LayoutParams(
            dp(48),
            dp(48),
            Gravity.CENTER
        ))

        setBusy(true)
        return root
    }

    private fun createIconButton(
        @DrawableRes iconRes: Int,
        description: String,
        onClick: () -> Unit
    ): ImageButton {
        return ImageButton(requireContext()).apply {
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_share_dialog_button)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = description
            setOnClickListener {
                it.isPressed = false
                onClick()
            }
            val margin = dp(4)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                leftMargin = margin
            }
        }
    }

    private fun loadSettingsAndRender() {
        val app = requireActivity().application as KeeNotesApp
        viewLifecycleOwner.lifecycleScope.launch {
            hiddenMessage = withContext(Dispatchers.IO) {
                app.settingsRepository.getHiddenMessage()
            }
            refreshPreview()
        }
    }

    private fun refreshPreview() {
        renderJob?.cancel()
        setBusy(true)
        showStatus("Rendering poster...", autoHide = false)
        val appContext = requireContext().applicationContext
        val noteSnapshot = note
        val hiddenSnapshot = hiddenMessage
        val themeSnapshot = inkTheme
        renderJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val poster = withContext(Dispatchers.Default) {
                    NotePosterRenderer.renderPosterImage(appContext, noteSnapshot, hiddenSnapshot, themeSnapshot)
                }
                currentPoster = poster
                posterImageView.setImageBitmap(poster)
                showStatus("Background: ${themeSnapshot.label}", autoHide = true)
            } catch (e: Exception) {
                showStatus("Poster render failed", autoHide = false)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun savePoster() {
        val poster = currentPoster ?: return
        setBusy(true)
        showStatus("Saving poster...", autoHide = false)
        val appContext = requireContext().applicationContext
        val displayName = defaultFileName(".png")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    PosterMediaStore.savePoster(appContext, poster, displayName)
                }
                showStatus("Poster saved to Photos", autoHide = true)
            } catch (e: Exception) {
                showStatus("Poster save failed", autoHide = true)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun sharePoster() {
        val poster = currentPoster ?: return
        setBusy(true)
        showStatus("Preparing poster...", autoHide = false)
        val appContext = requireContext().applicationContext
        val displayName = defaultFileName(".png")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    PosterMediaStore.cachePosterForShare(appContext, poster, displayName)
                }
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("KeeNotes poster", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Share poster"))
                showStatus("Poster ready", autoHide = true)
            } catch (e: Exception) {
                showStatus("Poster share failed", autoHide = true)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun saveVideo() {
        val poster = currentPoster ?: return
        setBusy(true)
        showStatus("Generating video...", autoHide = false)
        val appContext = requireContext().applicationContext
        val displayName = defaultFileName(".mp4")
        viewLifecycleOwner.lifecycleScope.launch {
            var videoFrame: Bitmap? = null
            var outputFile: java.io.File? = null
            try {
                videoFrame = withContext(Dispatchers.Default) {
                    NotePosterRenderer.renderVideoFrame(poster)
                }
                outputFile = PosterVideoExporter.exportToCache(appContext, videoFrame, displayName.removeSuffix(".mp4"))
                withContext(Dispatchers.IO) {
                    PosterMediaStore.saveVideo(appContext, outputFile, displayName)
                }
                showStatus("Video saved to Photos", autoHide = true)
            } catch (e: Exception) {
                showStatus("Video export failed", autoHide = true)
            } finally {
                outputFile?.delete()
                videoFrame?.recycle()
                setBusy(false)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        isBusy = busy
        if (::progressBar.isInitialized) {
            progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        }
        listOfNotNull(
            refreshButtonOrNull(),
            savePosterButtonOrNull(),
            sharePosterButtonOrNull(),
            saveVideoButtonOrNull()
        ).forEach {
            it.isEnabled = !busy
            it.alpha = if (busy) 0.45f else 1f
        }
        if (::closeButton.isInitialized) {
            closeButton.isEnabled = true
            closeButton.alpha = 1f
        }
    }

    private fun showStatus(message: String, autoHide: Boolean) {
        if (!::statusText.isInitialized) return
        statusJob?.cancel()
        statusText.text = message
        statusText.visibility = View.VISIBLE
        if (autoHide) {
            statusJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(1_800)
                statusText.visibility = View.GONE
            }
        }
    }

    private fun defaultFileName(extension: String): String {
        val timestamp = LocalDateTime.now().format(SAVE_TIMESTAMP_FORMATTER)
        val id = if (note.id > 0) note.id.toString() else "draft"
        return "keenotes-$id-$timestamp$extension".replace(Regex("[^A-Za-z0-9._-]"), "-")
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun refreshButtonOrNull() = if (::refreshButton.isInitialized) refreshButton else null
    private fun savePosterButtonOrNull() = if (::savePosterButton.isInitialized) savePosterButton else null
    private fun sharePosterButtonOrNull() = if (::sharePosterButton.isInitialized) sharePosterButton else null
    private fun saveVideoButtonOrNull() = if (::saveVideoButton.isInitialized) saveVideoButton else null

    companion object {
        private const val TAG = "NoteShareDialog"
        private const val ARG_ID = "id"
        private const val ARG_CONTENT = "content"
        private const val ARG_CHANNEL = "channel"
        private const val ARG_CREATED_AT = "createdAt"
        private val SAVE_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")

        fun show(fragmentManager: FragmentManager, note: Note) {
            newInstance(note).show(fragmentManager, TAG)
        }

        private fun newInstance(note: Note): NoteShareDialogFragment {
            return NoteShareDialogFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_ID, note.id)
                    putString(ARG_CONTENT, note.content)
                    putString(ARG_CHANNEL, note.channel)
                    putString(ARG_CREATED_AT, note.createdAt)
                }
            }
        }

        private fun Bundle.toNote(): Note {
            return Note(
                id = getLong(ARG_ID),
                content = getString(ARG_CONTENT).orEmpty(),
                channel = getString(ARG_CHANNEL).orEmpty(),
                createdAt = getString(ARG_CREATED_AT).orEmpty()
            )
        }
    }
}
