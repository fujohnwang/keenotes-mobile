package cn.keevol.keenotes.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
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
import cn.keevol.keenotes.network.WebSocketService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var swipeGestureDetector: GestureDetector
    
    // Selected color (primary/accent)
    private val selectedColor by lazy { ContextCompat.getColor(this, R.color.primary) }
    // Unselected color
    private val unselectedColor by lazy { ContextCompat.getColor(this, R.color.text_secondary) }
    
    /** Current main tab index (0=Note, 1=Review, 2=Settings), -1 for sub-pages */
    private var currentTabIndex = 0
    private val tabCount = 3
    
    /** Destination IDs of the 3 main tabs, ordered by index */
    private val mainTabDestinations by lazy {
        listOf(R.id.noteFragment, R.id.reviewFragment, R.id.settingsFragment)
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupNavigation()
        setupCustomTabBar()
        setupSwipeGesture()
        checkConfigurationAndNavigate()
        connectWebSocket()
        observeSyncStateForScreenWake()
    }
    
    /**
     * 检查配置状态，如果未配置则跳转到设置页面
     */
    private fun checkConfigurationAndNavigate() {
        val app = application as KeeNotesApp
        
        lifecycleScope.launch {
            val isConfigured = app.settingsRepository.isConfigured()
            if (!isConfigured) {
                // 跳转到设置页面
                navController.navigate(R.id.settingsFragment)
            }
        }
    }
    
    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
        
        // Listen to navigation changes to update tab selection
        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.noteFragment -> { currentTabIndex = 0; selectTab(0) }
                R.id.reviewFragment -> { currentTabIndex = 1; selectTab(1) }
                R.id.settingsFragment -> { currentTabIndex = 2; selectTab(2) }
                else -> currentTabIndex = -1 // sub-page, disable swipe
            }
        }
    }
    
    private fun setupCustomTabBar() {
        // Set click listeners
        binding.tabNote.setOnClickListener {
            navController.navigate(R.id.noteFragment)
        }
        
        binding.tabReview.setOnClickListener {
            navController.navigate(R.id.reviewFragment)
        }
        
        binding.tabSettings.setOnClickListener {
            navController.navigate(R.id.settingsFragment)
        }
        
        // Select first tab by default
        selectTab(0)
    }
    
    /**
     * 在主内容区域添加左右滑动手势，用于在 dock tab 之间切换。
     * 通过 Activity 级别的 dispatchTouchEvent 拦截，避免被 Fragment 内部的
     * ScrollView/RecyclerView 消费掉触摸事件。
     */
    private fun setupSwipeGesture() {
        swipeGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 100
            private val SWIPE_VELOCITY_THRESHOLD = 100

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                if (currentTabIndex < 0) return false

                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                if (abs(diffX) <= abs(diffY) * 1.5) return false
                if (abs(diffX) < SWIPE_THRESHOLD || abs(velocityX) < SWIPE_VELOCITY_THRESHOLD) return false

                val targetIndex = if (diffX < 0) {
                    (currentTabIndex + 1).coerceAtMost(tabCount - 1)
                } else {
                    (currentTabIndex - 1).coerceAtLeast(0)
                }

                if (targetIndex != currentTabIndex) {
                    navController.navigate(mainTabDestinations[targetIndex])
                }
                return true
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Let the gesture detector see every touch event before fragments handle it
        if (::swipeGestureDetector.isInitialized) {
            swipeGestureDetector.onTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }
    
    private fun selectTab(index: Int) {
        // Reset all tabs
        resetTab(binding.tabNote, binding.tabNoteIcon, binding.tabNoteText, binding.tabNoteDot)
        resetTab(binding.tabReview, binding.tabReviewIcon, binding.tabReviewText, binding.tabReviewDot)
        resetTab(binding.tabSettings, binding.tabSettingsIcon, binding.tabSettingsText, binding.tabSettingsDot)
        
        // Select the target tab
        when (index) {
            0 -> highlightTab(binding.tabNote, binding.tabNoteIcon, binding.tabNoteText, binding.tabNoteDot)
            1 -> highlightTab(binding.tabReview, binding.tabReviewIcon, binding.tabReviewText, binding.tabReviewDot)
            2 -> highlightTab(binding.tabSettings, binding.tabSettingsIcon, binding.tabSettingsText, binding.tabSettingsDot)
        }
    }
    
    private fun resetTab(tab: LinearLayout, icon: ImageView, text: TextView, dot: View) {
        tab.isSelected = false
        icon.setColorFilter(unselectedColor)
        text.setTextColor(unselectedColor)
        dot.visibility = View.INVISIBLE
        // 恢复默认缩放
        icon.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
    }
    
    private fun highlightTab(tab: LinearLayout, icon: ImageView, text: TextView, dot: View) {
        tab.isSelected = true
        icon.setColorFilter(selectedColor)
        text.setTextColor(selectedColor)
        dot.visibility = View.VISIBLE
        // 弹性放大动画（overshoot interpolator 模拟物理回弹）
        animateTabSelection(icon)
    }
    
    /**
     * 选中 tab 时的弹性缩放动画
     */
    private fun animateTabSelection(icon: ImageView) {
        val scaleX = ObjectAnimator.ofFloat(icon, "scaleX", 0.8f, 1.15f, 1f)
        val scaleY = ObjectAnimator.ofFloat(icon, "scaleY", 0.8f, 1.15f, 1f)
        AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            duration = 300
            interpolator = OvershootInterpolator(2f)
            start()
        }
    }
    
    /**
     * Navigate to Note tab - called from other fragments
     */
    fun navigateToNote() {
        binding.tabNote.performClick()
    }
    
    private fun connectWebSocket() {
        val app = application as KeeNotesApp
        
        lifecycleScope.launch {
            if (app.settingsRepository.isConfigured()) {
                app.webSocketService.connect()
            }
        }
    }
    
    /**
     * 同步期间保持屏幕常亮，同步完成后恢复
     */
    private fun observeSyncStateForScreenWake() {
        val app = application as KeeNotesApp
        lifecycleScope.launch {
            app.webSocketService.syncState.collectLatest { state ->
                if (state == WebSocketService.SyncState.SYNCING) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        (application as KeeNotesApp).webSocketService.disconnect()
    }
}
