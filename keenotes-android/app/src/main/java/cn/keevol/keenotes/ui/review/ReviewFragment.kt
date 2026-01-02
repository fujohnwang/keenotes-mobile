package cn.keevol.keenotes.ui.review

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import cn.keevol.keenotes.KeeNotesApp
import cn.keevol.keenotes.R
import cn.keevol.keenotes.data.entity.Note
import cn.keevol.keenotes.databinding.FragmentReviewBinding
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit

class ReviewFragment : Fragment() {
    
    private var _binding: FragmentReviewBinding? = null
    private val binding get() = _binding!!
    
    private val notesAdapter = NotesAdapter()
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReviewBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupHeader()
        setupPeriodSelector()
        setupRecyclerView()
        loadNotes("7 days")
    }
    
    private fun setupHeader() {
        binding.btnBack.setOnClickListener {
            findNavController().popBackStack()
        }
    }
    
    private fun setupPeriodSelector() {
        val periods = arrayOf("7 days", "30 days", "90 days", "All")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, periods)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.periodSpinner.adapter = adapter
        
        binding.periodSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                loadNotes(periods[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    private fun setupRecyclerView() {
        binding.notesRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = notesAdapter
        }
    }
    
    private fun loadNotes(period: String) {
        val app = requireActivity().application as KeeNotesApp
        
        binding.loadingText.visibility = View.VISIBLE
        binding.notesRecyclerView.visibility = View.GONE
        binding.emptyText.visibility = View.GONE
        
        lifecycleScope.launch {
            val days = when (period) {
                "30 days" -> 30
                "90 days" -> 90
                "All" -> 3650
                else -> 7
            }
            
            val since = Instant.now().minus(days.toLong(), ChronoUnit.DAYS).toString()
            val notes = app.database.noteDao().getNotesForReview(since)
            
            binding.loadingText.visibility = View.GONE
            
            if (notes.isEmpty()) {
                binding.emptyText.visibility = View.VISIBLE
                binding.emptyText.text = "No notes found for $period"
            } else {
                binding.notesRecyclerView.visibility = View.VISIBLE
                binding.countText.text = "${notes.size} note(s)"
                notesAdapter.submitList(notes)
            }
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
