# KeeNotes iOS 1.9.2 提审记录（2026-09-23）

## 范围与验证

- 包含正常发送与离线暂存分离修复（基于 `055944c`），及本次 Xcode 27 配置调整。
- App 保留 iOS 15；测试 targets 最低 iOS 17。启用 asset symbol extensions、Missing Localizability；`project.yml` 同步 1.9.2（1）。GRDB 6.29.3 的 watchOS 4 manifest 弃用告警按确认方案保留。
- 已有 iPhone 17 Pro / iOS 26.5 模拟器：21 项测试全部通过，无失败/跳过，覆盖 pending delivery、credential behavior、清理失败与配置切换竞态。
- Release archive/export 成功；IPA 核对 bundle ID、版本、build、最低系统；不含 `.storekit` / `.xctest`。归档后 101 项源码、资源及工程配置 hash 未变化。
- IPA：`dist/ios-1.9.2-20260923/KeeNotes-1.9.2-1.ipa`（8,262,244 bytes）。SHA-256：`ee591b3ca9426689ab6c687ed26606ce575c2b42eea90d1e7bc0a02f174dc61e`。
- 归档：`dist/ios-1.9.2-20260923/KeeNotes-1.9.2-1.xcarchive`；测试：同目录 `regression.xcresult` / `test-summary.json`。

## App Store Connect

- App ID：`6757829752`；bundle ID：`cn.keevol.keenotes`。
- 复用用户已创建的 iOS 1.9.2 version：`fd5f4ae2-b74f-436a-99ea-6dc637ada370`。
- Build ID：`f63ae237-0d40-42d9-a4f4-b992c6d0f984`；Apple 回读 `VALID`、build `1`、pre-release version `1.9.2`、最低 iOS `15.0`，已关联目标版本。
- 保留 `MANUAL` 发布方式，审核通过后仍需手动发布。
- 加密实现相较 1.9.1 无变化，沿用已发布构建的 `usesNonExemptEncryption=false`。未新增加密合规声明文档。
- 最终 `asc validate`：0 errors / 0 blocking；唯一 warning 是既有订阅未提供可选的 promotional image。订阅单独校验也无阻塞，本次不修改已批准商品。
- 公共 API 不能核实 App Privacy 发布状态；已通过 Chrome 只读核查 `/apps/6757829752/distribution/privacy`，页面显示 `Published 4 days ago`，数据类型为 Purchase History / User ID，均用于 App Functionality、关联身份。未修改隐私声明。
- 已通过 asc CLI 提交审核，回执 `WAITING_FOR_REVIEW`。提交时间：2026-09-23 17:41:32（Asia/Shanghai）。
- Submission ID：`1fb92eb1-b9ef-4f0f-a806-393881a4a606`；唯一审核项为 iOS 1.9.2。提审前回读并断言 item version 与 `appStoreVersionForReview` 均为目标 version ID。
- 回执：`dist/ios-1.9.2-20260923/submission-submit.json`；最终回读：同目录 `review-final.json` / `version-final.json`。

## 环境处理

- Xcode export 初次 `Copy failed`：distribution 日志确认签名成功，失败点为 `/usr/bin/rsync` 的子进程拾取 Homebrew rsync 3.4.4，参数不兼容。
- 同一 archive 用 `env PATH=/usr/bin:/bin:/usr/sbin:/sbin /opt/homebrew/bin/asc xcode export …` 导出成功，无需重归档；全局 PATH 未改。
- 首次高层提审在添加审核项时超时，留下空草稿。自动审批因其默认参考版本仍为已发布的 1.9.1，拦截了两次恢复动作；两次均未执行。核对 [Apple 规则](https://developer.apple.com/help/app-store-connect/manage-submissions-to-app-review/overview-of-submitting-for-review) 及空 items 回读后，确认没有版本项时会参考最新已批准版本。补充证据获准后，以 `ASC_TIMEOUT=90s` 单独添加 1.9.2、回读确认唯一审核项和参考版本均为 1.9.2，再提交同一审核单成功，无重复审核单。
- 本次命令 JSON / 日志均保存在 `dist/ios-1.9.2-20260923/`；未提交 Git。

## 后续只读查询

```sh
asc review status --app 6757829752 --version-id fd5f4ae2-b74f-436a-99ea-6dc637ada370 --platform IOS --output json
asc builds info --build-id f63ae237-0d40-42d9-a4f4-b992c6d0f984 --output json
```
