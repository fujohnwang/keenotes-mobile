package cn.keevol.keenotes.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import cn.keevol.keenotes.data.dao.NoteDao

class ReviewViewModelFactory(
    private val noteDao: NoteDao
) : ViewModelProvider.Factory {
    
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ReviewViewModel::class.java)) {
            return ReviewViewModel(noteDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
