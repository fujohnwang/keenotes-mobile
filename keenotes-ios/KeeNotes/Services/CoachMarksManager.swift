import UIKit
import Instructions

/// Coach Marks 引导管理器
/// 使用 Instructions 库实现真正的引导效果
class CoachMarksManager: NSObject {
    
    // MARK: - Properties
    
    private let coachMarksController = CoachMarksController()
    private weak var viewController: UIViewController?
    private let settingsService: SettingsService
    
    // 需要引导的视图引用
    private var tokenField: UIView?
    private var passwordField: UIView?
    
    // MARK: - Initialization
    
    init(settingsService: SettingsService) {
        self.settingsService = settingsService
        super.init()
        
        // 配置 CoachMarksController
        coachMarksController.dataSource = self
        coachMarksController.delegate = self
        
        // 配置样式 - 使用 overlayBackgroundColor 属性
        coachMarksController.overlay.backgroundColor = UIColor.black.withAlphaComponent(0.7)
        coachMarksController.overlay.isUserInteractionEnabled = false  // 不允许点击遮罩关闭
    }
    
    // MARK: - Public Methods
    
    /// 开始引导
    func startCoachMarks(in viewController: UIViewController, tokenField: UIView, passwordField: UIView) {
        self.viewController = viewController
        self.tokenField = tokenField
        self.passwordField = passwordField
        
        // 延迟启动，确保视图已经渲染
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            guard let self = self else { return }
            self.coachMarksController.start(in: .window(over: viewController))
        }
    }
    
    /// 停止引导
    func stopCoachMarks() {
        coachMarksController.stop(immediately: true)
    }
    
    // MARK: - Private Methods
    
    /// 检测系统语言是否为中文
    private func isChinese() -> Bool {
        let language = Locale.current.languageCode ?? ""
        let region = Locale.current.regionCode ?? ""
        return language == "zh" || region == "CN" || region == "TW" || region == "HK"
    }
}

// MARK: - CoachMarksControllerDataSource

extension CoachMarksManager: CoachMarksControllerDataSource {
    
    func numberOfCoachMarks(for coachMarksController: CoachMarksController) -> Int {
        return 2  // Token 和 Password 两个步骤
    }
    
    func coachMarksController(_ coachMarksController: CoachMarksController,
                             coachMarkAt index: Int) -> CoachMark {
        let chinese = isChinese()
        
        switch index {
        case 0:
            // Token 字段引导
            guard let tokenField = tokenField else {
                return CoachMark()
            }
            var coachMark = coachMarksController.helper.makeCoachMark(for: tokenField)
            coachMark.arrowOrientation = .top
            coachMark.gapBetweenCoachMarkAndCutoutPath = 6.0
            return coachMark
            
        case 1:
            // Password 字段引导
            guard let passwordField = passwordField else {
                return CoachMark()
            }
            var coachMark = coachMarksController.helper.makeCoachMark(for: passwordField)
            coachMark.arrowOrientation = .top
            coachMark.gapBetweenCoachMarkAndCutoutPath = 6.0
            return coachMark
            
        default:
            return CoachMark()
        }
    }
    
    func coachMarksController(_ coachMarksController: CoachMarksController,
                             coachMarkViewsAt index: Int,
                             madeFrom coachMark: CoachMark) -> (bodyView: (UIView & CoachMarkBodyView), arrowView: (UIView & CoachMarkArrowView)?) {
        let chinese = isChinese()
        
        let coachViews = coachMarksController.helper.makeDefaultCoachViews(
            withArrow: true,
            arrowOrientation: coachMark.arrowOrientation
        )
        
        switch index {
        case 0:
            // Token 字段提示
            coachViews.bodyView.hintLabel.text = chinese ? 
                "请输入访问令牌，用于安全连接到服务器" : 
                "Please enter your access token for secure server connection"
            coachViews.bodyView.nextLabel.text = chinese ? "下一步" : "Next"
            
        case 1:
            // Password 字段提示
            coachViews.bodyView.hintLabel.text = chinese ? 
                "请输入加密密码，用于端到端加密保护您的笔记数据" : 
                "Please enter encryption password for end-to-end encryption of your notes"
            coachViews.bodyView.nextLabel.text = chinese ? "完成" : "Finish"
            
        default:
            break
        }
        
        return (bodyView: coachViews.bodyView, arrowView: coachViews.arrowView)
    }
}

// MARK: - CoachMarksControllerDelegate

extension CoachMarksManager: CoachMarksControllerDelegate {
    
    func coachMarksController(_ coachMarksController: CoachMarksController,
                             willShow coachMark: CoachMark,
                             at index: Int) {
        print("[CoachMarks] Will show coach mark at index: \(index)")
    }
    
    func coachMarksController(_ coachMarksController: CoachMarksController,
                             didEndShowingBySkipping skipped: Bool) {
        print("[CoachMarks] Did end showing, skipped: \(skipped)")
    }
    
    func shouldHandleOverlayTap(in coachMarksController: CoachMarksController, at index: Int) -> Bool {
        // 不允许点击遮罩跳过
        return false
    }
}
