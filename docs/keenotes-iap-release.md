# KeeNotes IAP 部署与 TestFlight 记录

## 2026-09-19 状态校正

- 本轮补验与交互演示已记录于 [2026-09-19 验收](keenotes-iap-20260919-verification.md)：27 个业务/UI 用例有通过结果，系统本地新购观察用例通过；原 SKTestSession 自动化与真实 Sandbox 仍未关闭。
- 用户确认：TestFlight 从未实测；此前记录仅表示上传与内部测试分发已完成。本日 ASC 回读 1.9.0（2）仍为 VALID / IN_BETA_TESTING，商品仍为 READY_TO_SUBMIT，不能据此判定购买验收通过。回执保存在 `dist/iap-acceptance-20260919/asc/`。
- Worker 只读回读仍为版本 `6e38bb5f-e802-4f77-9a4d-6f070d617054`、100% 流量。Production / Sandbox 各有一条 TEST 通知，身份与交易均为 0；查询 rows_written=0。未重新部署、修改资源或用户数据。
- 本轮继续使用原有单台 iPhone 17，关闭并行测试，不下载 runtime。客户端 UI fixture、Xcode 本地 StoreKit 与真实 Apple Sandbox 分开记录；用户要求的截图交互演示稿也须标注测试边界。
- 9 月 14 日 `/tmp/keenotes-iap-release-20260914/` 和当时默认 DerivedData 原始证据现已不存在。下方历史结果保留为当时验收记录，不能当作目前仍可打开的证据；本轮新证据改存项目中已忽略的 `test-results/iap-20260919/`。

以下为 9 月 14 日历史记录。

日期：2026-09-14。Worker 生产部署、9/9 冒烟和双环境真实 Apple TEST 通知核验已通过；保存反馈修复后的 1.9.0（2）已为 VALID / IN_BETA_TESTING，内部组与测试说明均回读确认。只使用现有 iPhone 17 模拟器，未下载 runtime 或创建新设备。真实 Apple 购买交易验收及本地系统 StoreKit 失败项仍未关闭。

## 最新授权与范围

- 用户已授权部署 Workers、通过 ASC CLI 上传 App Store Connect 测试；年费暂定 US$8/年。设备测试按下述最新单模拟器约束执行。
- 仅沿用当前 Apple 账号的 Business/收款设定；不读取或复用 FooSnippets/RVXFlare 私钥。为 KeeNotes 创建并配置专用 IAP 服务端密钥。
- 用户要求复用现有生产 Worker 与数据资源，不创建单独 Sandbox Worker/D1/KV。Sandbox 使用真实 Apple 测试交易与独立测试 token/笔记，不修改已有用户数据。
- 本轮仅 TestFlight 测试分发，未要求公开 App Store 发布。禁止 Git 提交；用户最新要求停止手机操作、改用单一 iPhone 模拟器，不新增 iPad。已确认可直接复用 iOS 26.5 runtime 和现有 iPhone 17，无需下载。不重置 ip17 数据或自动 Save 切换现有连接。

## ASC 已完成

| 对象 | 已确认值 |
| --- | --- |
| App / Bundle | 6757829752 / cn.keevol.keenotes |
| 订阅组 | 22383447 / KeeNotes Cloud Sync |
| 年订阅商品 | 6811796283 / cn.keevol.keenotes.remote.yearly / ONE_YEAR |
| 美国价格 | 已设置并回读 USD 8.0 / 年；175 地区完整价格矩阵及与现有 App 一致的可售范围已配置、validator 确认 |
| 订阅组版本 | cfcb7640-37ba-49da-b699-2f1720988cb2 / PREPARE_FOR_SUBMISSION |
| 商品版本 | b851450d-1851-415f-b158-fcd2cfc75706 / PREPARE_FOR_SUBMISSION |
| 本地化 | 商品及订阅组均已创建 en-US / zh-Hans 名称；商品描述已保存 |
| 当前已发布 App | 1.8.2（1） |
| 本次测试构建 | 1.9.0（2），build ID `6a8ce140-5a8a-42bd-82c1-0763acc205c9`，VALID / IN_BETA_TESTING；既有内部组中保留（1）、新增（2） |

具体非敏感 API 回执保留于 `/tmp/keenotes-iap-release-20260914/`。正式年订阅审核截图已上传，delivery=COMPLETE，回读校验尺寸及 MD5 一致；商品现为 READY_TO_SUBMIT（未提交审核）。这不等于已完成真实购买验收。内部测试组 `f81c8dbe-6d46-4d0d-9f89-83d557721580`（KeeNotes IAP Internal）仅邀请用户本人。

## 单 Worker 双环境契约

| Apple 环境 | 交付路径 | 通知路径 |
| --- | --- | --- |
| Production | https://kns.afoo.me/iap/apple/provision | https://kns.afoo.me/iap/apple/notifications |
| Sandbox | https://kns.afoo.me/iap/apple/sandbox/provision | https://kns.afoo.me/iap/apple/sandbox/notifications |

路径由服务端选择 verifier / Server API 环境，body.environment 仅用于一致性校验；不能用客户端字段降级生产验签。D1 身份按 environment/bundle/appTransactionId 隔离，cron 并行推进两环境。新一轮 Spec/Standards 独立审查已通过，唯一 P2（Production 慢请求阻断 Sandbox）已修复并独立复验关闭。根任务在独立快照运行 38/38 测试通过，无 skipped；已集成原仓库，29 个源码/配置/测试文件 hash 匹配最终 manifest。

## 真机与构建准备

- 联机 af’s ip17p，iPhone 17 Pro，iOS 26.6.2，Developer Mode 与配对可用；现装 KeeNotes 1.8.2（1）。已有匹配设备的 development profile 和有效 Apple Distribution/App Store profile。
- 原仓库 1.9.0（1）已完成归档与 App Store 签名导出，5 项 IAP 配置均已核验；IPA 为 `/tmp/keenotes-iap-release-20260914/KeeNotes.ipa`，SHA256 `b7334b510112a8cc365459b5dd7f4ce9b43a993e563a8e75e4ae31f32c6c50c0`。根独立检查 hash、版本、Sandbox URL 和无本地 StoreKit fixture；子任务 codesign strict 通过。
- 复用既有 Distribution 证书，新增窄范围手工 profile `62G965RRN6` / `ad1b2332-f89d-4f70-a88e-335b1c4e0015`（KeeNotes App Store 20260914），解决 Xcode-managed profile 导出限制；没有新增或撤销证书，也没有重新归档。
- 隐私链接为 `https://afoo.me/knotes_privacy_policy.html`；根在 Chrome 正常读取完整页面，先前直连 403 不代表浏览器不可达。当前文案仍称不上传内容、不使用应用内支付处理、不存服务端个人数据，需按实际远程/IAP 数据处理核对并准备修订；未擅自更改线上法律声明。条款使用 `https://www.apple.com/legal/internet-services/itunes/dev/stdeula/`。
- 首轮真机只验证购买/恢复/凭据交付和草稿保留；不自动 Save 切换用户原有服务、不清空笔记。

## 待完成

1. 系统 StoreKit 经 IDE Run 预热可购买，但自动控制 Code=3 和 unfinished 断言仍未关闭。新增两次单方法观测：旧交易 0 的 JWS 字段齐全；临时新 productID 生成了交易 1，仍在应用交付/finish 前查不到 unfinished。后者与旧交易属于同订阅链，不算隔离新组，具体根因未确认。代码及日志未发现宿主提前 finish 路径，不能将 purchase response 解码报错直接判为 JWS 缺字段。单台 iPhone 17 的 P2 修复与相关 UI/行为回归已 6/6 通过，未为失败降低断言或更改生产代码。
2. 真实 Sandbox 购买/恢复/续订/退款及其授权执行仍缺实际交易证据。手机操作已按用户要求停止，不以 Xcode 本地交易或 Apple TEST 通知替代。
3. 现有隐私政策与实际远程存储/IAP 处理存在文案差异，代码核对及局部修订草案见 [隐私政策核对](keenotes-iap-privacy-review.md)。草案未发布，不阻止继续内部测试；正式发布前应明确保留/删除方式并更新披露。

## 已完成的发布核验

- 新 key `7L6NS8ZNPA` 安全保存在项目 `.secrets`（600、Git ignored、格式 PASS）；四项 StoreKit secrets 已按用户明确授权配置到既有 Worker。
- 006 增量迁移完成；既有三表行数不变，FK 错误为 0；保留 Time Travel 恢复点，没有导出全量敏感数据或执行 restore。
- 线上版本 `6e38bb5f-e802-4f77-9a4d-6f070d617054`，原 D1/KV/DO bindings 保持；9/9 smoke PASS。Apple Production/Sandbox TEST 均 SUCCESS，D1 各有 1 条按 UUID/环境精确关联的通知，错误环境记录为 0。
- 两套通知 URL 保存回读，Business 的 Paid Apps/Free Apps Agreement、Bank/Tax 均 Active。
- 新构建 `6a8ce140-5a8a-42bd-82c1-0763acc205c9` 为 VALID / IN_BETA_TESTING；内部组 builds 关系回读包含它及旧构建 `742a3a50-8edc-459c-b6c8-ae20c46d066b`。未新增 tester/邀请，沿用既有加密声明；en-US What to Test 回读与准备文本一致（ASC 会去掉末尾换行）。手机安装操作仍停止。
- 1.9.0（2）的 archive、签名与 99 个应用文件 hash 核验通过；本地导出 IPA 的 SHA256 为 `8599dadb67789ebfdf4059b555484c9fa99bae35f6118fbf46b572cab64f7ad0`。最终通过 `asc xcode export` 调用 Xcode 从同 archive 重新导出并上传，17:46:41 收到 UPLOAD SUCCEEDED；重新签名的上传包字节不与该本地 IPA hash 等同。Xcode 成功退出后 ASC wrapper 仍等待超过 20 分钟；在独立 API 验证处理、内部状态、组和说明全部成功后，仅终止该 wrapper，未取消上传或移除构建。API 回执及独立核对保存在 `/tmp/keenotes-iap-release-20260914/build-2/`。
- 年订阅截图来自 Xcode 从 ASC 同步的正式 SKU / P1Y / USD8.0 目录，由 SystemAppleStore/SubscriptionView 显示，非 UI fixture；未购买正式 SKU。截图 ID `3ed68e26-2c35-4ee8-a462-01c8049db41c`，1206×2622，delivery=COMPLETE、MD5 回读一致，商品 READY_TO_SUBMIT。临时截图 scheme/工程引用均已清理并复核；未提交公开审核。

## 过程记录（以下为按时间保留的历史状态）

自动审批曾拒绝跨项目密钥查找与创建新资源的原派发；该方案已按用户纠正取消，没有执行。

密钥补救记录：首次 IAB 下载 `8RWVNVAU2V` 后未找到落盘文件，用户授权自行重建。先用公开 Apple CA 验证 Chrome 实际下载，再生成并安全保存替换密钥 `7L6NS8ZNPA`。旧 key 撤销被自动审批以缺少明确撤销授权拒绝，已询问用户；目前旧 key 保留，此项不阻断新 key 配置与部署。私钥不写入文档或日志。

2026-09-14 发布进展：两套 Server Notifications URL 已在 Apple App Information 保存回读；Business 页面 Paid Apps/Free Apps Agreement、Bank/Tax 状态均 Active。006 线上增量迁移已完成，7个Apple对象及完整字段/FK检查通过，旧 notes20181/userauth30/subscriptions44 行数未变。自动审批拒绝将全量token/加密笔记复制本地，已取消导出，改用已验证v3-prod支持的TimeTravel恢复点（未执行restore）；不声称有SQL备份。后台定时跟进更新亦被自动审批拒绝，现已安全暂停，继续在当前任务执行。

密钥配置授权已补齐：用户对精确私钥/目标问题回复“允许配置到该 Worker”。部署任务继续四项secrets配置。用户同时表示设备可以使用，iOS任务检查iPhone Mirroring并准备安装官方TestFlight；尚未进行真实购买。

真机最新状态：iPhone Mirroring 从 iPhone in Use 转为 Connection Interrupted，iOS任务在检查连接，尚未进入购买/恢复流程。不得把已上传/已分组视为真实Sandbox验收。

发布审批状态：四项StoreKit secrets已实际成功；Worker原任务和根任务的deploy均在进程启动前被自动审批拒绝，尚未发布代码。根任务拒绝理由为“验收完成后部署”的条件被解释为真实端到端验收先完成；已明确请求用户批准“先部署已通过代码审查/38项测试的候选，再进行真实Sandbox验收”。未用其它工具/方式绕过。

用户已明确批准“允许现在部署再做 Sandbox 验收”，根实际部署exit0。线上版本 `6e38bb5f-e802-4f77-9a4d-6f070d617054`，现有 `keenotes-remote` / `kns.afoo.me`、D1/KV/DO bindings不变，5分钟cron已发布。Apple真实TEST已开始请求，实施任务独立验证线上状态和非破坏smoke。

真实通知初步结果：新key在Apple官方Production和Sandbox两套Server API均成功请求TEST。getTestNotificationStatus均返回signedPayloadPresent=true、对应环境TEST、sendAttemptResult=SUCCESS（首次投递成功）。正在独立关联Worker D1记录，仍不代表真实购买/续订/退款通过。
ip17已连接镜像；安装TestFlight到Apple账号密码认证处，由用户自行完成。未切换账号、未购买、未触碰原连接Save。

部署后独立检查：线上版本与发布回执一致，D1/KV/DO绑定未改变；9/9非破坏smoke通过（含两环境错误签名拒绝、环境错配拒绝、no-store和旧路由无鉴权拒绝）。notes20181/userauth30/subscriptions44保持，FK错误0。Production/Sandbox各有一条TEST，正在完成UUID关联最终核对。

真实TEST最终核验PASS：两环境各有1条与Apple回执精确匹配的D1 TEST（token NULL），wrong_environment_rows=0，Apple身份仍0；旧三表行数保持、FK错误0。对应公开脱敏证据 `apple-test-final-validation.json` 与 `production-smoke-results.json` 已由根阅读核对。部署及服务通知阶段通过，真实购买/恢复/续订/退款仍待ip17测试。
