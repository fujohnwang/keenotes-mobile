package cn.keevol.keenotes.ui

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import cn.keevol.keenotes.KeeNotesApp
import cn.keevol.keenotes.R
import cn.keevol.keenotes.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    
    // Tab views
    private lateinit var tabNote: LinearLayout
    private lateinit var tabReview: LinearLayout
    private lateinit var tabSettings: LinearLayout
    
    // Selected color (primary/accent)
    private val selectedColor by lazy { ContextCompat.getColor(this, R.color.primary) }
    // Unselected color
    private val unselectedColor by lazy { ContextCompat.getColor(this, R.color.text_secondary) }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupNavigation()
        setupCustomTabBar()
        connectWebSocket()
    }
    
    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
        
        // Listen to navigation changes to update tab selection
        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.noteFragment -> selectTab(0)
                R.id.reviewFragment -> selectTab(1)
                R.id.settingsFragment -> selectTab(2)
            }
        }
    }
    
    private fun setupCustomTabBar() {
        tabNote = binding.tabNote
        tabReview = binding.tabReview
        tabSettings = binding.tabSettings
        
        tabNote.setOnClickListener {
            navController.navigate(R.id.noteFragment)
        }
        
        tabReview.setOnClickListener {
            navController.navigate(R.id.reviewFragment)
        }
        
        tabSettings.setOnClickListener {
            navController.navigate(R.id.settingsFragment)
        }
        
        // Select first tab by default
        selectTab(0)
    }
    
    private fun selectTab(index: Int) {
        // Reset all tabs
        resetTab(tabNote, binding.tabNoteIcon, binding.tabNoteText)
        resetTab(tabReview, binding.tabReviewIcon, binding.tabReviewText)
        resetTab(tabSettings, binding.tabSettingsIcon, binding.tabSettingsText)
        
        // Select the target tab
        when (index) {
            0 -> highlightTab(tabNote, binding.tabNoteIcon, binding.tabNoteText)
            1 -> highlightTab(tabReview, binding.tabReviewIcon, binding.tabReviewText)
            2 -> highlightTab(tabSettings, binding.tabSettingsIcon, binding.tabSettingsText)
        }
    }
    
    private fun resetTab(tab: LinearLayout, icon: ImageView, text: TextView) {
        tab.isSelected = false
        icon.setColorFilter(unselectedColor)
        text.setTextColor(unselectedColor)
    }
    
    private fun highlightTab(tab: LinearLayout, icon: ImageView, text: TextView) {
        tab.isSelected = true
        icon.setColorFilter(selectedColor)
        text.setTextColor(selectedColor)
    }
    
    /**
     * Navigate to Note tab - called from other fragments
     */
    fun navigateToNote() {
        tabNote.performClick()
    }
    
    private fun connectWebSocket() {
        val app = application as KeeNotesApp
        
        lifecycleScope.launch {
            if (app.settingsRepository.isConfigured()) {
                app.webSocketService.connect()
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        (application as KeeNotesApp).webSocketService.disconnect()
    }
}
