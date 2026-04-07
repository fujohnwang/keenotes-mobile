package cn.keevol.keenotes.ui.note

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import cn.keevol.keenotes.KeeNotesApp
import cn.keevol.keenotes.R
import cn.keevol.keenotes.data.entity.Note
import cn.keevol.keenotes.databinding.FragmentOnThisDayBinding
import cn.keevol.keenotes.ui.common.EnlargedNoteDismissGesture
import cn.keevol.keenotes.ui.review.NotesAdapter
import cn.keevol.keenotes.util.DateTimeUtil
import cn.keevol.keenotes.util.ZeroWidthSteganography
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class OnThisDayFragment : Fragment() {

    private var _binding: FragmentOnThisDayBinding? = null
    private val binding get() = _binding!!

    private val notesAdapter = NotesAdapter { note -> showEnlargedNote(note) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOnThisDayBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupHeader()
        setupRecyclerView()
        setupEnlargedCardDismissGesture()
        observeNotes()
    }

    private fun setupHeader() {
        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupRecyclerView() {
        binding.notesRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.notesRecyclerView.adapter = notesAdapter
    }

    private fun observeNotes() {
        val app = requireActivity().application as KeeNotesApp

        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                app.database.noteDao().getNotesOnThisDayFlow(),
                app.settingsRepository.debugMockOnThisDay
            ) { realNotes, debugMockEnabled ->
                if (debugMockEnabled) {
                    OnThisDayDebugMock.build()
                } else {
                    realNotes
                }
            }.collectLatest { notes ->
                if (_binding == null) return@collectLatest
                val isEnlargedVisible = binding.enlargedNoteContainer.root.visibility == View.VISIBLE

                binding.countText.text = getString(R.string.on_this_day_count, notes.size)
                binding.emptyText.visibility =
                    if (notes.isEmpty() && !isEnlargedVisible) View.VISIBLE else View.GONE
                binding.notesRecyclerView.visibility =
                    if (notes.isNotEmpty() && !isEnlargedVisible) View.VISIBLE else View.GONE
                notesAdapter.submitList(notes)
            }
        }
    }

    private fun showEnlargedNote(note: Note) {
        if (_binding == null) return

        val container = binding.enlargedNoteContainer.root
        binding.enlargedNoteContainer.enlargedDateText.text = DateTimeUtil.utcToLocalDisplay(note.createdAt)
        val channelText = if (note.channel.isNotEmpty()) note.channel else "default"
        binding.enlargedNoteContainer.enlargedChannelText.text = "• $channelText"
        binding.enlargedNoteContainer.enlargedContentText.text = note.content

        binding.enlargedNoteContainer.enlargedContentText.setOnClickListener {
            copyToClipboard(note.content)
        }

        binding.enlargedNoteContainer.shrinkButton.setOnClickListener {
            hideEnlargedNote()
        }

        binding.emptyText.visibility = View.GONE
        binding.notesRecyclerView.visibility = View.GONE
        container.visibility = View.VISIBLE
        container.alpha = 0f
        container.animate().alpha(1f).setDuration(200).start()
    }

    private fun hideEnlargedNote() {
        if (_binding == null) return

        val container = binding.enlargedNoteContainer.root
        container.animate().alpha(0f).setDuration(200).withEndAction {
            if (_binding != null) {
                container.visibility = View.GONE
                binding.notesRecyclerView.visibility = View.VISIBLE
                if (notesAdapter.currentList.isEmpty()) {
                    binding.emptyText.visibility = View.VISIBLE
                    binding.notesRecyclerView.visibility = View.GONE
                }
            }
        }.start()
    }

    private fun setupEnlargedCardDismissGesture() {
        EnlargedNoteDismissGesture.attach(binding.enlargedNoteContainer) {
            if (binding.enlargedNoteContainer.root.visibility == View.VISIBLE) {
                hideEnlargedNote()
            }
        }
    }

    private fun copyToClipboard(content: String) {
        val context = requireContext()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val hiddenMessage = runBlocking {
            (context.applicationContext as KeeNotesApp).settingsRepository.getHiddenMessage()
        }
        val clip = ClipData.newPlainText("note", ZeroWidthSteganography.embedIfNeeded(content, hiddenMessage))
        clipboard.setPrimaryClip(clip)

        val inflater = LayoutInflater.from(context)
        val layout = inflater.inflate(R.layout.toast_copied, null)
        Toast(context).apply {
            duration = Toast.LENGTH_SHORT
            view = layout
            setGravity(Gravity.CENTER, 0, 0)
            show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
