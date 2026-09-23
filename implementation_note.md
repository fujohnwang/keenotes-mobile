## JavaFX Keep it 卡死修复记录

- 根因定位在 FX 线程的 optimistic note resolve 阶段：`noteItems.set()` 触发 `NoteListCell.updateItem()`，进而走 `NoteCardView.update()` 的 `TextArea.setText()` 和同步 layout 刷新，长期驻留后容易进入布局反馈循环。
- 修复取舍：确认远端 note 时只走 lightweight metadata update，更新 id/date/channel，不重新 setText；内容本身不变，真实数据会在后续 reload 中自然对齐。
- 同时把 `NoteCardView` 的 layout refresh 合并到下一次 FX pulse，并让边框 `AnimationTimer` 跑满后停止，降低长期驻留的 FX 线程压力。

## NoteCard 复制区域重构记录

- `NoteCardView` 的 click-to-copy 只保留在正文 `TextArea` 区域，header 行不再触发 copy，方便后续接入更多操作元素。
- Copied toast 挂在 `NoteCardView` 根 `StackPane` 上，定位到整张卡片中心；整卡 hover 保留，但 cursor 只在正文区域显示 hand。

## NoteCard 分享海报/视频记录

- `NoteCardView` header 右侧新增 share 入口，打开组件级分享 Dialog；所有复用 `NoteCardView` 的列表都会继承该入口。
- 桌面端海报复用 iOS 的梅兰竹菊水墨素材和 BGM 资源，渲染逻辑用 Java2D 实现，保持 9:16 纸张、动态字号和 footer 信息。
- 视频导出先依赖本机 `ffmpeg`，不引入内置编码器；启动 Dialog 时自动检测，找不到就禁用“保存视频”并显示简短提示。
- 分享 Dialog 使用隐藏的 `CANCEL_CLOSE` ButtonType 保留系统关闭行为；可见的“关闭”按钮只是自定义 toolbar 入口。
- 默认保存名为 `keenotes-{note.id}-{yyyy-MM-dd}-{HHmmss}`，其中时间戳取打开保存面板时的本地时间。
- 图片海报保存成功后会同时复制同一张图片到系统剪切板；视频保存不写剪切板。
- 分享 Dialog 新增“复制海报”入口，只把当前预览海报写入系统剪切板，不落盘，保留“保存海报”原有的保存后自动复制行为。
- Java2D 海报文字改用系统 logical `SansSerif` 以启用 Unicode/emoji font fallback；正文与 footer 文字分别先绘制到透明 ARGB layer 再合成，footer 通过整层 alpha 保留原有淡色效果，规避 macOS JDK 在不透明 buffer 或半透明文字颜色上丢失彩色 emoji。代价是各系统中文字形可能略有差异。
- 分享 Dialog 中的海报/视频 FileChooser 改以 Dialog 自身窗口为 owner，原生保存面板返回后在 FX event queue 恢复 Dialog 层级与焦点；不设置全局 always-on-top。
- 海报文本换行只保留原文中的真实空行，不再对每个普通换行额外插入空白行，排版更接近 iOS `Text(noteContent)`。
- 三端海报 footer 的创建时间统一显示为本地时间 `yyyy-MM-dd HH:mm`，精确到分钟以兼顾信息量和横向空间。
- iOS 海报在 `UIImage` 导出出口显式应用透明圆角 alpha clip，并通过 PhotoKit 写入 PNG 原始 resource；两层约束分别保证像素透明与保存时不发生隐式格式转换。

## Android 海报/视频分享记录

- Android 端采用 Media3 Transformer 导出视频，不内置 ffmpeg；为减少 scoped storage 兼容分支，`minSdk` 提到 29。
- 视频只保存到相册；海报支持保存到相册和系统分享，分享临时文件通过 FileProvider 暴露 content uri。
- 预览打开/切换背景时不再显示水墨素材名称；关闭入口使用 X 图标，避免和缩小图标混淆。

## 开源前敏感历史清理记录

- 2026-07-19：用 `git filter-branch` 删除误入历史的 `src/main/resources/fonts/NotoSansSC-Regular.ttf`，并将 `todos/overall_performance_optimization.md` 里的真实运行日志替换为 `[REDACTED]`。
- 本机没有 `git filter-repo`，所以选择 `filter-branch`；清理前完整备份在 `/private/tmp/keenotes-mobile-before-sensitive-history-rewrite.bundle`，这个 bundle 仍包含旧敏感历史，不要上传。

## 修订为新笔记回填记录

- 最小化实现只在客户端 UI 层加 revise 入口：普通 note 卡片/放大视图把原文回填到输入区，旧 note 不变，发送仍走现有新增 note 链路。
- 三端在输入区已有草稿时都要求确认覆盖；pending notes 不显示 revise 入口，避免和待发送队列语义混淆。
- 桌面端覆盖确认从原生 `Alert` 改为无标题栏自定义 Dialog，沿用 KeeNotes 的主题色、圆角和主/次按钮层级；行为仍只是确认是否覆盖输入区草稿。
- 桌面端 Dialog 的图标 badge 采用 top 对齐，而不是拉满文案高度；这样视觉重量更轻，避免左侧形成不必要的栏位。
- Android 端把 Note 输入草稿提升到 `MainActivity` 的非持久化 UI 状态，并通过 `savedInstanceState` 保住同一次界面会话；修复 Fragment 重建导致滑动/点击切 tab 后草稿丢失，以及 revise 回填时误判输入框为空的问题。
- Android 端 revise 的覆盖确认改为在来源页原地弹出；确认后才跳转输入页，取消不产生导航副作用，`NoteFragment` 仍保留兜底确认逻辑。
- JavaFX 端 revise 从搜索/回顾等页面跳回 Take Note 时改走 `DesktopMainView` 的统一 mode 切换入口，避免内容区已切换但 sidebar 高亮仍停留在来源页面。

## request_id 客户端接入记录

- 新发送链路先生成 `PreparedNote`，一次性完成加密、UTC 时间戳和 `request_id`，发送失败或离线时把同一份 encrypted payload 存入 pending，重试不再重新加密。
- 本地旧 pending row 没有 `encryptedContent/requestId` 时继续走旧发送逻辑；这是升级兼容，不影响旧客户端，也不改远端旧数据。
- pending 表新增列都是 nullable；iOS/JavaFX 运行时先查列再 `ALTER TABLE`，Android Room 4->5 migration 也加了 `PRAGMA table_info` guard，避免重复加列失败。
- Android 编译环境差异较大，这轮只做源码改动，不在 Codex 环境跑 Android 编译验证。
- JavaFX 端 HTTP/pending retry 出现网络失败时会主动标记 WebSocket suspect 并重连；`onFailure` 也会下发 disconnected，避免断网后 Sync Channel 长时间假绿。
- iOS/Android 端也补了同类 suspect reconnect：HTTP POST 或 pending retry 网络失败后，主动取消当前 WebSocket、置 disconnected，并走既有重连后 pending 自动重试链路。
- Android `Keep it` 点击入口加 prepare guard：同一条草稿在首次 prepare 完成前不会再次 prepare，prepare 成功后清空输入再释放 guard，避免快速连点生成多个 request_id。
- Android 发布包 `targetSdk` 从 35 提到 36，以满足 Google Play 2026-08-30 后的 API 36 要求；Android 15+ 会系统强制 edge-to-edge，主 Activity 只在 API 35+ 给 NavHost/dock 应用 system bar inset，避免 AndroidX `enableEdgeToEdge()` helper 把 deprecated system bar API 打进 release 包。
- Android 针对 Google Play warning 做第二轮处理：Material 升到 1.14.0 并移除 theme system bar color；AGP 升到 9.1.1、GitHub Actions/Wrapper Gradle 升到 9.3.1，移除旧 Kotlin Android plugin，使用 AGP built-in Kotlin + KSP 2.3.10；Room 升到 2.8.4 以匹配 Kotlin 2/KSP2，需 release CI 后做加密和数据库 smoke test。
- Gradle 9 不再接受旧的动态 `archivesBaseName` project property；Android 输出基础名改用 `base.archivesName`，保留 `keenotes-android-{version}` 命名语义。
- Android native build 和 release workflow 的 `checkout`/`setup-java`/`setup-android`/`setup-gradle` 升到 Node 24 runtime 对应 major，避免 GitHub Actions 的 Node 20 deprecation warning。

## iOS Review 前后台状态保持

- Review 当前会话进入后台后冻结自动 reload，避免前台重连同步或 `noteCount` 更新销毁列表并重置滚动位置。
- 下拉刷新或切换 period 会解除冻结；离开 Review tab 后视图按现有生命周期销毁，下次进入仍正常加载最新数据。
- Review 右下新增常驻 scroll-to-top FAB，使用首条 note 的稳定 id 定位；点击只滚动当前内存列表，不触发 reload，放大 note 时隐藏。视觉采用 36pt material 圆形和非激活 tab 同款灰色 regular 图标，外层保留 48pt 点击区域，避免常驻辅助操作抢占主视觉。

## iOS 构建 warning 清理

- On This Day 查询删除未使用的 `monthDay`；进入 GRDB 的 `@Sendable` read closure 前，将动态参数冻结为不可变的 `StatementArguments`，查询语义不变并兼容 Swift 6 concurrency 检查。

## Android Review 前后台状态保持

- Review 可见期间监听宿主 Activity 的 `ON_STOP`，进入后台后冻结 sync complete 与 note count 驱动的自动列表更新；切换 period 或重新进入 Review 会按原流程解除冻结并加载。
- 只覆盖 Activity/进程仍存活的 warm app 切换；系统杀进程后的恢复继续走现有初始化流程。
- Review 右下新增与 iOS 一致的常驻 scroll-to-top：36dp 弱化圆形、48dp 点击区和非激活 tab 灰色箭头；点击只平滑滚动当前列表，不 reload，空列表或放大 note 时隐藏。

## JavaFX 25 桌面运行时升级

- 桌面编译/打包基线升级为 JDK 25 + JavaFX 25.0.2，GitHub Actions 统一使用 `setup-java@v6`；Android 仍保持 JDK 17。
- `dependency-reduced-pom.xml` 是旧 shade 构建遗留且不参与当前 Maven 构建，本轮不手工同步该生成物。

## JavaFX 长驻稳定性第一批

- note 列表刷新改为两阶段提交：查询和分页参数先独立准备，成功后用一次 `setAll` 原子替换；加载/失败期间保留旧列表和 optimistic note。
- 主页面与 Settings 子页面先同步提交可见状态，fade-in 只做 88%→100% 的装饰，不再承载页面切换或 focus 时机。
- 新增全局 uncaught handler、前台 FX queue/pulse watchdog、App Nap gap 抑制、窗口生命周期日志和限频线程快照；watchdog 失焦即停，不制造后台常驻 pulse。
- `AnimationTimer` 在完成、异常和脱离 Scene 时严格 stop；import 状态的 FX Thread `sleep` 改为可取消的 `PauseTransition`。

## JavaFX 长驻稳定性第二批

- WebSocket heartbeat 改用 OkHttp protocol ping/pong（30s），不再把“长时间没有业务消息”误判为僵尸连接，也移除了独立 heartbeat scheduler。
- 连接状态由 `connectionGeneration + WebSocket identity` 双重校验；旧 socket 的迟到 open/message/close/failure 无权清理新连接，重连始终只有一个可取消的 `ScheduledFuture`；退避计数收到首条有效服务端消息后才清零，避免 open/close storm 永远停在首档重试。
- `disconnect`/shutdown 会先失效当前 generation，再关闭 socket；listener 回调增加异常隔离，单个 UI listener 失败不会打断重连状态机。
- 网络恢复后的 pending-note 查询/重试改投递到独立 scheduler，避免在 OkHttp WebSocket 回调线程里同步访问数据库；scheduler 的 start/shutdown 做了互斥和 rejection 兜底，周期任务也隔离异常，避免单次 DB 错误永久取消后续重试。
- 删除 token 前缀、URL、WebSocket accept、原始 payload、note 明文和 pending note 摘要日志；formatter 对 Bearer、secret key/value、URL query、JSON content 再做兜底脱敏。旧日志文件不会被追溯改写。
- 为兼容现有自托管 endpoint，本轮没有改变原有 TLS certificate/hostname 验证策略；应另开安全迁移项处理，避免与连接状态改造混在一起。

## JavaFX 侧边栏鼠标跟随

- 按用户要求恢复最初的底部横排：Star、Ghost、Cyclops、Cactus、Crowned 五个小角色固定顺序、紧凑错落排列，路径和眼睛坐标使用原生 JavaFX 节点。不再随机位置、大小或顺序；复用原侧边栏 spacer，空间不足时整组缩小或隐藏。
- Preferences 新增 `Show Sidebar Characters`，默认开启，使用 `show.sidebar.characters` 保存选择并通过 BooleanProperty 即时控制可见性；关闭后保留弹性 spacer、停止跟随与眨眼，重新开启时按现有窗口状态恢复，侧边栏 dispose 时解除设置绑定。
- Scene 内移动/拖动鼠标驱动瞳孔平滑跟随，移出窗口回正；运动收敛即停止 `AnimationTimer`，隐藏、失焦、最小化、脱离 Scene 时停止并回正。切换 Scene/Window 或 dispose 时移除旧监听，dispose 可重复调用，并在侧边栏其他清理操作之前执行。
- 保留参考配色；瞳孔位移限制在眼白内，Star 高光随瞳孔一起移动。无新增运行时依赖。
- 鼠标移动或拖动时保持睁眼跟随，并重置各角色的眨眼倒计时；静止后各自随机等待 3–7 秒眨眼，持续静止则继续随机眨眼，两眼同步、单次约 205ms。复用 `PauseTransition` 和短 `Timeline` 在 FX 线程执行；隐藏、失焦、最小化、脱离 Scene 或 dispose 时取消等待和眨眼并恢复睁眼，重新可见且获焦后重新计时；眨眼缩放与瞳孔方向坐标分离。
- JDK 25 离线 Maven 编译通过；按用户要求，最终鼠标交互效果留给手工验证。

## iOS IAP 与凭据历史计划（2026-09-14）

- 本轮仅新增实施计划书，未修改功能代码。IAP 采用独立年订阅/token，通用输入与 Save Settings 保持主入口；历史显示 endpoint、token 尾 4 位、通用/IAP tag，二次确认后启用。
- 计划先实现 Keychain 历史保留，再接 IAP；凭据历史不备份笔记。tuple 变化且有 pending 时暂缓切换，避免跨账户发送；价格、Product ID 与 ASC 选项待配置。
- UI 已确认采用方案 2：设置页右上角订阅入口打开独立 sheet，Server Configuration 标题右侧提供历史 sheet 入口；保留原设置结构和草稿，减少现有页面改动。已同步流程、模块范围与验收项，本轮仍仅更新文档。

## IAP 分任务实施与监督（2026-09-14）

- 按用户授权拆为 iOS、Workers 两个独立 worktree 任务；本任务负责固定接口契约、两轮独立审查、源码快照复跑与 UI 验收，不提交 Git。进度和失败证据统一记录在 docs/keenotes-ios-iap-acceptance.md。
- Apple 停用凭据的恢复区分 automatic / user_initiated，后台通知和补交付不能重新启用用户停用的 token。IAP 网络单独采用系统 TLS，保留原自托管连接兼容性。
- 本地模拟交付、真实 Keychain/SQLite/workerd、系统 StoreKitTest 和真实 Apple Sandbox 分别验收；最终独立 iOS 29 项、Workers 32 项通过，已按 SHA256 核对集成回原仓库。商品/价格/Apple 密钥未配置以及当前 StoreKitTest 失败不能算通过，真实端到端验收仍未完成。
- 用户最新约束：停止 Simulator 测试，不下载运行时；后续设备验证仅允许联机 ip17。清理本轮专用设备/可重建缓存，保留日志、截图与结果包，后台跟进不重复已通过测试。

## iOS 凭据历史与 IAP 实施（2026-09-14）

- 当前配置、历史和切换 journal 同项写入 Keychain，SQLite transaction marker 支持退出恢复；切换等待在途操作，有 pending 时禁止 tuple 变化，避免跨账户发送。
- HTTP 成功而 pending 清理失败时保留原 request_id，不恢复为可重复发送的新草稿；旧账户缓存、迟到查询和回调均按配置版本隔离。
- IAP 凭据独立持久化后才 finish，后台交付不填草稿；PIN 不发往交付服务。IAP 使用独立系统 TLS，通用同步保留现有兼容策略；生产/沙盒地址和正式商品均待配置。
- WebSocket 批次解码/Argon2 由独立 actor 串行执行。工程局部修改保留 1.8.2、原签名团队和 iOS 15 target；详见 keenotes-ios/IAP_VALIDATION.md，未提交 Git。

- 用户暂定正式年费 US$8/年（USD，2026-09-14）。已同步计划与验收待办；尚未写入 ASC，不硬编码客户端显示价格，不改本地 fixture。剩余为商品/服务端配置、ip17 真实 Apple 联调及发布。

- 用户随后授权生产 Workers 部署与 ASC/TestFlight 测试，并改为同一生产服务处理真实 Sandbox 测试身份；按固定路径选择环境，保持 token/笔记隔离，不新建测试资源。只沿用 Business 设定，不复用其他项目密钥。SKU及USD8年费已在ASC创建/回读；KeeNotes专用IAPkey已生成，私钥只放服务端。最新状态见 docs/keenotes-iap-release.md。

## iOS 1.9.0 发布产物准备（2026-09-14）

- 仅同步 pbxproj/project.yml 的正式 SKU、同域双 provision 路径、既有隐私/Apple EULA 链接，版本 1.9.0 (1)；价格仍取 StoreKit，不硬编码。
- 已生成同一份 Release arm64 archive，开发签名和五项配置已核验；Xcode-managed profile 需要 Automatic 归档。未安装设备、未使用 Simulator、未提交 Git。
- 签名导出问题已解决：查重后按授权创建 KeeNotes 专用手工 App Store profile，绑定既有 Distribution 证书；从同 archive 导出 1.9.0 (1) IPA，签名、entitlements 和五项配置核验通过。未创建/撤销证书、未上传；证据在 /tmp/keenotes-iap-release-20260914/ios-build/release-report.md。

- IAP 密钥补救：Chrome 下载验证后重建专用 key 7L6NS8ZNPA，文件保存在本项目 .secrets（700/600、Git ignored、格式校验通过）；旧 8RWVNVAU2V 撤销等待明确授权。不要使用或寻找其他项目私钥。
- 单 Worker 双 Apple 环境最终独立测试 38/38 PASS，慢 Production 请求不再阻断 Sandbox。ASC 已配置 USD 8/年及175地区价格/可售范围；真实 Sandbox 交易验收仍未完成。

- 部署备份改用Cloudflare已有TimeTravel恢复点：全量敏感数据本地导出被自动审批拒绝并取消；006仅新增schema，存量三表行数未变。无整库restore。1.9.0(1)已上传VALID并加入内部组，沿用1.8.2的加密声明（原加密源码无变更）。

- 用户明确确认先部署再做Sandbox后，Worker已发布版本6e38bb5f-e802-4f77-9a4d-6f070d617054；真实Apple通知与ip17交易仍在验收，不以部署成功代替端到端通过。

- 用户最新改为只用一个 iPhone 模拟器；停止 ip17 与邮件邀请操作，复用已有 iOS 26.5 / iPhone 17（6A3B455A-6971-4FF1-A64D-B71160B1F2A9），不下载 runtime、不创建 iPad 或并行测试克隆。本地 StoreKit 测试与真实 Apple Sandbox 交易分别记录；生产 Worker 和双环境真实 TEST 回调已验收通过。

- Simulator 补验：复用唯一 iPhone 17（iOS 26.5），不下载 runtime；LocalStoreKit scheme 与 project.yml 对齐为仅 LocalStoreKitTests、关闭 parallel。该配置漂移不等同于 Code 3 根因，本地 Xcode 交易不代表 Apple Sandbox。
- 单台Simulator补验最终3+1项关键UI通过；新增Save/history/后台草稿用例以持久结果断言。现有configurationRevision重建设置页会清掉Save成功提示，已报复审未改生产代码；系统StoreKitTest在CLI/IDE均Code3，真实Sandbox仍待验。证据在 /tmp/keenotes-iap-release-20260914/ios-simulator/。
- 后续P2已按复审修复：设置页保持identity，Note/Review仍按账户revision重建；history成功回调显示反馈，不添加可能覆盖新草稿的revision监听。4项UI+2项相关行为回归全部通过；系统StoreKit Code3仍独立待解。
- 隐私页已通过 Chrome 确认可达；现文与服务端笔记存储、Apple 交易处理及“停用而非删除”行为不一致。代码核对/局部草案保存在 docs/keenotes-iap-privacy-review.md，未修改线上政策或替用户设定保留期。

- 1.9.0(2) 已完成 Release archive/App Store IPA 导出；沿用既有签名，arm64、五项IAP配置、无测试资源及最终99项源文件hash核验通过。P2相关6项测试通过；上传交根监督任务，真实Sandbox验收仍独立待完成。报告 /tmp/keenotes-iap-release-20260914/build-2/release-report.md。

- 审核截图使用ASC synced正式USD8/P1Y目录与SystemAppleStore，基于旧Debug1.9.0(1)且SubscriptionView与最终Release一致；未买正式SKU、未Save，不算真实Sandbox证据。手写临时scheme绝对路径引起Xcode退出，改由GUI生成相对引用后完成；已清理临时工程配置，99项生产hash及原scheme/fixture核验通过。证据 /tmp/keenotes-iap-release-20260914/ios-simulator/review-screenshot/report.md。
- 最新1.9.0(2)已进入既有内部TestFlight组，VALID / IN_BETA_TESTING及测试说明回读通过。直接上传超时后使用asc调用Xcode从同archive重新导出上传；仅该进程PATH优先系统rsync。单一现有iPhone17、无下载/新设备；系统StoreKit unfinished失败及真实Sandbox交易仍未关闭，未提交Git或公开审核。

- 本地StoreKit诊断仅改测试文件：KEENOTES_STOREKIT_DIAGNOSTICS=1跳过控制器创建/清理，保留原unfinished断言，记录安全ID字段及测试manager的finish次数；观测模式不等同原隔离测试通过。单方法build-for-testing成功，未运行测试，99项生产源及已发布1.9.0(2)IPA保持不变；执行交根任务。

- StoreKit 18:05 观测返回 17:10 已完成旧订阅：JWS 六项白名单齐全，unfinished 为空；不能替代首次新购的隔离验收。新对照仅在 diagnostics 开关下接收 local.test. SKU，并临时只改 fixture 的 productID；保留原 contains 断言及其它目录字节，生产/正式 SKU/Release IPA 未变。根任务 GUI 执行后需恢复 fixture 与临时 scheme 环境。

- 2026-09-19 JavaFX 更新提示排查：以 v1.8.6 原始更新/侧栏代码和独立临时配置验证，接口返回 1.8.7、回调与普通页面显示正常。600 高度展开 Settings/Review 会使提示越出窗口，加入 characters 前也同样复现；更新仅启动时检查一次且失败不重试。尚未确认用户现场触发条件，未改生产代码。

- 2026-09-19 更新提示修复：提示保留独立完整高度，doodle 只使用剩余空间；高度不足时仅导航区域滚动，避免提示越界/重叠。更新改用 JavaFX Service + PauseTransition，启动延迟 3 秒，失败间隔 30 秒最多重试 3 次；成功停止，dev 跳过，退出取消 timer/Task/HTTP 并释放客户端资源。
- 编译及 SidebarUpdateTest 的 6 项检查通过（临时配置、模拟网络）；覆盖深浅主题、人物/概览开关、全部导航模式、570–1000 高度，以及重试上限与退出取消。原生 FX 检查需显式启用 -Dkeenotes.fx.tests=true；视觉体验由用户手工验收，未提交 Git。

- Issue #139：仅在 SidebarCompanionsView 追加敲键随机轻跳，保留鼠标跟随、眨眼及显示开关；90ms 限频，单角色动画不叠加，忽略修饰键/快捷键组合且不消费事件。按剩余高度限制幅度并裁剪，避免越出 doodle 区域；隐藏/失焦/移出 Scene/销毁时停止复位，移除键盘及位移监听。
- 编译及隔离 FX 检查通过：输入事件传递、限频、跳跃边界/落地、隐藏恢复、Scene 卸载重挂、重复 dispose。视觉细节由用户手工验收，未提交 Git。

- 中文输入法 bounce：OpenJFX 25 macOS 组词期间会抑制部分 KEY_PRESSED，KEY_RELEASED 仍转发；追加松开事件兜底，以 KeyCode 集合去重，不读取输入法状态/文字、不引入 native hook。正常按下仍即时触发，被拦截时在松开触发；限频及原有动画保持。生命周期重置清空按键集合，Scene 解绑移除两类监听。编译及仅松开事件/普通按键去重/快捷键/生命周期重放通过；未冒充真实中文输入法手工验收，未提交 Git。

- 2026-09-19：已恢复9/14遗留fixture/scheme，旧/tmp及默认GUI DerivedData证据已丢失，历史结果仅可引用对话记录。经授权准备单轮宿主隔离：沿用blank fixture、真实SystemAppleStore测试、新独立本地商品/订阅组，原unfinished断言不变并检查新交易身份/日期；仅build-for-testing成功，GUI验收交根任务。证据保存Git ignored的test-results/iap-20260919，完成后恢复临时配置；未动生产源或Worker。

- 2026-09-19 GUI隔离宿主单项1/1 PASS：新独立交易2在finish前可见unfinished，持久化后finish一次并清空；证据已留项目目录。普通宿主配对仅换新本地商品/独立组并移除blank参数，测试代码不变，构建成功交根任务；本轮生产hash未变。构建前diffcheck通过，构建后重复检查受Xcode license状态阻断，未代为接受协议。

- 2026-09-19后续：根任务发现工具链切换至Xcode27，取消尚未执行的普通宿主配对，不能与26.6隔离PASS当作严格对照。已恢复原fixture/scheme字节，诊断/新交易检查默认关闭，PBX/project.yml/正式scheme无自动改写；diffcheck已恢复通过，构建证据保留。未追加构建或测试。

- 2026-09-19 IAP 补验：只复用原 iPhone17/iOS26.5，新增 DEBUG 受控迟到交付与截图 UI 用例；27 项业务/UI 已取得通过结果（3 项先因本地 fixture 未启动失败，启动后仅补跑这3项通过）。系统 StoreKit 首次新购观察 1/1 通过，原 SKTestSession Code3 仍保留；真实 Sandbox/TestFlight 从未实测。临时 scheme/商品已恢复，Worker/Release 配置未变，未提交 Git。
- 交互稿位于 docs/iap-interaction-demo（实际截图＋连线/HTML）；系统截图隐藏安全输入值，UI 断言验证其保留。主线 fixture 价不代表正式 USD8/年。Xcode 本机工作期间26.6→27.0，按用户明确同意处理许可、取消新平台下载；不将不同工具版本视为单变量对照。完整结果见 docs/keenotes-iap-20260919-verification.md。

- 2026-09-19 IAP UI 本地化：订阅入口改为 callout semibold/品牌色并放宽英文文字宽度；历史入口为较小灰色文字、保留点击区域。新增76条系统标准 en/zh-Hans资源，覆盖IAP/凭据历史/确认/错误/连接反馈，不按国家选择语言；购买与同步流程不变。旧中文UI用例固定zh-Hans并采用稳定identifier，新增英中跨地区验收及英文主线截图用例；本子任务只静态校验，构建和单模拟器验收交根任务。按用户授权仅将PBX/project.yml隐私链接改为https://kns.afoo.me/privacy，未改版本或提交Git。

- 2026-09-19 双语 UI 补验：首轮最后错误提示被键盘遮挡，测试整屏滚动越过状态行；保留失败 xcresult/录像/层级，只让两语言用例在 Save 前按 Return 并等待键盘收起，错误全文断言不变。演示导出支持合并主回归与补跑、仅选每项最新 Passed 记录且归档各轮失败摘要；未自行运行 build/sim。

- 2026-09-19 送审准备：用户明确选择只用现有模拟器后提交；1.9.0(3) 的35项业务/UI均取得PASS（33+2语言定位补跑），原SKTestSession环境Code3与真实Sandbox未测边界保留。Worker隐私页已部署并更新App/ASC链接；发布用IPA含中英资源、无测试fixture或.storekit。导出指定既有profile对应证书SHA1，并仅在导出进程优先系统rsync，以避开同名证书/Homebrew冲突；未新增证书或修改系统PATH。

- 2026-09-19 19:40（上海）已将1.9.0(3)、年订阅和订阅组三项同审；submission 4479ec8e-92b0-453b-97ca-035cc81a7979，ASC/版本均WAITING_FOR_REVIEW，MANUAL发布。正式美国价格$8/年；未宣称Apple审核通过或真实Sandbox交易验收通过。回执/IPA保存在dist/iap-asc-review-20260919，交互稿已更新实际英文主线与中英附录。未提交Git。

- 2026-09-19 UI微调：Purchase/购买为品牌色44pt按钮，History为浅底图标次级按钮；历史行添加类型图标、semibold地址、monospaced尾4位、橙/蓝tag、右侧当前勾选及selected辅助标记。视觉截断不影响完整endpoint的辅助访问/确认框；保存/切换服务源hash不变。4项既有UI＋深色英文补跑1次通过，原模拟器已恢复light/large。独立预览docs/iap-ui-polish-20260919，旧build3送审证据保留；本轮尚未上传或撤回当前审核，未提交Git。

- 2026-09-19 UI 后续调整：Purchase／购买改用系统默认按钮，与 History 并排放在 Server Configuration 标题下；两行 section header 避免英文拥挤。顶部恢复纯标题，共享 TopHeaderView 移除已无调用的文字/突出样式扩展；Save Settings 保持主要视觉层级。只改入口位置与外观，保留 action/identifier、双语、历史确认行为；未变更 ASC 审核或提交 Git。
- 配置区入口移动后的构建及既有英文UI回归1/1通过；中英文浅色/英文深色设置实图已更新，恢复light。实施计划和UI预览同步新位置；未将旧入口截图冒充新版。
- 最新覆盖上述分行方案：配置标题与 Purchase、History 同一行；英文标题通过本地化资源固定换行，完整标题保留辅助访问标签。两个入口统一automatic样式及44pt点击区域；构建通过，更新中英文/深色实图，未重复业务测试。
- 按最终视觉层级 Save > Purchase > History 收敛：small bordered 圆角按钮＋footnote，移除撑高标签的最小高度；Purchase 按用户最终要求使用50%透明度主题蓝底、primary文字，History 浅灰；沿用系统按钮，仅Purchase为borderedProminent。同一行标题布局/行为不变，构建与中英/深浅实图检查通过；未新增测试、改 ASC 或提交 Git。
- 最终仅调位置：History 紧跟配置标题，Spacer 后 Purchase 靠右；已确认的字号、形状、颜色和 action 均保持。原单模拟器构建/截图核验，未改 ASC 或提交 Git。
- History 入口改为 clock.arrow.circlepath 图标，保留本地化 VoiceOver 名称与 identifier；配置标题改回普通本地化文本，删除专用强制换行资源。按钮位置/颜色/动作不变；构建通过，按用户要求不截图、不新增测试。
- History 图标改为 plain，无底色/边框；透明 padding 与 contentShape 保留点击余量，辅助名称/action 不变。构建运行通过，未截图。
- Purchase 按用户要求移除主题色 opacity(0.5)，恢复不透明；其余样式/位置/行为不变，未截图。
- Purchase 文字改为白色，匹配已恢复不透明的主题蓝底；其它不变，未截图。

## JavaFX 本地混合搜索（#140）

- 单一搜索框改为 Lucene + Java jieba；只有 provider 已配置且启用才做 hybrid，SQL LIKE 搜索删除。远程同步提交时在现有 SQLite 同事务记录任务，分词、索引、HTTP 全部后台执行；不增加业务 append_seq、不改远程协议。
- 关键词/向量独立 Base + Delta，全量采用固定正文快照并手动触发；取消保留构建检查点，增量继续。向量按 note 请求以隔离失败，同 profile/input 复用；代价是完整模型重算时 HTTP 请求数较多、重建快照额外占磁盘。
- 模型迁移必须覆盖旧视图才发布；候选 Delta 未赶上时保留旧模型并允许重试。已配置 profile 的凭据沿用 CryptoHelper 加密保存在 settings，支持迁移中重启；API Key 不进入搜索元数据。
- 搜索 codec 保留 Lucene 标准磁盘格式，将默认 1024 维上限扩到 16384；3072 维写入、重建及重启查询有测试覆盖。
- 过期查询取消独立 HTTP Call；清空缓存轮换 epoch，拒绝旧结果并延迟回收正在退出的旧构建目录。缺失 Base 可手动重建；增量失败自动重试 3 次后需手动重试。
- 十万条合成短笔记 + 768 维模拟向量通过容量冒烟：关键词构建约 17 秒、向量构建约 105 秒、索引约 327 MiB；不含真实模型推理/网络时间。详细约束及验证命令见 `docs/desktop-local-search.md`。

- 2026-09-20 按用户选项2：先手动发布已批准 IAP 1.9.0(3)，回读 READY_FOR_DISTRIBUTION；随后将最终 UI 打包为1.9.1(4)，09:52提交，版本及submission cbbd04aa-c8bf-46a2-9be7-593eec4034f6均WAITING_FOR_REVIEW，保留MANUAL发布。独立审查＋原单模拟器3/3回归通过；未截图，临时测试调整已恢复，正式IPA无fixture。ASC stage需传metadata根目录，已修正并复用同一版本；详情docs/keenotes-iap-release-20260920.md，未提交Git。

## AI 设置布局与多模型管理

- AI 设置按 MCP / Local Search 排列，默认打开 MCP；示例右上角一键复制完整 JSON，使用可清理的 PauseTransition 显示成功反馈。Tab 高度按当前内容计算；隐藏的 Settings 子页不再参与布局。
- Local Search 将 None 和已保存模型统一为整卡可点选的 ToggleButton，无 radio 圆点；选中使用边框/底色/文字提示，宽屏最多四列并自动换行。Configure 为卡片上的独立按钮，只打开编辑；选择 None 保留配置和索引并关闭语义搜索，旧的关闭状态迁移为 None。
- 沿用原 settings 文件与 CryptoHelper 保存加密凭据；旧单模型配置自动映射为第一张卡片，保留 profile 和启用状态。新增配置只保存为候选，选择模型继续走原有索引迁移流程；显示名称不改变向量 profile。
- MCP 示例空白是本轮 Tab 布局回归：内部 unmanaged 容器阻断高度失效传播，展开后仍按折叠高度裁剪。订阅内容布局变化以重算当前 Tab 高度，dispose 时解除；补上原生 FX 展开/收起及文本裁剪回归，上轮只验证了折叠状态。
- 关键词和语义索引分别提供重建、取消、重试及状态；失败重试按 family 隔离。取消全量仍等待当前请求退出，避免影响同类型增量任务；关闭语义搜索则取消向量请求。原生 FX 覆盖单选/None、剪切板复制及重建并行/取消隔离。
- 搜索列表恢复笔记时间倒序，同时间按 ID 倒序、缺失时间最后；关键词预览及 hybrid 最终结果统一在后台 hydration 后排序。Lucene/RRF 仍决定最多 100 条候选，不改变索引、不需重建；覆盖相关性与时间冲突、同时间及缺失时间回归。

### Local Search 排版重构：Keyword / Semantic 两个顶层 section（2026-09-20）

- 按用户确认的「大标题 + 分隔线」方案：`Keyword Search Settings` 和 `Semantic Search Settings` 升为顶层 section，`Embedding models` 降为 semantic section 内的子块，两个 index 面板各自归入所属 section。
- 第二轮按反馈：删掉页顶 `Local Search` 标题与副标题；Semantic section 内顺序改为 `Semantic index` 在前、`Embedding models` 在后。对调后原来的提示 "Select a model **above**" 方位词失效，改为 "Select a model, then rebuild…"（去掉方位词避免再次对调时又错）。
- 代价：两个 index 面板不再并排（原先 FlowPane 宽屏两列），因此删掉 `indexWidth` binding 和 `indexes` 容器，面板改为撑满 section 宽度。窄屏下每个面板更高，但溢出断言在 600/800/1200 三档均通过。
- 顶层 section 用 `Region` 画 1px 分隔线而不是 `Separator`：`Separator` 的 line 在 Modena 里是双层 border + insets，覆盖起来比一个 `-fx-background-color` 的 Region 麻烦，颜色也更难跟随主题变量。
- 保留原有 id / style class（`#rebuild-*`、`#keyword-index-panel`、`.search-index-panel`、`.model-card-cell` 等），测试里新增 `#keyword-search-section` / `#semantic-search-section` 的结构断言（层级归属 + keyword 在 semantic 之上）。
- 未做：`+ Add model` 文案保持原样（图里的 "Add" 判断为示意）；不给 section 加折叠；semantic 关闭时 section 不置灰。

### 模型卡片改版（2026-09-20）

- 去掉卡片的 `KW`/`EM` 图标徽章和 `Default`/`Local`/`Custom` 标签，名称放大到 19px；选中态由底部 `Selected`/`Click to select` 文字改为右上角 SVG 对勾（未选中时 `visible=false` + `managed=false`，不占位）。
- 连带清理：`modelCard()` 里的 `local(baseUrl)` 判定随之失去唯一调用点，`local()` 方法和 `java.net.URI` import 一并删除；CSS 里 `.model-icon` / `.model-kind` / `.model-card-status` 三条规则成为死代码，也删掉。
- 代价：原来靠 `state` 标签 `minHeight: 32` 给右下角 `Configure` 覆盖按钮留位，标签删掉后改用同高度的空 `Region` 占位；行高与之前一致，卡片保持等高。
- 名称加了 `setWrapText(true)`：字号变大后长显示名更容易溢出，不加会直接截断。
- 测试补断言：全局可见的对勾数量为 1，且落在 `.selected-model-card` 内。

### 卡片 Configure 改右键菜单（2026-09-20）

- 删掉卡片底部的 `Separator` + 32px 占位 `Region`（就是那块诡异空白），`Configure` 按钮改为卡片右键菜单项；按钮原本只为给覆盖按钮让位而存在，一起删掉后卡片高度由内容决定。
- 连带删掉 `modelCard()` 外面的 `StackPane` 包裹层：它唯一的用途是承载 `Configure` 覆盖按钮，现在没必要了。代价是同一个节点只能有一个 id，`#select-embedding-*` 与 `#embedding-model-*` 合并为后者，测试选择器同步改名。
- `Configure` 菜单项没有绑定 `disableProperty(saving)`：卡片本身已绑定，而 JavaFX 里 disabled 节点收不到鼠标事件，右键菜单自然不会弹出。省掉了在 `clearCards()` 里遍历 `MenuItem` 解绑的生命周期代码。
- 右键菜单沿用全局 `.context-menu` 主题样式（dark/light/main 三份都有），未新增样式；`.model-card-cell` 及其 `.search-secondary` 规则、`clearCards()` 里的 `.button` 解绑循环均已成为死代码，一并删除。

## /doctor 体检与配置调整（2026-09-20）

- 根 `CLAUDE.md` 里的 `@AGEHTS.md` 是拼写错误（该文件不存在），导致项目指令 `AGENTS.md` 从未被加载过；已改为 `@AGENTS.md`。改动未提交，等你 review `git diff` 后自行决定。
- `~/.claude/settings.json`：`permissions.defaultMode` 设为 `auto`；5 个零使用插件置 `false`；14 个零使用技能设 `skillOverrides: off`（`asc-*` 22 个和 hyperframes/media/video 10 个经用户确认后已恢复启用）；移除失效条目 `superpowers@superpowers-marketplace`。原文件备份在 `~/.claude/settings.json.doctor.bak`。
- `~/.claude.json`：本项目 `disabledMcpServers` 增加 `pencil`、`fetch`。注意 `/mcp disable` 是**逐项目**生效，换项目要重复操作。备份在 `~/.claude.json.doctor.bak`。
- 7 个死技能条目（2 个自引用死链 + 5 个内容全为悬空链接的目录）**未能删除**：被 deny 规则 `Bash(rm -rf:*)` / `Bash(rm -f:*)` 拦截，需你手动执行。
- `claude update` **失败**（2.1.270 → 2.1.278）：npm 全局安装的更新路径报错且无详细信息。根因未确定（目录可写、registry 正常），需你选择走 npm 还是 `claude install` 原生安装。

## FxRuntimeMonitor 窗口日志降噪（2026-09-21）

- 只把 `FxRuntimeMonitor` 里 4 处窗口焦点/生命周期日志从 `info` 改成 `fine` **不会生效**：`AppLogger` 把 root logger 和 console/file 两个 handler 都设为 `Level.ALL`（`AppLogger.java:79,85,94`），FINE 照样写进 `~/keenotes.log`。
- 因此在类里加了 static block，显式 `logger.setLevel(INFO)`，只作用于本 logger，不影响其它类。想复现焦点抖动时加 JVM 参数 `-Dkeenotes.debug.fx=true` 即可恢复全部输出（无需改代码重编）。这是本仓库第一处 `Level.FINE` 用法，也没有既有的 debug 开关约定可复用。

### 怎么打开这个调试开关（已实测，别踩坑）

- ❌ **`mvn clean javafx:run -Dkeenotes.debug.fx=true` 无效**。`javafx-maven-plugin` 会 fork 出一个独立 JVM，只传 `options`（pom 里配的 VM 参数列表）+ module-path + classpath；Maven 命令行上的 `-D` 不会被带过去。实测对照：跑起来的 app 进程（PID 12391）命令行里只有 `--module-path/--add-modules/-classpath/主类`，Maven 自己的 `-Dclassworlds.conf=` 等一个都没出现。
- ❌ `-Djavafx.options=...` 也不行：`mvn help:describe -Dplugin=org.openjfx:javafx-maven-plugin:0.0.8 -Ddetail -Dgoal=run` 显示 `options` 是唯一没有 User property 绑定的参数。
- ✅ **可行**：`JAVA_TOOL_OPTIONS="-Dkeenotes.debug.fx=true" mvn clean javafx:run`。两段链路都已实测：forked 的 app JVM 继承了父进程全部环境变量（`ps eww <pid>` 能看到 77 个，PATH/HOME/JAVA_HOME 齐全），而 JVM 启动器会自动读取 `JAVA_TOOL_OPTIONS` 里的 `-D`（`JAVA_TOOL_OPTIONS=-Dx=y java -version` 会打印 `Picked up JAVA_TOOL_OPTIONS`）。
- ✅ 打包后的 fat jar（`spring-boot-maven-plugin` repackage）直接在 `-jar` 前加：`java -Dkeenotes.debug.fx=true -jar target/keenotes-mobile-*.jar`。
- 改 pom 的 `<options><option>-Dkeenotes.debug.fx=true</option></options>` 也行，但等于**永久**打开调试日志，不推荐；要开时用上面的环境变量，别改 pom。
- 打开后会多出 `Level.FINE` 标签的行。注意日志真实文件名是 **`~/keenotes.log.0`**，没有 `~/keenotes.log`：`AppLogger.java:92` 用的是 `new FileHandler(pattern, limit, count, append)`，这个构造器产出的文件是 `pattern.0/1/2`，`.0` 是当前写入的那个（已实测）。老日志在 `~/keenotes.log.1`、`.2`。

### 为什么当前日志文件是 `keenotes.log.0` 而不是 `keenotes.log`

- 这是 JUL 的文档化行为，不是 AppLogger 写错。`FileHandler` javadoc：如果 pattern 里没有 `%g` 而 count > 1，世代号会被加到文件名末尾的点号之后；实现见 `FileHandler.generate()` 里的 `if (count > 1 && !sawg) word.append('.').append(generation)`。写法上无论显式写 `%g`（得到 `keenotes.log0`）还是不写（得到 `keenotes.log.0`），**只要 count > 1 当前文件就必然带序号**。AppLogger 的 `MAX_FILE_COUNT=3` 决定了这一点。
- 设计动机：JUL 把当前文件也当成生成序列的一员（`files[i] = generate(pattern, i, unique)`），滚动就是级联 rename `files[i] → files[i+1]` 然后重开 `files[0]`，所以 `.0` 天然就是"正在写"的槽位、`.count-1` 最老。若当前文件叫原名，`rotate()` 就得多一条特例分支。logback/log4j2 用的是相反习惯（当前 `app.log`、历史 `.1/.2`），所以看着反直觉。
- 想改成 `keenotes.log` 只有 count=1 一条路，但那时 limit 一到 `rotate()` 里的 `open(files[0], false)` 是 append=false，**直接清空文件**（数据丢失）。安全组合只有 count=1 + limit=0，即永不滚动、无上限增长。JUL 不支持"当前用原名 + 历史带序号"（`rotate()`/`files` 均 private，子类也改不了），要那个效果只能换 logback/log4j2 —— 为一个文件名引依赖不划算。结论：维持现状，记住看 `.0` 就行。
- `~/keenotes.log.0.1` 这个幽灵文件的来历：FileHandler 发现目标文件被别的进程占着（`.lck`）时，会把 unique 号追加在**自动生成的世代号之后**（`if (unique > 0 && !sawu)`），所以 `.0.1` = generation 0 + unique 1，意味着**曾有两个 KeeNotes 实例同时运行**，第二个实例的日志落到了这里，没有污染主日志。反过来说：`~/` 下出现 `keenotes.log.0.1`、`.0.2` 就说明同机跑过多个实例。
- 实践含义：并行跑第二个实例做实验不会覆盖主日志，但是否会因此抢共享资源（本地导入端口 / MCP 端口）未验证，别当成安全的并行手段。
- `logger.info("Window close requested")`、`FX watchdog resumed after scheduler gap=`、`FX runtime monitor stopped` 保持 INFO：都是每次运行至多一条，且排查时有用。真正被静默的只有 `Window focused/iconified/showing` 和 `Window lifecycle event=` 这类高频行。
- `logWindowState()` 内部的 `captureWindowState()` 仍然无条件执行（它的返回值 `lastWindowState` 会拼进卡死告警里），降级只影响打印，不影响行为。

## Local Search 状态拆成全量/增量两层（2026-09-21）

- `LocalSearchEngine.Status` 从 8 个平铺字段改成 `Status(Layer keywords, Layer vectors)` + `Layer(base, delta, pending, failed, built)`。唯一消费者是 `SearchSettingsPane.showStatus`，12 个位置参数（一半是 int）太容易接错位。原 `indexed`（Base/Delta 合并后的 `visibleCount`）从 Status 里删掉了，因为新 UI 不显示它；`IndexFamily.count()` 保留，`search()` 内部还在用 `count() > 0` 判断有没有向量索引。
- `baseCount()/deltaCount()` 用**即时 stream 扫描**，没有像 `visibleCount` 那样加缓存字段：`cleanCoveredDelta()` 删 Delta 条目时**不调用 `recount()`**（它不改变 `visibleCount`，净值 0），缓存字段会正好在这个场景下悄悄失准——而"全量重建后丢掉被覆盖的增量"恰恰是这次改动最需要显示正确的场景。status 轮询 1s/次，扫 2 万条 HashMap 是微秒级，不值得为它引入第二份要同步的状态。代码里留了 `ponytail:` 注释。已改成 `Layer` 的测试断言顺带覆盖了 `cleanCoveredDelta` 这条路径。
- 计数语义：Base 只存 eligible 文档（`Builder.add` 跳过 ineligible），Delta 还会存 ineligible 墓碑（用来遮蔽 Base），所以两边都按 eligible 过滤，否则"增量 N notes"会把墓碑也算进去。
- UI 变成两行：`Historical index  <N> notes` / `Incremental index  <N> notes · <N> pending · <N> failed`。全量未构建时第一行是 `Historical index  not built`——原来的 "Historical index not built" 提示没丢，只是挪进了对应的行。没有做空格对齐：`.field-hint` 是比例字体斜体，凑空格只会歪。
- 语义搜索未启用时的边界沿用原语义：`vector` family 拿不到时 Layer 全 0，UI 仍用 `catalog().enabled()` 决定显示 "Semantic search is off"，这段行为没动。

## iOS 正常发送与离线暂存分离（2026-09-23）

- 修复 1.9.0 引入的 outbox 闪现：保留 HTTP 前落盘，用内存中的 request_id 集合排除首次发送中的记录；横幅、列表、自动重试共用待重试列表。无需数据库迁移，进程退出后留存记录会重新进入 outbox；账号切换仍检查全部持久化记录。
- HTTP 不再由 WebSocket 连接状态拦截。仅 `URLError.notConnectedToInternet` 提示离线；超时、服务器错误显示“发送失败，已保存到本机待重试”。沿用请求超时和重连/定时重试机制。
- 数据库发布完整待发快照，使用同一 SQLite connection 的 `totalChangesCount` 拒绝乱序旧快照，避免并发发送后已删除记录重新出现；代价是内存保留待发列表，而非只有计数。
- 先在已有 iPhone 17 Pro / iOS 26.5 模拟器复现 3 项失败，再验证修复后 21 项测试通过（HTTP mock；覆盖离线恢复、并发、持久化恢复、清理失败与配置切换），原独立复现脚本的 3 项也全部转绿。结果：`/private/tmp/keenotes-outbox-validation/regression.xcresult`；未提交 Git。
