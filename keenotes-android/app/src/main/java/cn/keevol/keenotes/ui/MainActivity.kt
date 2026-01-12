package cn.keevol.keenotes.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import cn.keevol.keenotes.KeeNotesApp
import cn.keevol.keenotes.R
import cn.keevol.keenotes.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupNavigation()
        connectWebSocket()
    }
    
    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        
        // Setup BottomNavigationView with NavController
        binding.bottomNavigation.setupWithNavController(navController)
        
        // Disable the Material 3 active indicator (the pill-shaped background on icon)
        binding.bottomNavigation.isItemActiveIndicatorEnabled = false
        
        // Force icon tint to use our color selector (override Material 3 defaults)
        val iconTint = androidx.appcompat.content.res.AppCompatResources.getColorStateList(
            this, R.color.bottom_nav_item_color
        )
        binding.bottomNavigation.itemIconTintList = iconTint
        binding.bottomNavigation.itemTextColor = iconTint
        
        // Reduce icon-label spacing by adjusting label margins
        binding.bottomNavigation.post {
            reduceIconLabelSpacing()
        }
    }
    
    private fun reduceIconLabelSpacing() {
        val menuView = binding.bottomNavigation.getChildAt(0) as? android.view.ViewGroup ?: return
        for (i in 0 until menuView.childCount) {
            val itemView = menuView.getChildAt(i)
            // Find the labels (small and large) and reduce their top margin
            try {
                val smallLabelField = itemView.javaClass.getDeclaredField("smallLabel")
                smallLabelField.isAccessible = true
                val smallLabel = smallLabelField.get(itemView) as? android.widget.TextView
                smallLabel?.let {
                    val params = it.layoutParams as? android.view.ViewGroup.MarginLayoutParams
                    params?.topMargin = 0
                    it.layoutParams = params
                }
                
                val largeLabelField = itemView.javaClass.getDeclaredField("largeLabel")
                largeLabelField.isAccessible = true
                val largeLabel = largeLabelField.get(itemView) as? android.widget.TextView
                largeLabel?.let {
                    val params = it.layoutParams as? android.view.ViewGroup.MarginLayoutParams
                    params?.topMargin = 0
                    it.layoutParams = params
                }
            } catch (e: Exception) {
                // Ignore if reflection fails
            }
        }
    }
    
    /**
     * Navigate to Note tab
     * Simply trigger the bottom navigation selection
     */
    fun navigateToNote() {
        binding.bottomNavigation.post {
            binding.bottomNavigation.selectedItemId = R.id.noteFragment
        }
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
