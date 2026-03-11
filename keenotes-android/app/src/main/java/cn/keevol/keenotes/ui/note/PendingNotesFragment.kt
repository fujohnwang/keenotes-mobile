package cn.keevol.keenotes.ui.note

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import cn.keevol.keenotes.KeeNotesApp
import cn.keevol.keenotes.databinding.FragmentPendingNotesBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PendingNotesFragment : Fragment() {

    private var _binding: FragmentPendingNotesBinding? = null
    private val binding get() = _binding!!
    private val adapter = PendingNotesAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPendingNotesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }

        binding.pendingRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.pendingRecyclerView.adapter = adapter

        val app = requireActivity().application as KeeNotesApp
        viewLifecycleOwner.lifecycleScope.launch {
            app.pendingNoteService.pendingNotesFlow.collectLatest { notes ->
                if (_binding != null) {
                    adapter.submitList(notes)
                    binding.emptyText.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
                    binding.pendingRecyclerView.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
