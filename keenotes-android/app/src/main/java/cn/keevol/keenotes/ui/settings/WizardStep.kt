package cn.keevol.keenotes.ui.settings

/**
 * 向导步骤数据类
 */
data class WizardStep(
    val fieldId: String,
    val title: String,
    val description: String,
    val isRequired: Boolean = true
)
