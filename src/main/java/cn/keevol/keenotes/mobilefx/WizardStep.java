package cn.keevol.keenotes.mobilefx;

import javafx.scene.Node;

/**
 * 向导步骤数据类
 * 定义每个引导步骤的信息
 */
public class WizardStep {
    private final Node targetNode;
    private final String title;
    private final String description;
    private final boolean isRequired;
    
    public WizardStep(Node targetNode, String title, String description, boolean isRequired) {
        this.targetNode = targetNode;
        this.title = title;
        this.description = description;
        this.isRequired = isRequired;
    }
    
    public Node getTargetNode() {
        return targetNode;
    }
    
    public String getTitle() {
        return title;
    }
    
    public String getDescription() {
        return description;
    }
    
    public boolean isRequired() {
        return isRequired;
    }
}
