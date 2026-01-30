package cn.keevol.keenotes.mobilefx;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置向导管理器
 * 负责管理向导的状态和流程
 */
public class SettingsWizard {
    
    private final IntegerProperty currentStepProperty = new SimpleIntegerProperty(0);
    private final BooleanProperty wizardActiveProperty = new SimpleBooleanProperty(false);
    private final List<WizardStep> steps = new ArrayList<>();
    private final SettingsService settingsService;
    
    private LightweightPopOver currentPopOver;
    private String originalBorderStyle = "";
    private boolean programmaticHide = false;  // 标志：是否是程序主动隐藏
    private javafx.beans.value.ChangeListener<Number> windowResizeListener;  // 窗口大小变化监听器
    
    public SettingsWizard(Node endpointField, Node tokenField, Node passwordField, Node confirmPasswordField, SettingsService settingsService) {
        this.settingsService = settingsService;
        
        // 定义向导步骤
        initSteps(endpointField, tokenField, passwordField, confirmPasswordField);
        
        // 设置步骤监听器
        setupStepListener();
        
        // 监听配置变化
        setupConfigurationListener();
    }
    
    /**
     * 初始化向导步骤
     */
    private void initSteps(Node endpointField, Node tokenField, Node passwordField, Node confirmPasswordField) {
        // 检测系统语言
        boolean isChinese = isChinese();
        
        // 不再引导 endpoint 字段（因为有默认值，优先级较低）
        // 只引导 token 和 password 字段
        
        steps.add(new WizardStep(
            tokenField,
            isChinese ? "访问令牌" : "Access Token",
            isChinese ? "请输入访问令牌，用于安全连接到服务器" : "Please enter your access token for secure server connection",
            true
        ));
        
        steps.add(new WizardStep(
            passwordField,
            isChinese ? "加密密码" : "Encryption Password",
            isChinese ? "请输入加密密码，用于端到端加密保护您的笔记数据" : "Please enter encryption password for end-to-end encryption of your notes",
            true
        ));
        
        steps.add(new WizardStep(
            confirmPasswordField,
            isChinese ? "确认加密密码" : "Confirm Encryption Password",
            isChinese ? "请再次输入加密密码以确认" : "Please re-enter the encryption password to confirm",
            true
        ));
    }
    
    /**
     * 检测系统语言是否为中文
     */
    private boolean isChinese() {
        String language = System.getProperty("user.language");
        String country = System.getProperty("user.country");
        
        // 检查语言是否为中文（zh）或国家/地区是否为中国大陆(CN)、台湾(TW)、香港(HK)
        return "zh".equalsIgnoreCase(language) || 
               "CN".equalsIgnoreCase(country) || 
               "TW".equalsIgnoreCase(country) || 
               "HK".equalsIgnoreCase(country);
    }
    
    /**
     * 设置步骤监听器
     */
    private void setupStepListener() {
        currentStepProperty.addListener((obs, oldVal, newVal) -> {
            int stepIndex = newVal.intValue();
            
            // 移除旧步骤的高亮
            if (oldVal.intValue() >= 0 && oldVal.intValue() < steps.size()) {
                removeHighlight(steps.get(oldVal.intValue()).getTargetNode());
            }
            
            // 关闭当前 PopOver（程序主动关闭）
            if (currentPopOver != null) {
                programmaticHide = true;  // 设置标志
                currentPopOver.hide();
                currentPopOver = null;
                programmaticHide = false;  // 重置标志
            }
            
            // 显示新步骤
            if (stepIndex >= 0 && stepIndex < steps.size() && wizardActiveProperty.get()) {
                showStepPopOver(steps.get(stepIndex));
            } else if (stepIndex >= steps.size()) {
                // 所有步骤完成
                wizardActiveProperty.set(false);
            }
        });
        
        // 监听向导激活状态
        wizardActiveProperty.addListener((obs, oldVal, newVal) -> {
            if (!newVal) {
                // 向导关闭，清理资源
                cleanup();
            }
        });
    }
    
    /**
     * 监听配置变化，自动完成向导
     */
    private void setupConfigurationListener() {
        // 这里可以监听 SettingsService 的属性变化
        // 但由于 SettingsService 没有暴露 Property，我们在每次操作后手动检查
    }
    
    /**
     * 显示指定步骤的 PopOver
     */
    private void showStepPopOver(WizardStep step) {
        System.out.println("[SettingsWizard] Showing step: " + step.getTitle());
        
        Node targetNode = step.getTargetNode();
        
        if (targetNode == null) {
            System.err.println("[SettingsWizard] Target node is null!");
            return;
        }
        
        System.out.println("[SettingsWizard] Target node: " + targetNode.getClass().getSimpleName());
        
        // 高亮目标字段
        highlightField(targetNode);
        
        // 创建 PopOver 内容
        VBox content = createPopOverContent(step);
        
        // 创建并显示 PopOver
        currentPopOver = new LightweightPopOver();
        currentPopOver.setTitle(step.getTitle());
        currentPopOver.setContentNode(content);
        currentPopOver.setArrowLocation(LightweightPopOver.ArrowLocation.LEFT_CENTER);
        
        // 当 PopOver 隐藏时，只有在用户手动关闭时才停止向导
        currentPopOver.setOnHidden(event -> {
            // 如果是程序主动隐藏（切换步骤），不停止向导
            if (!programmaticHide && wizardActiveProperty.get()) {
                // 用户手动关闭了 PopOver（按 ESC 或其他方式），停止向导
                System.out.println("[SettingsWizard] User manually closed PopOver, stopping wizard");
                wizardActiveProperty.set(false);
            }
            
            // 移除窗口大小监听器
            removeWindowResizeListener();
        });
        
        System.out.println("[SettingsWizard] Showing PopOver...");
        currentPopOver.show(targetNode);
        System.out.println("[SettingsWizard] PopOver shown");
        
        // 添加窗口大小变化监听器
        setupWindowResizeListener(targetNode);
    }
    
    /**
     * 创建 PopOver 内容
     */
    private VBox createPopOverContent(WizardStep step) {
        VBox content = new VBox(12);
        content.setPadding(new Insets(0));
        
        // 检测系统语言
        boolean isChinese = isChinese();
        
        // 描述文本
        Label description = new Label(step.getDescription());
        description.setWrapText(true);
        description.setStyle(
            "-fx-font-size: 13px;" +
            "-fx-text-fill: -fx-text-secondary;"
        );
        
        // 按钮容器
        HBox buttons = new HBox(10);
        buttons.setPadding(new Insets(8, 0, 0, 0));
        
        // 下一步按钮文本（根据是否是最后一步显示不同文本）
        String nextButtonText;
        if (currentStepProperty.get() == steps.size() - 1) {
            nextButtonText = isChinese ? "完成" : "Finish";
        } else {
            nextButtonText = isChinese ? "下一步" : "Next";
        }
        
        Button nextBtn = new Button(nextButtonText);
        nextBtn.setStyle(
            "-fx-background-color: -fx-primary;" +
            "-fx-text-fill: #FFFFFF;" +
            "-fx-padding: 6 16;" +
            "-fx-background-radius: 4;" +
            "-fx-cursor: hand;"
        );
        nextBtn.setOnMouseEntered(e -> nextBtn.setStyle(
            "-fx-background-color: -fx-primary-dark;" +
            "-fx-text-fill: #FFFFFF;" +
            "-fx-padding: 6 16;" +
            "-fx-background-radius: 4;" +
            "-fx-cursor: hand;"
        ));
        nextBtn.setOnMouseExited(e -> nextBtn.setStyle(
            "-fx-background-color: -fx-primary;" +
            "-fx-text-fill: #FFFFFF;" +
            "-fx-padding: 6 16;" +
            "-fx-background-radius: 4;" +
            "-fx-cursor: hand;"
        ));
        nextBtn.setOnAction(e -> nextStep());
        
        // 跳过按钮
        Button skipBtn = new Button(isChinese ? "跳过引导" : "Skip Guide");
        skipBtn.setStyle(
            "-fx-background-color: transparent;" +
            "-fx-text-fill: -fx-text-secondary;" +
            "-fx-padding: 6 16;" +
            "-fx-cursor: hand;"
        );
        skipBtn.setOnMouseEntered(e -> skipBtn.setStyle(
            "-fx-background-color: transparent;" +
            "-fx-text-fill: -fx-text-primary;" +
            "-fx-padding: 6 16;" +
            "-fx-cursor: hand;"
        ));
        skipBtn.setOnMouseExited(e -> skipBtn.setStyle(
            "-fx-background-color: transparent;" +
            "-fx-text-fill: -fx-text-secondary;" +
            "-fx-padding: 6 16;" +
            "-fx-cursor: hand;"
        ));
        skipBtn.setOnAction(e -> skipWizard());
        
        // 添加弹性空间
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        
        buttons.getChildren().addAll(skipBtn, spacer, nextBtn);
        
        content.getChildren().addAll(description, buttons);
        
        return content;
    }
    
    /**
     * 下一步
     */
    private void nextStep() {
        int nextStep = currentStepProperty.get() + 1;
        currentStepProperty.set(nextStep);
        
        // 将焦点转移到下一个输入框
        if (nextStep < steps.size()) {
            Node nextNode = steps.get(nextStep).getTargetNode();
            if (nextNode != null) {
                // 使用 Platform.runLater 确保在 UI 更新后执行
                javafx.application.Platform.runLater(() -> {
                    nextNode.requestFocus();
                });
            }
        }
        
        // 检查是否完成
        checkCompletion();
    }
    
    /**
     * 跳过向导
     */
    private void skipWizard() {
        wizardActiveProperty.set(false);
    }
    
    /**
     * 检查配置是否完成
     */
    private void checkCompletion() {
        if (settingsService.isConfigured()) {
            // 配置已完成，自动关闭向导
            wizardActiveProperty.set(false);
        }
    }
    
    /**
     * 高亮字段
     */
    private void highlightField(Node node) {
        if (node instanceof TextField) {
            TextField field = (TextField) node;
            // 保存原始样式
            originalBorderStyle = field.getStyle();
            // 添加蓝色边框
            field.setStyle(originalBorderStyle + 
                "-fx-border-color: -fx-primary;" +
                "-fx-border-width: 2;" +
                "-fx-border-radius: 4;" +
                "-fx-effect: dropshadow(gaussian, -fx-accent-glow, 12, 0, 0, 0);"
            );
        }
    }
    
    /**
     * 移除高亮
     */
    private void removeHighlight(Node node) {
        if (node instanceof TextField) {
            TextField field = (TextField) node;
            // 恢复原始样式
            field.setStyle(originalBorderStyle);
        }
    }
    
    /**
     * 清理资源
     */
    private void cleanup() {
        // 关闭 PopOver
        if (currentPopOver != null) {
            programmaticHide = true;  // 设置标志
            currentPopOver.hide();
            currentPopOver = null;
            programmaticHide = false;  // 重置标志
        }
        
        // 移除所有高亮
        for (WizardStep step : steps) {
            removeHighlight(step.getTargetNode());
        }
    }
    
    /**
     * 启动向导
     */
    public void start() {
        System.out.println("[SettingsWizard] Starting wizard...");
        System.out.println("[SettingsWizard] wizardActive: " + wizardActiveProperty.get());
        System.out.println("[SettingsWizard] steps count: " + steps.size());
        
        if (!wizardActiveProperty.get()) {
            wizardActiveProperty.set(true);
            currentStepProperty.set(0);
            System.out.println("[SettingsWizard] Wizard activated, showing first step");
            
            // 直接显示第一步（因为 currentStepProperty 初始值就是 0，监听器不会触发）
            if (!steps.isEmpty()) {
                showStepPopOver(steps.get(0));
            }
        }
    }
    
    /**
     * 停止向导
     */
    public void stop() {
        wizardActiveProperty.set(false);
    }
    
    /**
     * 设置窗口大小变化监听器
     */
    private void setupWindowResizeListener(Node targetNode) {
        // 移除旧的监听器
        removeWindowResizeListener();
        
        // 获取 Scene
        javafx.scene.Scene scene = targetNode.getScene();
        if (scene == null) {
            return;
        }
        
        // 创建监听器
        windowResizeListener = (obs, oldVal, newVal) -> {
            if (currentPopOver != null && currentPopOver.isShowing()) {
                // 窗口大小变化时，重新定位 PopOver
                javafx.application.Platform.runLater(() -> {
                    currentPopOver.reposition(targetNode);
                });
            }
        };
        
        // 监听窗口宽度和高度变化
        scene.widthProperty().addListener(windowResizeListener);
        scene.heightProperty().addListener(windowResizeListener);
    }
    
    /**
     * 移除窗口大小监听器
     */
    private void removeWindowResizeListener() {
        if (windowResizeListener != null) {
            // 尝试从当前步骤获取目标节点
            int stepIndex = currentStepProperty.get();
            Node targetNode = null;
            
            // 确保索引在有效范围内
            if (stepIndex >= 0 && stepIndex < steps.size()) {
                targetNode = steps.get(stepIndex).getTargetNode();
            } else if (!steps.isEmpty()) {
                // 如果索引超出范围，使用第一个步骤的节点
                targetNode = steps.get(0).getTargetNode();
            }
            
            if (targetNode != null && targetNode.getScene() != null) {
                javafx.scene.Scene scene = targetNode.getScene();
                scene.widthProperty().removeListener(windowResizeListener);
                scene.heightProperty().removeListener(windowResizeListener);
            }
            windowResizeListener = null;
        }
    }
    
    /**
     * 获取向导激活状态属性
     */
    public BooleanProperty wizardActiveProperty() {
        return wizardActiveProperty;
    }
    
    /**
     * 获取当前步骤属性
     */
    public IntegerProperty currentStepProperty() {
        return currentStepProperty;
    }
}
