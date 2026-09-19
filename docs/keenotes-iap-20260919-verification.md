# KeeNotes IAP 本轮验收（2026-09-19）

截至 1.9.0（3），业务/UI 回归的 35 个不同用例均已取得通过结果；独立的系统 StoreKit 本地新购观察用例仍为既有 1/1 通过记录。原 SKTestSession 自动化环境故障 Code=3 保留。真实 Apple Sandbox / TestFlight 购买未实测；按用户明确决定，本轮以模拟器验收后推进正式审核，不把本地通过写成 Apple 服务器链路通过。

## 1.9.0（3）界面与双语补验

- 同一 iPhone 17 / iOS 26.5，Xcode 27。主回归 `review-build3/regression.xcresult`：33 PASS / 2 FAIL；两项失败均为新语言测试尾部的错误提示定位。Save 已触发，正确错误文案已生成，但键盘遮挡后测试连续整屏滚动越过状态行。
- 只修正两语言用例的交互：Return 结束输入、等待键盘消失、确认草稿不变，再 Save；完整错误全文与可见性断言保留。补跑 `review-build3/language-recheck.xcresult`：2/2 PASS。没有删除失败证据，也没有为通过测试改变生产保存逻辑。
- 英文系统＋中国地区、简体中文系统＋美国地区均验证入口、商品、恢复购买状态、历史标签、当前标记、二次确认和保存错误。订阅入口突出、历史入口弱化；系统语言决定界面文案。
- 交互稿主线 1–7 使用首轮通过的英文捕获用例；迟到分支 8 使用同轮中文用例；双语附录 8 图取补跑通过记录。逐图用例、设备、时间、来源结果包及 SHA-256 见 [screenshots.json](iap-interaction-demo/evidence/screenshots.json)，两轮原始摘要（包括首轮失败）见 [screenshot-run-summary.json](iap-interaction-demo/evidence/screenshot-run-summary.json)。
- 所有结果包及失败录像/层级保留在 Git ignored 的 `test-results/iap-20260919/review-build3/`；以下早期 27 项结果和系统 StoreKit 记录保留其原时点，不能与这轮 35 项简单相加。


## 当前部署与审核推进状态

- 根任务已验证 Worker 隐私页版本 `edabe337-6ead-4913-9bb9-24a4fa7d703d`：39 项测试及 10 项线上 smoke 通过；隐私政策地址为 https://kns.afoo.me/privacy。
- 1.9.0（3）已在北京时间 2026-09-19 19:40:08 正式提交 App Review；ASC 回执与版本回读均为 WAITING_FOR_REVIEW。审核含 app、年订阅和订阅组三项，发布方式 MANUAL。详见 [提交记录](keenotes-iap-asc-submission-20260919.md)。

## 较早验收阶段的代码与环境

- 保留原工作区全部既有成果，不提交 Git。本轮新增 DEBUG 延迟交付 fixture 与两个 UI 验收用例；发行配置、正式商品和生产服务逻辑未改。
- 只使用原有 iPhone 17（`6A3B455A-6971-4FF1-A64D-B71160B1F2A9`）与 iOS 26.5（23F77）。未下载 runtime、创建设备或克隆，未擦除模拟器数据。
- 开始时 Xcode 26.6（17F113）；过程中本机 Xcode 变为 27.0（27A266a）。本任务没有执行 Xcode 下载/升级；按用户明确授权接受了已安装版本的许可，取消所有额外平台下载并完成必需组件初始化。两版本结果不能作为严格单变量对照。
- 本轮临时 StoreKit 商品、订阅组、scheme 参数已恢复。未执行的普通宿主对照保留为 NOT_EXECUTED，不列为通过。

## 较早验收阶段的测试结果

| 范围 | 本轮结果与证据 |
| --- | --- |
| 凭据存储、配置切换、购买状态机、请求边界与 UI | Xcode 27 上首轮可运行回归为 24 PASS / 3 FAIL；3 项失败因 supervisor 未启动依赖的 localhost WebSocket/TLS fixture。启动仓库已有 fixture 后，同一构建产物仅补跑这 3 项，3/3 PASS。合计 27 个不同用例有通过结果。 |
| 新增 UI 主线 | 删除演示预置历史；购买仅填 endpoint/token 并保留 PIN；Save 前不新增历史、Save 后两条历史；通用/IAP 标签、token 后四位、PIN 隐藏；取消确认不替换；确认自动保存和切换，无需再次 Save。PASS。 |
| 新增迟到交付 UI | 等待交付时关闭订阅页、修改 endpoint，回前台后交付完成；新草稿、原 token/PIN 和当前历史标记不被替换。PASS。 |
| 本地系统 StoreKit | Xcode 26.6 GUI 加载目录后，真实 SystemAppleStore 新购 verified/Xcode 交易 `2`，original ID 也为 `2`，日期属于本次新购。交付前 unfinished 包含该交易；本地交付依赖返回后，Keychain 持久化成功，再 finish 一次，之后 unfinished 为空。原断言未削弱，1/1 PASS。 |
| 原 SKTestSession 自动化 | Xcode 27 + 同一 iOS 26.5 上仍出现保存配置/清理交易 Code=3，商品数量为 0，1 FAIL；尚未到购买断言。此项未关闭，也不把观察模式的通过冒充它通过。 |

第一次 Xcode 27 广泛运行还遇到 Simulator preflight Busy，测试 App 未能启动；重新启动同一设备后恢复。所有失败结果均保留，未通过删除结果或改断言使其消失。

证据位于 Git ignored 的 `test-results/iap-20260919/`：

- `host-isolated-run/GUI.xcresult` 与安全交易字段 `gui-safe-skdiag.json`。
- `xcode27-normal-storekit.xcresult`：原自动化失败。
- `final-rebooted-ui-and-behavior.xcresult`：24 PASS / 3 fixture 未就绪失败。
- `network-fixtures-ready.xcresult`：对应 3 项补跑通过。
- `final-attachments/manifest.json`：截图对应的真实用例、设备与时间。

## 较早验收时点的 Worker 与 ASC 快照

以下版本、计数和未送审结论仅记录前一阶段，不代表上方当前部署与提审进度。

- 当时 Worker 为版本 `6e38bb5f-e802-4f77-9a4d-6f070d617054`，100% 流量；29 项发布清单 hash 及额外 5 项源码无变化。未再次部署、迁移或修改用户数据。
- 只读计数：Production / Sandbox 身份与交易均为 0，各仅有一条 TEST 通知，rows_written=0。TEST 通知不等于购买/续订/恢复通过。
- ASC 1.9.0（2）为 VALID / IN_BETA_TESTING，年订阅商品 READY_TO_SUBMIT；正式计划美国 USD 8/年。用户已明确 TestFlight 从未实测，不能将“可供测试”写成“测试通过”。
- 新回执保存在 `dist/iap-acceptance-20260919/{worker,asc}/`；未公开提交 App Store 审核。

## 交互演示稿

- [可点击演示](iap-interaction-demo/index.html)：8 张主流程实际截图，箭头跳转、取消/迟到分支、图片放大；另附 8 张跨地区双语截图，以及独立系统 StoreKit 真实本地测试弹窗和结果卡。
- [截图连线总览](iap-interaction-demo/overview.png) / [SVG 原图](iap-interaction-demo/overview.svg)。总览以 1.9.0（3）新截图重新渲染并目视检查，HTML 完成静态脚本/结构/资源检查，浏览器点击实测受本地文件访问策略限制未执行。
- 主线是客户端本地 fixture，截图显示的 `$1.00` 为测试价；不是正式价格、真实 Sandbox 购买或远程连接成功。安全输入框在系统截图中隐藏，保留值由测试断言验证。
- `screenshots/10` 原拟捕捉成功提示，实际截图时测试已退出至主屏，未纳入演示或作为成功证据；交易结论以 xcresult/安全字段日志为准。

购买、恢复、续订、退款与 Worker 授权的真实 Apple 链路仍缺实测证据；按用户本轮决定，此边界不再阻塞提审。本地 Xcode 签名没有作为生产购买凭证发送给线上 Worker。
