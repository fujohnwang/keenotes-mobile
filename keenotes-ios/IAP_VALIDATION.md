# iOS 凭据历史与 IAP 验证记录

日期：2026-09-14。工作树：`/Users/af/CodexProfiles/codex-fq/worktrees/6278/keenotes-mobile`。基线：`6c1ddb916bf4cef378e1236970fb5adbd51665da`。未提交 git。

**用户最新约束：停止新测试，不下载或继续使用 Simulator/runtime；后续测试最多仅允许联机 ip17。** 下列 Simulator 命令仅保留为已完成验证的历史记录，不应继续执行。功能源码保持冻结。

## 交付状态

功能源码已冻结并通过实施侧 iPad、监督侧独立 iPhone 本地验收，各 25 项行为测试 + 4 项 UI 测试，零失败、零运行时跳过。两次命令均显式排除了单独列出的 `LocalStoreKitTests`，因此不代表完整 Apple 购买链路通过。Release simulator 编译成功。监督侧已关闭本轮报告的源码问题。

保留已有版本 1.8.2、签名团队 NG744CF46S、bundle ID cn.keevol.keenotes 和 iOS 15 deployment target；仅局部修改现有 pbxproj，未通过 XcodeGen 覆盖工程。实际运行验证环境是 Xcode 26.6 (17F113)、iOS 26.5；没有 iOS 15 runtime 实测或设备归档结果。

## 改动与取舍

- 凭据以完整 `(endpoint, token, PIN)` 区分。当前配置、历史和切换 journal 同项保存至 Keychain；迁移写入成功后才删除旧明文 defaults。删历史不清空当前配置，也不在启动时自动补回。
- 手工 Save 与历史确认走同一切换边界：停止新操作、等待在途发送/同步、复查 pending，再以 journal 和 SQLite transition marker 完成可重放切换。有 pending 时阻止 tuple 变化；失败阶段决定恢复旧配置或等待恢复。
- 发送先保存原 request_id。HTTP 成功但 SQLite 清理失败时保留同一请求，不恢复可重复发送的新草稿。切换清理旧账户内存缓存，拒绝旧查询、旧 HTTP 和旧 WebSocket 回调污染新配置。
- WebSocket 批量 JSON/Argon2/AES 解码放到独立 actor，按条串行处理，避免阻塞 MainActor 或并行堆叠 Argon2 内存。原通用同步的 TLS 兼容策略保持；IAP 使用独立 URLSession、系统 TLS 校验、无重定向、无 PIN/Bearer。
- StoreKit 监听覆盖启动、前台恢复及未完成交易；只有显式恢复购买调用 AppStore.sync。交付成功且独立购买凭据 Keychain 落盘后才 finish；重试区分 automatic/user_initiated，停止生命周期后不再创建重试任务。
- 历史页与订阅页为独立 sheet。历史仅显示 token 尾 4 位；确认直接启用完整 tuple。订阅显式填入仅修改 endpoint/token，保留 PIN，仍需 Save；后台交付不覆盖草稿。首次引导、clipboard/confetti 和可取消的 3 秒 toast 保留。
- 测试使用独立 Keychain namespace、临时 SQLite 和本地 fixture。`UITestFixture` 仅在 DEBUG 且显式启动参数下启用；fixture 商品、价格和 `.invalid` endpoint 均不是正式配置。

## 文件清单

下列路径均相对本工作树：

| 范围 | 文件 |
| --- | --- |
| 新增凭据服务 | `keenotes-ios/KeeNotes/Services/CredentialsStore.swift`、`ConnectionConfigurationCoordinator.swift`、`SettingsDraft.swift` |
| 新增 IAP 服务 | `keenotes-ios/KeeNotes/Services/AppleProvisioningClient.swift`、`StoreKitPurchaseService.swift` |
| 新增解码/测试入口 | `keenotes-ios/KeeNotes/Services/WebSocketMessageDecoder.swift`、`UITestFixture.swift` |
| 修改基础服务 | `keenotes-ios/KeeNotes/Services/KeychainService.swift`、`SettingsService.swift`、`DatabaseService.swift`、`ApiService.swift`、`PendingNoteService.swift`、`WebSocketService.swift` |
| 新增界面 | `keenotes-ios/KeeNotes/Views/CredentialHistoryView.swift`、`SubscriptionView.swift` |
| 修改应用/界面 | `keenotes-ios/KeeNotes/App/KeeNotesApp.swift`；`keenotes-ios/KeeNotes/Views/SettingsView.swift`、`MainTabView.swift`、`NoteView.swift`、`TopHeaderView.swift` |
| 工程配置 | `keenotes-ios/KeeNotes/Info.plist`、`keenotes-ios/project.yml`、`keenotes-ios/KeeNotes.xcodeproj/project.pbxproj`、`keenotes-ios/KeeNotes.xcodeproj/xcshareddata/xcschemes/KeeNotes.xcscheme`、`KeeNotesLocalStoreKit.xcscheme` |
| 行为测试 | `keenotes-ios/KeeNotesTests/CredentialBehaviorTests.swift`、`PurchaseBehaviorTests.swift`、`ProvisioningBoundaryTests.swift`、`ConnectionRaceTests.swift` |
| Apple 本地测试 | `keenotes-ios/KeeNotesTests/LocalStoreKitTests.swift`、`LocalAnnual.storekit` |
| UI 测试 | `keenotes-ios/KeeNotesUITests/SettingsFlowUITests.swift` |
| 验证工具 | `keenotes-ios/scripts/validate-iap.sh`、`tls-fixture.py`、`websocket-fixture.py` |
| 文档 | `keenotes-ios/IAP_VALIDATION.md`、`implementation_note.md` |

## 验证结果与证据

| 项目 | 结果 | 日志与结果包 |
| --- | --- | --- |
| 基线 simulator build | 通过 | `/tmp/keenotes-iap-validation/baseline-retry.log` |
| 冻结源码 iPad 全部本地用例 | 29 通过 | `/tmp/keenotes-iap-validation/final-ipad.log`、`final-ipad.xcresult` |
| 监督侧独立候选 iPhone 全部本地用例 | 29 通过 | `/tmp/keenotes-iap-supervisor-20260914/ios-candidate.log`、`ios-candidate.xcresult` |
| Release simulator build | 通过 | `/tmp/keenotes-iap-validation/release-build.log` |
| 真正 SKTestSession/SystemAppleStore 测试 | 未通过，配置同步返回 SKInternalErrorDomain Code=3，商品为空 | `/tmp/keenotes-iap-validation/storekit-local-2.log`、`storekit-local-2.xcresult` |

行为测试包括真实 Security.framework 读写、SQLite transaction/trigger 故障恢复、history 删除与切换等待的竞争、旧账户延迟查询隔离、购买重试/finish 顺序、固定路由与契约字段、HTTP 302 拒绝重定向、真实自签 HTTPS 拒绝、真实 URLSession WebSocket 与 SQLite 写入竞争、12 条真实 V2 加密笔记的解密及 MainActor 心跳。StoreKit 状态机测试使用注入的交易事件，不能替代真实 Apple 测试。

4 项 UI 测试覆盖历史取消/确认/脱敏、订阅 sheet 草稿保留及显式填入后仍需 Save、空配置首次引导及无 PIN 时不生成历史、连续两次离线发送的 toast 生命周期。截图已目视检查：

- iPhone 历史：[截图](/tmp/keenotes-iap-validation/ui-iphone-attachments/8C36C2E3-EDED-4D43-8725-BC507925D6EA.png)；订阅：[截图](/tmp/keenotes-iap-validation/ui-iphone-attachments/ACEAB586-9731-4B76-9E90-C85951D676BF.png)。
- iPad 历史：[截图](/tmp/keenotes-iap-validation/ui-ipad-attachments/86752389-4CCB-4D92-8B40-F391147FA603.png)；订阅：[截图](/tmp/keenotes-iap-validation/ui-ipad-attachments/44940BAD-7DEC-4AF4-92B2-AB481910A0D1.png)；填入尚未保存：[截图](/tmp/keenotes-iap-validation/ui-ipad-attachments/17FF6BA9-432A-4FF6-B64B-CB888BF6645E.png)。
- 完整截图清单位于上述 `ui-iphone-attachments/manifest.json` 与 `ui-ipad-attachments/manifest.json`。

早期失败日志保留：首次 Keychain 用例因无 ad-hoc 签名报 -34018，修正测试签名后通过；早期 WS fixture 帧长度及 UI 测试滚动/选中文本问题已修复；磁盘不足的一次 iPad 执行未跑用例。`all-iphone-2.log` 的 UI 4 项通过，但该整次命令失败，不作为最终全套通过证据。

## 历史验证命令（当前约束下不再执行）

以下为在工作树根目录执行的本次 iPad 最终验证实际命令，执行时 TLS 18443 与 WS 18444 两个本地 fixture 已运行。对应专用 Simulator 和 build 缓存已按用户要求删除，fixtures 已停止：

```bash
xcodebuild -project keenotes-ios/KeeNotes.xcodeproj -scheme KeeNotes -destination 'platform=iOS Simulator,id=38C56FD3-65E1-47C3-A654-549F43E3763C' -derivedDataPath /tmp/keenotes-iap-validation/build -disableAutomaticPackageResolution -skip-testing:KeeNotesTests/LocalStoreKitTests -parallel-testing-enabled NO -resultBundlePath /tmp/keenotes-iap-validation/final-ipad.xcresult CODE_SIGN_IDENTITY=- test > /tmp/keenotes-iap-validation/final-ipad.log 2>&1
```

保留的验证脚本可启动/退出本地 fixtures，分别执行 25 项行为和 4 项 UI 测试、导出截图；当前禁止继续执行。脚本不自动清理已有目录或 erase simulator：

```bash
bash keenotes-ios/scripts/validate-iap.sh '<DEDICATED_SIMULATOR_UDID>' /tmp/keenotes-iap-validation/reproduction-1
```

本次 Release 验证实际命令：

```bash
xcodebuild -project keenotes-ios/KeeNotes.xcodeproj -scheme KeeNotes -configuration Release -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath /tmp/keenotes-iap-validation/build -disableAutomaticPackageResolution CODE_SIGNING_ALLOWED=NO build > /tmp/keenotes-iap-validation/release-build.log 2>&1
```

真实本地 StoreKit 用例曾独立执行，当前环境已复现失败，不计入上面 29 项。下面仅记录对应命令形式，按最新约束不再使用 Simulator 重试：

```bash
xcodebuild -project keenotes-ios/KeeNotes.xcodeproj -scheme KeeNotesLocalStoreKit -destination 'platform=iOS Simulator,id=<DEDICATED_SIMULATOR_UDID>' -derivedDataPath /tmp/keenotes-iap-validation/build -only-testing:KeeNotesTests/LocalStoreKitTests -parallel-testing-enabled NO -resultBundlePath /tmp/keenotes-iap-validation/storekit-recheck.xcresult CODE_SIGN_IDENTITY=- test > /tmp/keenotes-iap-validation/storekit-recheck.log 2>&1
```

## 外部待办

2026-09-14 用户已暂定 US$8/年（USD），这是正式商品的定价决策，尚未配置到 ASC。客户端价格继续读取 StoreKit 的本地化商品价格，不将此金额硬编码到 UI；本地 fixture 的 1.00 价格保持为测试数据。

Debug/Release 的下列 build settings 均保留为空，由 Info.plist 引用。应提供正式值后进行 Apple Sandbox/TestFlight 端到端验收：

| 设置 | 所需配置 |
| --- | --- |
| `IAP_ANNUAL_PRODUCT_ID` | 正式自动续订年订阅 Product ID；显示价格来自 StoreKit 本地化商品 |
| `IAP_PRODUCTION_PROVISION_URL` | Production 独立 HTTPS 交付完整 URL |
| `IAP_SANDBOX_PROVISION_URL` | 与 Production 不同的 Sandbox HTTPS 交付完整 URL |
| `IAP_TERMS_URL` | 正式服务条款 URL |
| `IAP_PRIVACY_URL` | 正式隐私政策 URL |

`LocalAnnual.storekit` 中 `local.test.annual` 和 1.00 价格仅为本地测试数据。未配置正式 SKU/价格，未修改 ASC 商品、部署服务端、迁移生产数据、上传 TestFlight 或提交审核。真实购买/恢复/续订/退款撤销及服务端独立 entitlement 的端到端验证仍待完成；本地通过不等于上线条件已满足。

## 按用户要求清理（2026-09-14）

- 已停止本任务 TLS 18443、WebSocket 18444 fixtures，未启动任何新测试，也未下载 runtime。
- 仅删除本任务专用 Simulator：`BADADBF1-10C3-421B-B77D-01AC399505C7`（KeeNotes-IAP-Validation-iPhone）、`38C56FD3-65E1-47C3-A654-549F43E3763C`（KeeNotes-IAP-Validation-iPad）。删除前后设备清单比较确认其他 device identifiers 全部保留；未删除预存设备或 runtime。
- 删除 `/tmp/keenotes-iap-validation/build` 可重建缓存。其内部 `Logs` 已移至 `/tmp/keenotes-iap-validation/preserved-deriveddata-logs`；基线源码、所有外部日志、xcresult、截图及本工作树源码保留。未修改其他项目缓存或用户设备数据。
- 按删除前 `du -sk` 扣除保留日志计算，本次释放约 **1.43 GiB**：build 1,462,488 KiB，加两个 Simulator 各 17,916 KiB，减保留日志 140 KiB。清理后磁盘可用约 19.18 GiB（`df` 为 20,113,880 KiB；受系统其他活动影响，不将其差值全归因于本次操作）。
- 清理证据：`/tmp/keenotes-iap-validation/cleanup-sizes.log`、`cleanup-simulators-before.json`、`cleanup-simulators-after.json`。后续仅允许联机 ip17，功能源码保持冻结。
