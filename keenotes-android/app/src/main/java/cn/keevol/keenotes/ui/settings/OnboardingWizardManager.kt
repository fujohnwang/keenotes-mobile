package cn.keevol.keenotes.ui.settings

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import cn.keevol.keenotes.R
import cn.keevol.keenotes.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 首次启动配置向导管理器
 * iOS 风格：卡片紧跟输入框，自动聚焦
 */
class OnboardingWizardManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val settingsRepository: SettingsRepository,
    private val containerView: View
) {
    
    private var currentStep = 0
    private var wizardCard: CardView? = null
    private val steps: List<WizardStep>
    
    // 输入框引用
    private val tokenInput: EditText?
    private val passwordInput: EditText?
    private val confirmPasswordInput: EditText?
    
    init {
        steps = createSteps()
        
        // 获取输入框引用
        tokenInput = containerView.findViewById(R.id.tokenInput)
        passwordInput = containerView.findViewById(R.id.passwordInput)
        confirmPasswordInput = containerView.findViewById(R.id.passwordConfirmInput)
    }
    
    /**
     * 检测系统语言是否为中文
     */
    private fun isChinese(): Boolean {
        val locale = Locale.getDefault()
        val language = locale.language
        val country = locale.country
        return language == "zh" || country == "CN" || country == "TW" || country == "HK"
    }
    
    /**
     * 创建向导步骤
     */
    private fun createSteps(): List<WizardStep> {
        val chinese = isChinese()
        return listOf(
            WizardStep(
                fieldId = "token",
                title = if (chinese) "访问令牌" else "Access Token",
                description = if (chinese) 
                    "请输入访问令牌，用于安全连接到服务器" 
                else 
                    "Please enter your access token for secure server connection",
                isRequired = true
            ),
            WizardStep(
                fieldId = "encryptionPassword",
                title = if (chinese) "加密密码" else "Encryption Password",
                description = if (chinese) 
                    "请输入加密密码，用于端到端加密保护您的笔记数据" 
                else 
                    "Please enter encryption password for end-to-end encryption of your notes",
                isRequired = true
            ),
            WizardStep(
                fieldId = "confirmPassword",
                title = if (chinese) "确认加密密码" else "Confirm Encryption Password",
                description = if (chinese) 
                    "请再次输入加密密码以确认" 
                else 
                    "Please re-enter the encryption password to confirm",
                isRequired = true
            )
        )
    }
    
    /**
     * 检查并显示向导
     */
    fun checkAndShowWizard() {
        lifecycleOwner.lifecycleScope.launch {
            val isConfigured = settingsRepository.isConfigured.first()
            if (!isConfigured) {
                // 延迟显示，确保界面已渲染
                kotlinx.coroutines.delay(500)
                showWizard()
            }
        }
    }
    
    /**
     * 显示向导
     */
    private fun showWizard() {
        if (currentStep >= steps.count()) {
            hideWizard()
            return
        }
        
        val step = steps[currentStep]
        val chinese = isChinese()
        
        // 获取当前步骤对应的输入框
        val targetInput = getInputForStep(step.fieldId)
        if (targetInput == null) {
            // 输入框未找到，跳过此步骤
            currentStep++
            if (currentStep < steps.count()) {
                showWizard()
            }
            return
        }
        
        // 聚焦到目标输入框
        targetInput.requestFocus()
        
        // 等待输入框布局完成后再显示卡片
        targetInput.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                targetInput.viewTreeObserver.removeOnGlobalLayoutListener(this)
                showCardBelowInput(targetInput, step, chinese)
            }
        })
    }
    
    /**
     * 在输入框下方显示卡片
     */
    private fun showCardBelowInput(targetInput: View, step: WizardStep, chinese: Boolean) {
        // 移除旧卡片
        hideWizard()
        
        // 创建向导卡片
        val inflater = LayoutInflater.from(context)
        wizardCard = inflater.inflate(R.layout.wizard_card, null) as CardView
        
        // 计算卡片位置（输入框下方，带小间距）
        val location = IntArray(2)
        targetInput.getLocationInWindow(location)
        val inputX = location[0]
        val inputY = location[1]
        val inputHeight = targetInput.height
        
        // 设置卡片位置参数
        val cardLayoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            // 计算卡片的 top margin（输入框底部 + 8dp 间距）
            topMargin = inputY + inputHeight + dpToPx(8)
            leftMargin = dpToPx(16)
            rightMargin = dpToPx(16)
        }
        wizardCard!!.layoutParams = cardLayoutParams
        
        // 设置卡片内容
        val titleView = wizardCard!!.findViewById<TextView>(R.id.wizardTitle)
        val descriptionView = wizardCard!!.findViewById<TextView>(R.id.wizardDescription)
        val nextButton = wizardCard!!.findViewById<Button>(R.id.wizardNext)
        val skipButton = wizardCard!!.findViewById<TextView>(R.id.wizardSkip)
        
        titleView.text = step.title
        descriptionView.text = step.description
        
        // 设置按钮文本
        val isLastStep = currentStep == steps.count() - 1
        nextButton.text = if (isLastStep) {
            if (chinese) "完成" else "Finish"
        } else {
            if (chinese) "下一步" else "Next"
        }
        
        skipButton.text = if (chinese) "跳过" else "Skip"
        
        // 设置点击事件
        nextButton.setOnClickListener {
            currentStep++
            hideWizard()
            if (currentStep < steps.count()) {
                showWizard()
            }
        }
        
        skipButton.setOnClickListener {
            hideWizard()
        }
        
        // 将卡片添加到容器
        if (containerView is android.view.ViewGroup) {
            containerView.addView(wizardCard)
        }
    }
    
    /**
     * 根据 fieldId 获取对应的输入框
     */
    private fun getInputForStep(fieldId: String): EditText? {
        return when (fieldId) {
            "token" -> tokenInput
            "encryptionPassword" -> passwordInput
            "confirmPassword" -> confirmPasswordInput
            else -> null
        }
    }
    
    /**
     * 隐藏向导
     */
    private fun hideWizard() {
        wizardCard?.let { card ->
            if (containerView is android.view.ViewGroup) {
                containerView.removeView(card)
            }
        }
        wizardCard = null
    }
    
    /**
     * dp 转 px
     */
    private fun dpToPx(dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density).toInt()
    }
    
    /**
     * 监听配置状态变化
     */
    fun observeConfigurationState() {
        lifecycleOwner.lifecycleScope.launch {
            settingsRepository.isConfigured.collect { isConfigured ->
                if (isConfigured) {
                    hideWizard()
                }
            }
        }
    }
}
