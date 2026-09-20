# KeeNotes 发布记录（2026-09-20）

用户确认先发布已获批 IAP，再单独提交最终 UI。

## 1.9.0（build 3）

- 已执行手动发布，ASC 回读为 `READY_FOR_DISTRIBUTION`。
- App `6757829752`；version `7b27e67a-0b2c-4d15-bf09-186f4776deb4`；build `cf2e6da1-f268-4862-bca3-7cb913ed3a8d`。
- 既有审核单 `4479ec8e-92b0-453b-97ca-035cc81a7979` 的 App、年订阅和订阅组三项均批准；正式美国年费 $8。
- 发布回执：`dist/iap-asc-review-20260920/`。

## 1.9.1（build 4）

- 包含用户定稿的配置区 Purchase 按钮、无底色 History 图标、凭据历史视觉优化和相关双语资源。
- 与 build 3 的 101 项源码 hash 清单比较，仅 5 个 UI/资源文件变动；缺少这五个文件的旧源码快照，故审查基于当前源码与范围核验。
- 独立代码审查通过；原有 3 项 UI 回归全部通过：购买填入与保存保留 PIN、历史取消/确认/隐藏敏感信息、英文系统在中国地区保持英文。
- 复用已有 iPhone 17 / iOS 26.5；未下载模拟器、未截图。测试截图 helper 临时禁用后按备份字节恢复。
- 1.9.1 正式 IPA 的商品 ID、Production/Sandbox endpoint、EULA 和隐私政策链接验证通过，含 77 条中英匹配资源，无 DEBUG fixture、`.storekit` 或 `.xctest`。
- 已于 2026-09-20 09:52（上海）提交审核，版本和审核单均回读为 `WAITING_FOR_REVIEW`；保持 `MANUAL` 发布，获批后仍需手动发布。
- version `ea4a9a65-8482-4af4-8505-c70d44c9782d`；build `9bca34ad-a5be-4d4b-903a-dd70dabe779f`；submission `cbbd04aa-c8bf-46a2-9be7-593eec4034f6`。
- 最终 readiness：0 errors / 0 blocking；唯一 warning 为可选订阅推广图。App Privacy 沿用刚获批并发布的同一 App 设置，本次未变更 app-info。
- ASC CLI 4.9.1 的 stage 示例给出版本子目录，实际 metadata push 需要根目录；首轮创建版本后停止，修正目录并复用同一版本完成，未创建重复审核单。
- 证据：`dist/iap-ui-release-20260920/`。

真实 Apple Sandbox/TestFlight 交易未实测；沿用用户明确选择的单模拟器验证后送审范围。未提交 Git。

## 后续状态查询

```sh
asc review submissions-get --id cbbd04aa-c8bf-46a2-9be7-593eec4034f6 --include items,appStoreVersionForReview --output json
```
