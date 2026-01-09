package cn.keevol.keenotes.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.keevol.keenotes.data.dao.NoteDao
import cn.keevol.keenotes.data.entity.Note
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit

@OptIn(FlowPreview::class)
class ReviewViewModel(private val noteDao: NoteDao) : ViewModel() {
    
    // Search query from MainActivity
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    
    // Selected period
    private val _selectedPeriod = MutableStateFlow("7 days")
    val selectedPeriod: StateFlow<String> = _selectedPeriod.asStateFlow()
    
    // Notes result
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()
    
    // Loading state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    init {
        // Combine search query and period, with debounce for search
        viewModelScope.launch {
            combine(
                _searchQuery.debounce(500), // 500ms debounce for search
                _selectedPeriod
            ) { query, period ->
                Pair(query, period)
            }.collectLatest { (query, period) ->
                loadNotes(query, period)
            }
        }
    }
    
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }
    
    fun setSelectedPeriod(period: String) {
        _selectedPeriod.value = period
    }
    
    private suspend fun loadNotes(query: String, period: String) {
        _isLoading.value = true
        
        try {
            val result = if (query.isNotBlank()) {
                // Search mode
                noteDao.searchNotes(query)
            } else {
                // Period mode
                val days = when (period) {
                    "30 days" -> 30
                    "90 days" -> 90
                    "All" -> 3650
                    else -> 7
                }
                val since = Instant.now().minus(days.toLong(), ChronoUnit.DAYS).toString()
                noteDao.getNotesForReview(since)
            }
            
            _notes.value = result
        } catch (e: Exception) {
            // Handle error
            _notes.value = emptyList()
        } finally {
            _isLoading.value = false
        }
    }
    
    fun refresh() {
        viewModelScope.launch {
            loadNotes(_searchQuery.value, _selectedPeriod.value)
        }
    }
}
