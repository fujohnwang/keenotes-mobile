package cn.keevol.keenotes.ui.settings

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
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
    
    init {
        steps = createSteps()
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
        
        // 创建向导卡片
        val inflater = LayoutInflater.from(context)
        wizardCard = inflater.inflate(R.layout.wizard_card, null) as CardView
        
        // 设置内容
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
        
        // 添加到容器底部
        if (containerView is android.view.ViewGroup) {
            containerView.addView(wizardCard)
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
