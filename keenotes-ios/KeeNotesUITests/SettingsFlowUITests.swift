import XCTest

final class SettingsFlowUITests: XCTestCase {
    private func launch(_ mode: String = "configured", language: String = "zh-Hans", region: String = "zh_CN") -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-fixture", mode, "-AppleLanguages", "(\(language))", "-AppleLocale", region]
        app.launch()
        XCTAssertTrue(app.buttons["subscriptionEntry"].waitForExistence(timeout: 10))
        return app
    }
    private func capture(_ name: String, app: XCUIApplication) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }
    private func dismissSheet(_ app: XCUIApplication) {
        let done = app.buttons.matching(NSPredicate(format: "identifier == 'subscriptionDone' OR identifier == 'historyDone'")).firstMatch
        XCTAssertTrue(done.waitForExistence(timeout: 5)); done.tap()
    }
    private func replace(_ field: XCUIElement, with text: String) {
        field.tap()
        field.press(forDuration: 1.2)
        let app = XCUIApplication()
        let selectAll = app.menuItems.matching(NSPredicate(format: "label == '全选' OR label == 'Select All'")).firstMatch
        if selectAll.waitForExistence(timeout: 2) { selectAll.tap() }
        else {
            let button = app.buttons.matching(NSPredicate(format: "label == '全选' OR label == 'Select All'")).firstMatch
            XCTAssertTrue(button.waitForExistence(timeout: 2)); button.tap()
        }
        field.typeText(text)
    }

    func testIAPInterfaceUsesEnglishWhenRegionIsChina() {
        assertIAPLanguage(language: "en", region: "zh_CN", chinese: false)
    }

    func testIAPInterfaceUsesSimplifiedChineseWhenRegionIsUS() {
        assertIAPLanguage(language: "zh-Hans", region: "en_US", chinese: true)
    }

    private func assertIAPLanguage(language: String, region: String, chinese: Bool) {
        let app = launch(language: language, region: region)
        let subscriptionTitle = chinese ? "订阅" : "Subscription"
        let historyTitle = chinese ? "凭据历史" : "Credential History"
        let generalTitle = chinese ? "通用" : "General"
        let currentTitle = chinese ? "当前使用" : "Currently in use"
        func reveal(_ element: XCUIElement, scrollingDown: Bool = false) {
            for _ in 0..<5 {
                if element.exists && element.isHittable { break }
                if scrollingDown { app.swipeDown() } else { app.swipeUp() }
            }
            XCTAssertTrue(element.waitForExistence(timeout: 5))
            XCTAssertTrue(element.isHittable)
        }
        XCTAssertEqual(app.buttons["subscriptionEntry"].label, chinese ? "购买" : "Purchase")
        XCTAssertEqual(app.buttons["credentialHistory"].label, chinese ? "历史" : "History")
        XCTAssertEqual(app.buttons["saveSettings"].label, chinese ? "保存设置" : "Save Settings")
        let connection = app.staticTexts["connectionStatus"]
        reveal(connection)
        let connectionLabels = chinese ? ["连接已建立", "连接中…", "连接未建立；已保存的本地配置仍保留。"] :
            ["Connected", "Connecting…", "Not connected. Your saved local configuration is preserved."]
        XCTAssertTrue(connectionLabels.contains(connection.label))
        reveal(app.textFields["endpointInput"], scrollingDown: true)
        capture("language-\(language)-settings", app: app)

        app.buttons["subscriptionEntry"].tap()
        XCTAssertTrue(app.navigationBars[subscriptionTitle].waitForExistence(timeout: 5))
        let purchase = app.buttons["subscriptionPurchase"]
        XCTAssertTrue(purchase.waitForExistence(timeout: 5))
        XCTAssertEqual(purchase.label, chinese ? "订阅一年" : "Subscribe for a Year")
        XCTAssertEqual(app.staticTexts["subscriptionHeading"].label, chinese ? "KeeNotes 云同步" : "KeeNotes Cloud Sync")
        XCTAssertEqual(app.staticTexts["subscriptionProduct"].label, chinese ? "本地验证年订阅（fixture）" : "Local annual subscription (fixture)")
        XCTAssertEqual(app.staticTexts["subscriptionPrice"].label, chinese ? "$1.00 / 年" : "$1.00 / year")
        XCTAssertEqual(app.buttons["subscriptionDone"].label, chinese ? "完成" : "Done")
        capture("language-\(language)-subscription", app: app)

        let restore = app.buttons["subscriptionRestore"]
        reveal(restore)
        XCTAssertEqual(restore.label, chinese ? "恢复购买" : "Restore Purchases")
        restore.tap()
        let purchaseStatus = app.staticTexts["subscriptionStatus"]
        let noPurchases = chinese ? "当前没有可恢复的有效购买，原配置与历史仍保留。" :
            "There are no valid purchases to restore. Your existing configuration and history are preserved."
        reveal(purchaseStatus, scrollingDown: true)
        let restored = expectation(for: NSPredicate(format: "label == %@", noPurchases), evaluatedWith: purchaseStatus)
        wait(for: [restored], timeout: 5)
        for (id, title) in [
            ("subscriptionRetry", chinese ? "重试交付" : "Retry Delivery"),
            ("subscriptionManage", chinese ? "管理订阅" : "Manage Subscription"),
            ("subscriptionFill", chinese ? "填入连接凭据" : "Fill Connection Credentials")
        ] {
            let button = app.buttons[id]; reveal(button); XCTAssertEqual(button.label, title)
        }
        dismissSheet(app)

        let history = app.buttons["credentialHistory"]
        reveal(history, scrollingDown: true); history.tap()
        XCTAssertTrue(app.navigationBars[historyTitle].waitForExistence(timeout: 5))
        let general = app.buttons.containing(.staticText, identifier: "https://general.example.invalid").firstMatch
        XCTAssertEqual(general.staticTexts["credentialSourceTag"].label, generalTitle)
        XCTAssertEqual(general.images["historyCurrent"].label, currentTitle)
        XCTAssertEqual(app.buttons["historyDone"].label, chinese ? "完成" : "Done")
        capture("language-\(language)-history", app: app)
        app.buttons.containing(.staticText, identifier: "https://subscription.example.invalid").firstMatch.tap()
        let confirm = app.alerts[chinese ? "确认切换配置？" : "Switch configuration?"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 5))
        XCTAssertTrue(confirm.buttons[chinese ? "确认切换" : "Switch"].exists)
        let explanation = chinese ? "将替换完整连接配置并重新连接。" : "Replace the complete connection configuration and reconnect."
        XCTAssertTrue(confirm.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", explanation)).firstMatch.exists)
        capture("language-\(language)-confirm", app: app)
        confirm.buttons[chinese ? "取消" : "Cancel"].tap()
        dismissSheet(app)

        let endpoint = app.textFields["endpointInput"]
        reveal(endpoint, scrollingDown: true); replace(endpoint, with: "invalid-endpoint")
        // End editing before checking the status row below Save; an open URL keyboard obscures it.
        let keyboard = app.keyboards.firstMatch
        XCTAssertTrue(keyboard.waitForExistence(timeout: 3))
        keyboard.buttons["Return"].tap()
        let editingEnded = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: keyboard)
        wait(for: [editingEnded], timeout: 5)
        XCTAssertEqual(endpoint.value as? String, "invalid-endpoint")
        let save = app.buttons["saveSettings"]
        reveal(save); XCTAssertTrue(save.isEnabled); save.tap()
        let error = app.staticTexts["configurationStatus"]
        reveal(error)
        let invalidInput = chinese ? "请填写有效的 HTTP(S) endpoint、token 和加密密码。" :
            "Enter a valid HTTP(S) endpoint, token, and encryption password."
        let rejected = expectation(for: NSPredicate(format: "label == %@", invalidInput), evaluatedWith: error)
        wait(for: [rejected], timeout: 5)
    }

    func testHistoryCancelHasNoEffectConfirmAppliesWithoutSaveAndMasksSecrets() {
        let app = launch()
        let endpoint = app.textFields["endpointInput"]
        replace(endpoint, with: "https://draft.example.invalid")
        app.buttons["credentialHistory"].tap()
        XCTAssertTrue(app.navigationBars["凭据历史"].waitForExistence(timeout: 5))
        capture("history-masked", app: app)
        XCTAssertFalse(app.staticTexts["Fixture-PIN-B"].exists)
        XCTAssertFalse(app.staticTexts["fixture-iap-EFGH"].exists)
        app.buttons.containing(.staticText, identifier: "https://subscription.example.invalid").firstMatch.tap()
        XCTAssertTrue(app.alerts["确认切换配置？"].waitForExistence(timeout: 3))
        capture("history-confirmation", app: app)
        app.alerts.buttons["取消"].tap()
        dismissSheet(app)
        XCTAssertEqual(endpoint.value as? String, "https://draft.example.invalid")
        app.buttons["credentialHistory"].tap()
        app.buttons.containing(.staticText, identifier: "https://subscription.example.invalid").firstMatch.tap()
        app.alerts.buttons["确认切换"].tap()
        XCTAssertTrue(endpoint.waitForExistence(timeout: 5))
        XCTAssertEqual(endpoint.value as? String, "https://subscription.example.invalid")
        XCTAssertTrue(app.staticTexts["配置已切换；连接状态见下方。"].waitForExistence(timeout: 5))
        app.buttons["credentialHistory"].tap()
        XCTAssertTrue(app.buttons.containing(.staticText, identifier: "https://subscription.example.invalid").firstMatch.images["historyCurrent"].exists)
        capture("history-active-after-confirm", app: app)
    }

    func testSubscriptionSheetPreservesDraftAndExplicitFillDoesNotSave() {
        let app = launch()
        let endpoint = app.textFields["endpointInput"]
        replace(endpoint, with: "https://draft.example.invalid")
        let pinBefore = app.secureTextFields["pinInput"].value as? String
        app.buttons["subscriptionEntry"].tap()
        XCTAssertTrue(app.navigationBars["订阅"].waitForExistence(timeout: 3))
        capture("subscription-sheet", app: app)
        dismissSheet(app)
        XCTAssertEqual(endpoint.value as? String, "https://draft.example.invalid")
        app.buttons["subscriptionEntry"].tap()
        let fill = app.buttons["subscriptionFill"]
        for _ in 0..<4 {
            if fill.exists && fill.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(fill.waitForExistence(timeout: 3)); fill.tap()
        XCTAssertTrue(endpoint.waitForExistence(timeout: 5))
        XCTAssertEqual(endpoint.value as? String, "https://subscription.example.invalid")
        XCTAssertEqual(app.secureTextFields["pinInput"].value as? String, pinBefore)
        XCTAssertTrue(app.staticTexts["凭据已填入，保存后开始使用。"].exists)
        app.buttons["credentialHistory"].tap()
        XCTAssertTrue(app.buttons.containing(.staticText, identifier: "https://general.example.invalid").firstMatch.images["historyCurrent"].exists)
        capture("fill-still-requires-save", app: app)
    }

    func testBlankOnboardingCanOpenPurchaseAndPurchaseFillsWithoutCreatingHistory() {
        let app = launch("blank")
        XCTAssertTrue(app.staticTexts["访问令牌"].waitForExistence(timeout: 5))
        capture("first-configuration-wizard", app: app)
        app.buttons["下一步"].tap()
        XCTAssertTrue(app.staticTexts["加密密码"].waitForExistence(timeout: 3))
        app.buttons["下一步"].tap()
        XCTAssertTrue(app.staticTexts["确认加密密码"].waitForExistence(timeout: 3))
        capture("wizard-confirm-pin-step", app: app)
        app.buttons["subscriptionEntry"].tap()
        XCTAssertTrue(app.buttons["subscriptionPurchase"].waitForExistence(timeout: 5))
        app.buttons["subscriptionPurchase"].tap()
        let endpoint = app.textFields["endpointInput"]
        XCTAssertTrue(endpoint.waitForExistence(timeout: 5))
        XCTAssertEqual(endpoint.value as? String, "https://subscription.example.invalid")
        app.buttons["credentialHistory"].tap()
        XCTAssertTrue(app.staticTexts["尚无已保存的完整配置"].waitForExistence(timeout: 5))
        capture("purchase-without-pin-no-history", app: app)
    }

    func testOfflineToastDisappearsAndAnotherSendRestartsItsLifetime() {
        let app = launch()
        app.buttons["Note"].tap()
        let editor = app.textViews["noteInput"]
        XCTAssertTrue(editor.waitForExistence(timeout: 5))
        editor.tap(); editor.typeText("first offline fixture")
        app.buttons["Keep it"].tap()
        let toast = app.staticTexts["noteErrorToast"]
        XCTAssertTrue(toast.waitForExistence(timeout: 5))
        capture("offline-toast", app: app)
        let gone = NSPredicate(format: "exists == false")
        expectation(for: gone, evaluatedWith: toast)
        waitForExpectations(timeout: 6)
        editor.tap(); editor.typeText("second offline fixture")
        app.buttons["Keep it"].tap()
        XCTAssertTrue(toast.waitForExistence(timeout: 5))
        expectation(for: gone, evaluatedWith: toast)
        waitForExpectations(timeout: 6)
    }

    // Delivery waits for Home/activate so the sheet is gone and the draft has changed
    // before the result arrives. This fixture does not validate Apple Sandbox delivery.
    func testLateFixtureDeliveryAfterDismissingSubscriptionDoesNotOverwriteDraftOrCurrentConfiguration() throws {
        let app = launch("delayed-delivery")
        let endpoint = app.textFields["endpointInput"]
        let tokenBefore = try XCTUnwrap(app.secureTextFields["tokenInput"].value as? String)
        let pinBefore = try XCTUnwrap(app.secureTextFields["pinInput"].value as? String)
        let confirmBefore = try XCTUnwrap(app.secureTextFields["confirmPinInput"].value as? String)
        XCTAssertEqual(endpoint.value as? String, "https://general.example.invalid")
        XCTAssertFalse(tokenBefore.isEmpty)
        XCTAssertFalse(pinBefore.isEmpty)
        XCTAssertFalse(confirmBefore.isEmpty)

        app.buttons["subscriptionEntry"].tap()
        let purchase = app.buttons["subscriptionPurchase"]
        XCTAssertTrue(purchase.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["subscriptionFill"].exists, "No cached purchase may masquerade as late delivery")
        purchase.tap()
        XCTAssertTrue(app.staticTexts["正在通过 App Store 购买…"].waitForExistence(timeout: 5))
        XCTAssertFalse(purchase.isEnabled)
        XCTAssertFalse(app.buttons["subscriptionFill"].exists)
        dismissSheet(app)
        XCTAssertTrue(endpoint.waitForExistence(timeout: 5))
        let unsavedEndpoint = "https://late-result-draft.example.invalid"
        replace(endpoint, with: unsavedEndpoint)
        XCTAssertEqual(endpoint.value as? String, unsavedEndpoint)

        XCUIDevice.shared.press(.home)
        let backgrounded = expectation(for: NSPredicate { _, _ in app.state != .runningForeground }, evaluatedWith: app)
        wait(for: [backgrounded], timeout: 5)
        app.activate()
        XCTAssertTrue(endpoint.waitForExistence(timeout: 5))

        // Reopen only to observe the completed delivery; never tap the explicit fill.
        app.buttons["subscriptionEntry"].tap()
        let delivered = app.staticTexts["凭据已交付。填入后仍需保存配置；服务激活可能需要稍候。"]
        XCTAssertTrue(delivered.waitForExistence(timeout: 5))
        let purchaseIdle = expectation(for: NSPredicate(format: "enabled == true"), evaluatedWith: purchase)
        wait(for: [purchaseIdle], timeout: 5)
        let fill = app.buttons["subscriptionFill"]
        for _ in 0..<4 {
            if fill.exists && fill.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(fill.waitForExistence(timeout: 5))
        capture("late-delivery-completed-without-fill", app: app)
        dismissSheet(app)
        XCTAssertTrue(endpoint.waitForExistence(timeout: 5))
        XCTAssertEqual(endpoint.value as? String, unsavedEndpoint)
        XCTAssertEqual(app.secureTextFields["tokenInput"].value as? String, tokenBefore)
        XCTAssertEqual(app.secureTextFields["pinInput"].value as? String, pinBefore)
        XCTAssertEqual(app.secureTextFields["confirmPinInput"].value as? String, confirmBefore)
        XCTAssertFalse(app.staticTexts["凭据已填入，保存后开始使用。"].exists)
        capture("08-late-draft", app: app)
        app.buttons["credentialHistory"].tap()
        XCTAssertTrue(app.navigationBars["凭据历史"].waitForExistence(timeout: 5))
        let original = app.buttons.containing(.staticText, identifier: "https://general.example.invalid").firstMatch
        let subscription = app.buttons.containing(.staticText, identifier: "https://subscription.example.invalid")
        XCTAssertTrue(original.images["historyCurrent"].exists)
        XCTAssertEqual(subscription.count, 1)
        XCTAssertFalse(subscription.firstMatch.images["historyCurrent"].exists)
        XCTAssertFalse(app.buttons.containing(.staticText, identifier: unsavedEndpoint).firstMatch.exists)
        capture("late-delivery-keeps-current-history", app: app)
    }

    // Captures the real fixture UI only; neither Apple Sandbox nor remote connectivity is asserted.
    func testCapturePurchaseAndHistoryDemonstration() throws {
        try capturePurchaseAndHistory(language: "zh-Hans", region: "zh_CN", chinese: true, prefix: "")
    }

    func testCapturePurchaseAndHistoryDemonstrationInEnglish() throws {
        try capturePurchaseAndHistory(language: "en", region: "en_US", chinese: false, prefix: "en-")
    }

    private func capturePurchaseAndHistory(language: String, region: String, chinese: Bool, prefix: String) throws {
        let app = launch(language: language, region: region)
        let historyTitle = chinese ? "凭据历史" : "Credential History"
        let generalTitle = chinese ? "通用" : "General"
        let confirmTitle = chinese ? "确认切换配置？" : "Switch configuration?"
        let switchTitle = chinese ? "确认切换" : "Switch"
        let cancelTitle = chinese ? "取消" : "Cancel"
        let generalURL = "https://general.example.invalid"
        let subscriptionURL = "https://subscription.example.invalid"
        let endpoint = app.textFields["endpointInput"]
        let tokenBefore = try XCTUnwrap(app.secureTextFields["tokenInput"].value as? String)
        let pinBefore = try XCTUnwrap(app.secureTextFields["pinInput"].value as? String)
        let confirmBefore = try XCTUnwrap(app.secureTextFields["confirmPinInput"].value as? String)
        XCTAssertEqual(endpoint.value as? String, generalURL)
        XCTAssertFalse(tokenBefore.isEmpty)
        XCTAssertFalse(pinBefore.isEmpty)
        XCTAssertFalse(confirmBefore.isEmpty)
        let general = app.buttons.containing(.staticText, identifier: generalURL)
        let subscription = app.buttons.containing(.staticText, identifier: subscriptionURL)
        let historyEndpoints = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'https://'"))

        func openHistory() {
            let history = app.buttons["credentialHistory"]
            for _ in 0..<3 {
                if history.isHittable { break }
                app.swipeDown()
            }
            XCTAssertTrue(history.isHittable); history.tap()
            XCTAssertTrue(app.navigationBars[historyTitle].waitForExistence(timeout: 5))
        }
        func showStatus(_ text: String) {
            let status = app.staticTexts[text]
            for _ in 0..<3 {
                if status.waitForExistence(timeout: 1) && status.isHittable { break }
                app.swipeUp()
            }
            XCTAssertTrue(status.waitForExistence(timeout: 5))
            XCTAssertTrue(status.isHittable)
        }

        // Remove only the seeded history tuple through the UI; the purchased cache remains.
        openHistory()
        XCTAssertEqual(general.count, 1)
        XCTAssertEqual(subscription.count, 1)
        subscription.firstMatch.swipeLeft()
        let delete = app.buttons.matching(NSPredicate(format: "label == '删除' OR label == 'Delete'")).firstMatch
        if delete.waitForExistence(timeout: 2) { delete.tap() }
        let removed = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: subscription.firstMatch)
        wait(for: [removed], timeout: 5)
        XCTAssertEqual(historyEndpoints.count, 1)
        XCTAssertEqual(general.count, 1)
        XCTAssertEqual(general.firstMatch.staticTexts["credentialSourceTag"].label, generalTitle)
        XCTAssertTrue(general.firstMatch.images["historyCurrent"].exists)
        dismissSheet(app)
        XCTAssertTrue(endpoint.waitForExistence(timeout: 5))
        XCTAssertEqual(endpoint.value as? String, generalURL)
        capture(prefix + "01-settings", app: app)

        app.buttons["subscriptionEntry"].tap()
        let purchase = app.buttons["subscriptionPurchase"]
        XCTAssertTrue(purchase.waitForExistence(timeout: 5))
        let ready = expectation(for: NSPredicate(format: "enabled == true"), evaluatedWith: purchase)
        wait(for: [ready], timeout: 5)
        capture(prefix + "02-subscription", app: app)
        purchase.tap()
        XCTAssertTrue(endpoint.waitForExistence(timeout: 5))
        XCTAssertEqual(endpoint.value as? String, subscriptionURL)
        XCTAssertEqual(app.secureTextFields["pinInput"].value as? String, pinBefore)
        XCTAssertEqual(app.secureTextFields["confirmPinInput"].value as? String, confirmBefore)
        showStatus(chinese ? "凭据已填入，保存后开始使用。" : "Credentials filled. Save to start using them.")
        capture(prefix + "03-filled", app: app)
        openHistory()
        XCTAssertEqual(historyEndpoints.count, 1)
        XCTAssertEqual(subscription.count, 0, "Filling a draft must not create its history entry")
        XCTAssertTrue(general.firstMatch.images["historyCurrent"].exists)
        dismissSheet(app)

        let save = app.buttons["saveSettings"]
        for _ in 0..<3 {
            if save.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(save.isHittable)
        XCTAssertTrue(save.isEnabled); save.tap()
        showStatus(chinese ? "配置已保存；连接状态见下方。" : "Configuration saved. See connection status below.")
        capture(prefix + "04-saved", app: app)
        openHistory()
        XCTAssertEqual(historyEndpoints.count, 2)
        XCTAssertEqual(general.count, 1)
        XCTAssertEqual(subscription.count, 1)
        XCTAssertEqual(general.firstMatch.staticTexts["credentialSourceTag"].label, generalTitle)
        XCTAssertEqual(subscription.firstMatch.staticTexts["credentialSourceTag"].label, "IAP")
        XCTAssertTrue(general.firstMatch.staticTexts["…ABCD"].exists)
        XCTAssertTrue(subscription.firstMatch.staticTexts["…EFGH"].exists)
        XCTAssertEqual(app.images.matching(identifier: "historyCurrent").count, 1)
        XCTAssertTrue(subscription.firstMatch.images["historyCurrent"].exists)
        XCTAssertFalse(general.firstMatch.images["historyCurrent"].exists)
        for secret in ["fixture-general-ABCD", "fixture-iap-EFGH", "Fixture-PIN-A", "Fixture-PIN-B"] {
            XCTAssertFalse(app.staticTexts[secret].exists)
        }
        capture(prefix + "05-history", app: app)

        general.firstMatch.tap()
        let confirmation = app.alerts[confirmTitle]
        XCTAssertTrue(confirmation.waitForExistence(timeout: 5))
        XCTAssertTrue(confirmation.buttons[switchTitle].exists)
        XCTAssertTrue(confirmation.buttons[cancelTitle].exists)
        capture(prefix + "06-confirm", app: app)
        confirmation.buttons[cancelTitle].tap()
        let cancelled = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: confirmation)
        wait(for: [cancelled], timeout: 5)
        XCTAssertEqual(historyEndpoints.count, 2)
        XCTAssertTrue(subscription.firstMatch.images["historyCurrent"].exists)
        XCTAssertFalse(general.firstMatch.images["historyCurrent"].exists)
        dismissSheet(app)
        XCTAssertTrue(endpoint.waitForExistence(timeout: 5))
        XCTAssertEqual(endpoint.value as? String, subscriptionURL)
        XCTAssertEqual(app.secureTextFields["pinInput"].value as? String, pinBefore)
        XCTAssertEqual(app.secureTextFields["confirmPinInput"].value as? String, confirmBefore)

        openHistory()
        general.firstMatch.tap()
        XCTAssertTrue(confirmation.waitForExistence(timeout: 5))
        confirmation.buttons[switchTitle].tap()
        XCTAssertTrue(endpoint.waitForExistence(timeout: 5))
        XCTAssertEqual(endpoint.value as? String, generalURL)
        XCTAssertEqual(app.secureTextFields["tokenInput"].value as? String, tokenBefore)
        XCTAssertEqual(app.secureTextFields["pinInput"].value as? String, pinBefore)
        XCTAssertEqual(app.secureTextFields["confirmPinInput"].value as? String, confirmBefore)
        showStatus(chinese ? "配置已切换；连接状态见下方。" : "Configuration switched. See connection status below.")
        // No Save tap after confirmation: the history marker proves activation happened already.
        openHistory()
        XCTAssertEqual(historyEndpoints.count, 2)
        XCTAssertEqual(app.images.matching(identifier: "historyCurrent").count, 1)
        XCTAssertTrue(general.firstMatch.images["historyCurrent"].exists)
        XCTAssertFalse(subscription.firstMatch.images["historyCurrent"].exists)
        XCTAssertEqual(subscription.firstMatch.staticTexts["credentialSourceTag"].label, "IAP")
        dismissSheet(app)
        XCTAssertTrue(endpoint.waitForExistence(timeout: 5))
        XCTAssertEqual(endpoint.value as? String, generalURL)
        showStatus(chinese ? "配置已切换；连接状态见下方。" : "Configuration switched. See connection status below.")
        capture(prefix + "07-switched", app: app)
    }

    // Local UIAppleStore fixture only; this does not validate Apple Sandbox transactions.
    func testFixturePurchasePreservesPINThenSaveCreatesFullHistoryAndBackgroundKeepsDraft() throws {
        let app = launch()
        let endpoint = app.textFields["endpointInput"]
        XCTAssertTrue(app.secureTextFields["pinInput"].exists)
        XCTAssertTrue(app.secureTextFields["confirmPinInput"].exists)
        let pinBefore = try XCTUnwrap(app.secureTextFields["pinInput"].value as? String)
        let confirmBefore = try XCTUnwrap(app.secureTextFields["confirmPinInput"].value as? String)
        XCTAssertFalse(pinBefore.isEmpty)
        XCTAssertFalse(confirmBefore.isEmpty)
        app.buttons["subscriptionEntry"].tap()
        XCTAssertTrue(app.buttons["subscriptionPurchase"].waitForExistence(timeout: 5))
        app.buttons["subscriptionPurchase"].tap()
        XCTAssertTrue(endpoint.waitForExistence(timeout: 5))
        XCTAssertEqual(endpoint.value as? String, "https://subscription.example.invalid")
        XCTAssertEqual(app.secureTextFields["pinInput"].value as? String, pinBefore)
        XCTAssertEqual(app.secureTextFields["confirmPinInput"].value as? String, confirmBefore)
        app.buttons["credentialHistory"].tap()
        XCTAssertTrue(app.buttons.containing(.staticText, identifier: "https://general.example.invalid").firstMatch.images["historyCurrent"].exists)
        XCTAssertEqual(app.buttons.containing(.staticText, identifier: "https://subscription.example.invalid").count, 1)
        dismissSheet(app)
        let save = app.buttons["saveSettings"]
        for _ in 0..<3 {
            if save.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(save.isEnabled); save.tap()
        XCTAssertTrue(app.staticTexts["配置已保存；连接状态见下方。"].waitForExistence(timeout: 5))
        for _ in 0..<3 {
            if app.buttons["credentialHistory"].isHittable { break }
            app.swipeDown()
        }
        app.buttons["credentialHistory"].tap()
        let saved = app.buttons.containing(.staticText, identifier: "https://subscription.example.invalid")
        let savedTwoTuples = expectation(for: NSPredicate(format: "count == 2"), evaluatedWith: saved)
        wait(for: [savedTwoTuples], timeout: 5)
        XCTAssertEqual(saved.count, 2, "Same endpoint/token with preserved PIN must remain a distinct full tuple")
        XCTAssertEqual(app.images.matching(identifier: "historyCurrent").count, 1)
        XCTAssertTrue(saved.containing(.image, identifier: "historyCurrent").firstMatch.exists)
        capture("fixture-purchase-saved-history", app: app)
        dismissSheet(app)
        endpoint.tap()
        endpoint.typeText("draft")
        let unsavedEndpoint = try XCTUnwrap(endpoint.value as? String)
        XCTAssertNotEqual(unsavedEndpoint, "https://subscription.example.invalid")
        XCUIDevice.shared.press(.home)
        app.activate()
        XCTAssertTrue(endpoint.waitForExistence(timeout: 5))
        XCTAssertEqual(endpoint.value as? String, unsavedEndpoint)
        XCTAssertEqual(app.secureTextFields["pinInput"].value as? String, pinBefore)
        XCTAssertEqual(app.secureTextFields["confirmPinInput"].value as? String, confirmBefore)
        for _ in 0..<3 {
            if save.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(save.isEnabled); save.tap()
        XCTAssertTrue(app.staticTexts["配置已保存；连接状态见下方。"].waitForExistence(timeout: 5))
        for _ in 0..<3 {
            if app.buttons["credentialHistory"].isHittable { break }
            app.swipeDown()
        }
        app.buttons["credentialHistory"].tap()
        let general = app.buttons.containing(.staticText, identifier: unsavedEndpoint).firstMatch
        XCTAssertTrue(general.images["historyCurrent"].waitForExistence(timeout: 5))
        XCTAssertEqual(general.staticTexts["credentialSourceTag"].label, "通用")
        capture("general-save-after-draft-edit", app: app)
    }
}
