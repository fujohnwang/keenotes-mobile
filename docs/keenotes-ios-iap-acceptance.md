# KeeNotes IAP supervisor 验收记录

## 本轮验收范围（2026-09-19）

本轮结果：27 个业务/UI 用例均已取得通过结果，独立系统 StoreKit 本地观察用例 1/1 通过；原 SKTestSession Code=3 和真实 Apple 端到端测试仍未关闭。详见 [本轮完整验收与演示稿](keenotes-iap-20260919-verification.md)。

用户明确：TestFlight 尚未测试。ASC 中的 VALID / IN_BETA_TESTING 只证明构建可用于测试，不证明实际购买通过。Worker 本日只读核查确认线上版本未变，Production / Sandbox 的交易、身份数量均为 0，各仅有一条 TEST 通知。

继续复用原有 iPhone 17 / iOS 26.5，不创建、下载或克隆其它设备。新增 UI 竞态测试覆盖：购买交付等待时关闭订阅页、修改草稿，回前台后收到交付仍不自动覆盖草稿或切换当前配置。客户端 fixture、系统 StoreKit 本地交易和真实 Sandbox 结果分开记录。

旧 `/tmp` 与默认 DerivedData 原始结果已不存在；下文为历史验收记录。本轮结果与截图保存在项目 `test-results/iap-20260919/`，交互演示稿只使用实际采集截图。

以下为 9 月 14 日历史记录。

日期：2026-09-14。状态：初版本地代码与行为验收通过并集成；同一生产 Worker 双环境已部署并通过独立复验（38/38）及线上 smoke。最新 1.9.0（2）已为 VALID / IN_BETA_TESTING；单台现有 iPhone 17 上的保存反馈修复回归 6/6 通过。本地系统 StoreKit 失败项与真实 Apple 端到端验收尚未关闭。最新进度见 [部署记录](keenotes-iap-release.md)。

用户最新测试约束：停止 ip17 操作，改为仅使用一个 iPhone 模拟器，不创建 iPad。复用已有 iOS 26.5 runtime / iPhone 17（6A3B455A-6971-4FF1-A64D-B71160B1F2A9），无需下载；关闭并行测试，避免额外设备克隆。下文首轮 Simulator 结果仍作为历史证据，本轮新增结果另行记录。

## 基线与职责

- iOS 仓库基线：6c1ddb9，master；实施前已有实施计划与 implementation_note.md 的本轮文档改动，集成时必须保留。
- Workers 仓库基线：5eece82，master；实施前工作区干净。
- 两个独立实施任务分别负责 iOS 与 Workers；本任务仅负责契约、监督、独立审查、验收与变更集成。
- iOS 任务：01a09e80-3c7d-74f2-9861-ef4b6a255c0d；worktree：/Users/af/CodexProfiles/codex-fq/worktrees/6278/keenotes-mobile。
- Workers 任务：01a09e80-54c8-72c0-a04b-0fd0301f6be9；worktree：/Users/af/CodexProfiles/codex-fq/worktrees/9656/keenotes-remote-workers。
- 验收依据：实施计划书第 8 节及 keenotes-iap-integration-contract.md。

## 验收门槛

1. 独立审查：身份与鉴权、Keychain 持久化、迁移、配置切换与迟到回调；未解决的高风险问题不得通过。
2. 构建与行为测试：由 supervisor 独立运行关键测试，不仅引用实施者结论。
3. 两端契约联调：成功、失败、待交付、重复与恢复行为一致；本地测试与真实 Apple 测试分开记录。
4. UI 验收：iPhone/iPad、两个 sheet、历史二次确认、草稿保持、IAP 填表后仍需 Save。
5. 回归：通用凭据、原订阅链路、笔记同步及 pending 隔离。
6. 真实 Sandbox/TestFlight：只有获得实际证据后才能通过；商品/凭据/环境缺失作为外部阻塞列出，不以模拟测试替代。

## 发布准备现状

- 美国价格 USD 8/年、正式SKU cn.keevol.keenotes.remote.yearly、175地区价格矩阵/可售范围均已写入并验证。App6757829752，订阅组22383447，商品6811796283。
- 1.9.0（1）已归档、签名导出、上传ASC，processingState=VALID、internalBuildState=IN_BETA_TESTING；专用内部组仅用户本人。
- 用户已明确授权既有Worker生产部署、TestFlight Sandbox测试及专用新key写入该Worker。006增量迁移、四项secrets配置、生产部署与双环境真实TEST通知核验已完成。没有授权公开AppStore发布。

## 历史实施与本地验收进度

- 两端契约已固定，两个实施任务已启动；均从记录的干净基线进入独立 worktree。
- supervisor 已提醒 iOS 保留实际 pbxproj 的 1.8.2 版本与签名配置，避免 project.yml 的旧值导致生成回退。
- 预审补充两项约束：IAP 使用正常系统 TLS 的独立会话；Apple 主动停用凭据不能被后台 provision/续订/对账自动恢复。契约增加 intent（默认 automatic）与 ACCESS_DISABLED，只有明确的 user_initiated 且有效权益可恢复访问。
- 已启动两项独立只读预审：Standards（生命周期、持久化及错误恢复）和 Spec（需求覆盖、原同步行为回归）。实施未完成，预审通过不代表最终验收通过。
- Workers 实施者确认：未完成投影的重试 alarm 不能被握手改为长期到期 alarm；待 supervisor 实跑故障测试验证。
- 旧版本 DO 尚未持久化 token 的连接在升级时需断开重连，实施者已明确此迁移行为；新连接的 token 与握手状态持久化，待验证非 Apple 回归。
- 已设置本任务后台跟进（keenotes-iap），在实施或验收仍未完成时继续监督，仅实质变化时通知；这不替代独立验收证据。

## 行为证据清单

下表反映最终候选的本地证据。早期失败记录保留于后续章节；本地通过不代表真实 Apple 验收通过。

| 范围 | 必须覆盖的关键路径 | 证据状态 |
| --- | --- | --- |
| Keychain | 旧完整配置迁移、完整 tuple 去重、不同 PIN 保留、写入/读取失败保护、删除当前历史不撤销配置 | 独立 iOS 行为测试通过，真实 Security.framework / SQLite |
| 配置切换 | 确认前与取消后零副作用；确认后保存并连接；pending 与发送失败落库隔离；崩溃恢复 | 独立行为及 UI 测试通过；包含删除历史与在途操作的竞争 |
| IAP 交付 | 验签失败、pending、取消、Keychain 失败不 finish、重复交易、重试、显式/自动 intent、迟到结果不填表 | 注入交易事件的状态机测试通过；实际 Apple 交易未通过 |
| 网络契约 | 固定环境 URL、正常 TLS、拒绝重定向、不发 PIN、错误分类、秒/毫秒与 grace 截止 | 两端独立边界测试通过；真实自签 HTTPS 被拒绝，302 不跟随 |
| Workers 事实 | 并发首次购买固定 token、同身份不同链、旧退款与新续订乱序、重复通知、grace/过期 | 独立 workerd / D1 测试通过；含退款撤回遗漏后的 Server API 修复 |
| 授权恢复 | D1/KV/DO 写失败重试、alarm 保留、DO 重启/休眠、停用持久化、自动恢复不重新激活 | 独立故障注入测试通过，客户端重放不能清除撤销事实 |
| 笔记与旧渠道 | 原 HTTP/WS 协议、同步批次/广播/重连、Gumroad/admin 行为与邮件、退款后 HTTP/WS 均拒绝 | 本地回归通过；含真实 WS、迟到写入、旧连接升级重连、迁移保留旧数据 |
| UI | iPhone/iPad、右上订阅 sheet、历史 sheet、最后四位与 tag、PIN 隐藏、草稿与 Save 语义 | iPhone 独立 4 项 UI 通过并目视检查；iPad 实施侧 4 项通过，supervisor 复核截图 |
| 真实 Apple | Sandbox 购买/恢复/续订/退款及服务端通知，对应正确 KeeNotes 商品与环境 | 外部配置待定 |

## 第一轮独立预审（14:20）

Standards 与 Spec 分开记录；以下全部已交回实施任务，代码修改尚须独立行为复验。

- Standards：IAP URLSession 与 delegate 循环引用；普通 token 的错误进入 Apple alarm 循环。实施者已反馈修正，待复验。
- Spec：首次向导触发、确认密码 field ID 与 toast 消失回归；往年今日内存缓存及迟到查询跨账号隔离；HTTP 成功但 pending 清理失败被误判为未保存导致重复发送；保存等待期间历史删除被旧 envelope 写回。
- iOS 实施者交付首批 8 项真实 Keychain/SQLite 测试通过证据：/tmp/keenotes-iap-validation/history-tests-signed.xcresult。未签名宿主的首次失败（-34018）保留记录；通过结果来自 ad-hoc 签名专用模拟器。supervisor 尚未独立复跑，不能据此关闭条目。
- Workers 首批 workerd 集成测试仍有 KV 删除关闭连接、grace 到期关闭连接两项超时；尚未通过。
- Workers 远程 secrets 只读检查仅确认现有 ADMIN_TOKEN / GUMROAD_ACCESS_TOKEN / RESEND_API_KEY，Apple Server API 所需密钥尚未配置；未读取或记录 secret 值。

## Supervisor 独立运行（14:23）

- 将两端当时源码复制到 /tmp/keenotes-iap-supervisor-20260914 下的独立 snapshot，记录 SHA256 source-manifest.json；后续新改动不能沿用此次通过结论。
- iOS：独立空白模拟器 1DE741A0-18FC-4B31-A553-107BA058B6B0，独立 DerivedData，ad-hoc 签名宿主，执行 `xcodebuild ... -only-testing:KeeNotesTests/CredentialBehaviorTests CODE_SIGN_IDENTITY=- test`。8/8 通过。证据：/tmp/keenotes-iap-supervisor-20260914/history-independent.xcresult 与 history-independent.log。覆盖迁移、读写失败、完整 tuple 去重、pending/lease 阻止切换、SQLite/Keychain 两阶段失败恢复与草稿版本保护。新退回的缓存/删除并发/发送清理问题尚不在此快照测试内。
- Workers：/opt/homebrew/bin/npm run typecheck 通过；独立快照运行 `/opt/homebrew/bin/npm test`，14 个行为子测试与父项全部通过（测试器计 15/15）。证据：/tmp/keenotes-iap-supervisor-20260914/worker-independent.log。实际本地 workerd/D1/KV/DO，Apple 验签/Server API 边界注入 fixture；不是 Apple Sandbox。
- Workers 此批覆盖固定身份、旧期退款乱序、存量 WS 撤销、旧 KV 拒权、停用 intent、KV 失败补偿及 alarm、grace 到期、延迟 SQL 写拒绝、DO 重建与 runtime 重启、普通 token malformed JSON 无 Apple alarm。此前两个关闭超时经测试桥连接就绪处理后独立复跑通过。
- 当前结论：首批存储与授权行为通过，整体验收仍在进行。官方验签运行时、完整 IAP 客户端、UI、最终变更复审及真实 Apple 环境未关闭。

## 第二轮预审与运行（14:40）

- iPhone 手动 UI：supervisor 使用自建空白设备验证订阅独立 sheet、历史显示 endpoint/末四位/通用 tag/当前标记、点击条目先确认、取消保留未保存 endpoint 草稿与当前记录顺序、确认后保存并更新当前标记。证据：history-confirmation.jpg、subscription-entry.jpg（同 supervisor 临时目录）。此批使用 example.invalid 测试凭据，未连接生产。
- 第一轮报告的实现问题已在第二轮源码复审确认修正，仍以最终统一运行结果为准。
- 新退回 Standards：iOS 整类 MainActor 导致真实 Argon2id 批次解密阻塞 UI；Workers 非 2xx response 提前清理超时，错误 body 挂起无法超时。
- 新退回 Spec：退款撤回通知漏达后，当前有效 Server API 快照不能消除已存 revoked 状态；要求区分权威快照与客户端重放，并保持版本/并发安全。
- supervisor worker-snapshot-v2 独立运行结果：27 计数中 22 通过、5 失败（含父项）。官方完整验签正例返回 INVALID_STOREKIT_JWS，连带 4 子项失败；日志 worker-independent-v2.log 已交回定位，未接受实施者先前全通过结论。
- iOS 实施者反馈扩展存储/IAP/TLS与4项iPhone UI已通过；真实WS测试发现Python fixture短帧编码错误，待最终复跑，不沿用失败整体日志为通过证据。
- 系统 StoreKitTest 在当前 Xcode 26.6 / iOS 26.5 上多次返回 SKInternalErrorDomain Code=3。Apple DTS 已在 https://developer.apple.com/forums/thread/826971 确认过同类问题；当前环境的实际失败保留，不将假交付测试视为系统 StoreKitTest 通过，也不做无界同环境重试。
- 磁盘不足曾阻塞iPad构建；supervisor仅清理本轮自建模拟器与可再生成DerivedData（保留快照/日志/xcresult/截图），释放后约2.1GiB。后续验收需避免重复创建大体积产物。

## 最终本地验收与集成（15:00）

- Standards / Spec 两条独立审查报告的具体问题均已修复并复核。Argon2 批次解密移入独立串行 actor，真实 12 条 V2 笔记与 MainActor 心跳测试通过；非 2xx 挂起 body 保持超时；Server API 权威快照可修复遗漏的退款撤回，同时拒绝旧客户端重放和并发旧版本。
- iOS 最终独立候选：25 项行为 + 4 项 UI，共 29/29 通过。命令明确排除 `LocalStoreKitTests`，不能称全部 Apple 测试通过。证据：`/tmp/keenotes-iap-supervisor-20260914/ios-candidate.log`、`ios-candidate.xcresult`、`ios-ui-attachments/`。
- iPad：实施任务最终同组 29/29 通过，supervisor 检查最终日志和订阅/历史截图；没有宣称 supervisor 另跑一次 iPad。证据：`/tmp/keenotes-iap-validation/final-ipad.log`、`final-ipad.xcresult`、`ui-ipad-attachments/`。Release simulator 构建成功见同目录 `release-build.log`。
- Workers 最终独立候选：32/32 通过，无失败或跳过，证据 `/tmp/keenotes-iap-supervisor-20260914/worker-final-accepted.log`。测试实际使用本地 workerd/D1/KV/DO，并在 Workers runtime 验证官方 Apple 库的完整 JWS/OCSP；Apple 网络响应为受控 fixture，不是 Apple Sandbox。
- 官方验签早期正例失败源自测试子进程选到 LibreSSL 默认 SHA-1 OCSP。修复为确定选择 OpenSSL 3 并显式签 SHA-256，未放宽生产验签；supervisor 重跑正反例通过。最后的旧 DO 无身份连接关闭、存量 migration 数据保留及 expires_at 秒边界也已纳入最终候选复验。
- TypeScript 检查通过；实施侧 Production/Sandbox dry-run、本地隔离新库 schema 与健康 SQL 通过。没有部署、远程 migration、真实 Sandbox 交易、真机归档或 iOS 15 实机测试证据。
- 已集成到 `/Users/af/workspace.javafx/keenotes-mobile` 与 `/Users/af/workspace.keenotes/keenotes-remote-workers`。集成前逐文件校验原仓库仍为基线内容，功能文件匹配独立测试快照 SHA256，集成后逐字节复核；保留本轮原有计划与 notes 文档改动。未 stage、未 commit。清单为 supervisor 临时目录中的 `ios-integration-manifest.json` 与 `worker-integration-manifest.json`。
- 完整实施交付说明分别为原仓库 `keenotes-ios/IAP_VALIDATION.md`、Workers `AppleImplementationReport.md` / `AppleDeploymentRunbook.md`。临时证据不依赖可重建的 DerivedData；用户磁盘约束已同步两个任务及后台跟进。
- 最终功能文件的 SHA256 已持久记录到 [keenotes-iap-accepted-files.json](keenotes-iap-accepted-files.json)：iOS 35 个、Workers 29 个文件逐字节一致。两个原仓库 `git diff --check` 通过，暂存区为空。
- 收尾清理仅涉及本轮两个专用 Simulator、TLS/WS fixture 和两端本轮 iOS DerivedData，合计约 1.7 GiB；已有设备及 runtime 保留。iOS 清理证据为 `/tmp/keenotes-iap-validation/cleanup-sizes.log` 和 simulator 前后清单。后续不运行历史 Simulator 命令。

## 真实环境验收现状

1. US$8/年、正式SKU及175地区价格/可售范围已完成；审核截图已由 SystemAppleStore 使用 ASC 同步目录取得，上传后 delivery=COMPLETE、商品 READY_TO_SUBMIT。没有使用 $1 UI fixture，也未提交公开审核。
2. 下载丢失问题已通过重建7L6NS8ZNPA解决：私钥安全保存项目.secrets（600、Git ignored、格式PASS），用户明确批准后根已配置全部四项Worker secrets。同Worker四条固定路由，双环境38/38独立测试和两轴审查通过；原仓库29项hash匹配。线上006完成且旧三表行数未变，使用现有TimeTravel恢复点（全量本地导出取消）。Worker已实际部署，9/9线上smoke通过；真实Apple两环境TEST均SUCCESS且D1逐UUID/环境关联通过，无错误环境记录，Apple身份仍0。真实购买交易验收仍待完成。
3. 真实购买、恢复、续订、退款/撤回、Server API/通知与 HTTP/WS 权益执行尚缺实际交易证据。用户已停止 ip17 操作；本轮模拟器仅补齐客户端本地测试，不将 Xcode 交易冒充 Apple Sandbox，不为本地交易放宽线上验签。
4. 系统 StoreKitTest Code=3 的实际失败继续单列；按用户最新授权，在唯一现有 iPhone 17 上排查并复验，修复后记录根因与独立证据。

## 单一 iPhone 17 补验（2026-09-14）

- 只复用已有 iOS 26.5（23F77）/ iPhone 17，未下载 runtime 或创建新设备；根只读确认运行中的设备仅 `6A3B455A-6971-4FF1-A64D-B71160B1F2A9`。
- CLI 定向测试与根在 Xcode IDE Test navigator 直接运行的单项均失败：SKTestSession 保存/重置配置返回 Code=3，商品为空。IDE summary 确认 1 项、0 PASS、1 FAIL、0 skipped；证据 `/tmp/keenotes-iap-release-20260914/ios-simulator/storekit-ide-directed.xcresult`。同一配置已正确打包，根因尚未确定，不能以缺少 xctestrun 配置字段直接归因。
- 独立复核发现本地测试 scheme 与 project.yml 范围漂移，已同步为仅 LocalStoreKitTests，设置 `parallelizable=NO`、`useTestSelectionWhitelist=YES`。第一次 IDE 测试因遗漏 whitelist 属性意外跑入其他测试，已正常停止并保留失败现场；不能作为通过证据。随后通过单方法菜单完成上述独立对照。
- 补验发现 P2：整页 configurationRevision identity 会重建 Settings，丢弃通用/IAP Save 成功提示。已修复为 Settings 保持身份，Note/Review 仍按账户 revision 重建；history 仅在 apply 和共享 draft 更新成功后回调显示切换提示。未加入会覆盖新草稿的 revision 监听。
- 修复后同一设备串行回归 **6/6 PASS、0 skipped**（4 项 UI、2 项草稿/账户隔离行为）。恢复 Save 成功文案断言，并覆盖通用 endpoint 保存、history 取消/确认、购后保留 PIN、完整 tuple 历史和后台草稿。根核对原始 summary、源码与精确 diff，独立 Standards 复审 PASS，P2 关闭。证据 `/tmp/keenotes-iap-release-20260914/ios-simulator/save-status-fix/`。
- 新候选为 1.9.0（2）；业务增量仅上述 3 个 View。根逐一核对最终 manifest 的 99 个应用/配置文件 hash 全部匹配，IPA 无 StoreKit fixture、测试包或私钥。UI fixture 仍不能替代真实 Apple Sandbox。
- 根追加一次有不同前提的 IDE 对照：明确选择 LocalAnnual 配置，Run Without Building 预热，再执行单个系统测试。系统显示不扣费的 Xcode 购买窗口并完成购买；测试继续执行后仅 `LocalStoreKitTests.swift:24` 的 unfinished 断言失败。SKTestSession 自动控制仍报 Code=3。方案引用路径语义未变，不能把先前控件显示 None 单独认定根因，也不能把本次整体记为 PASS。
- 定点只读复查发现 storekitd 在 purchase 返回后报告缺少 `original-transaction-id`、随后 unfinished=0，首次应用 finish 晚于失败断言。正式宿主 SKU guard 会拒绝本地 SKU，Xcode 环境也不能请求在线交付；尚无提前 finish 证据。缺少同一笔交易 ID 的对照，不能据此断言具体系统根因，原断言保留。报告 `/tmp/keenotes-iap-release-20260914/ios-simulator/storekit-ide-warmup/unfinished-readonly-analysis.md`。
- 1.9.0（2）经 Xcode 官方导出上传后，ASC 回读 VALID / IN_BETA_TESTING、既有内部组包含该 build、测试说明一致。新构建 ID `6a8ce140-5a8a-42bd-82c1-0763acc205c9`。这只完成测试分发，不代表设备安装或真实交易已经通过。
- 增加默认关闭的 test-only 观测开关，跳过 SKTestSession 控制器创建和清理，保留原 unfinished 断言。18:05 对照返回已完成的旧交易 0，JWS 六个白名单字段齐全；不能把系统 purchase response 的解码错误等同 JWS 缺字段。
- 18:14 仅临时改变本地 productID 后再次单方法实跑：系统新购成功，交易 1 / original 0 / 新 productID / 当次 purchaseDate 在购买返回与 raw current 中一致；但 finish 之前 unfinished 仍为空，1 FAIL / 0 skipped。这是同组/同链的新事务，不是隔离新组。测试 manager 的唯一 finish 在原断言之后。原隔离模式 Code=3 与此次现象均保持未关闭，未修改生产逻辑或降低断言；两次观测不计入通过数量。

`keenotes-iap` 后台跟进已暂停：自动审批拒绝扩大定时任务范围，当前任务继续执行已授权工作。整体验收保持未完成，最新事实以部署记录为准；上方32项测试和旧manifest为历史候选证据。
