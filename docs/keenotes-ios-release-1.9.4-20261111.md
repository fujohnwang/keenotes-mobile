# KeeNotes iOS 1.9.4 提审记录（2026-11-11）

- 提交时间：2026-11-11 10:10:08（Asia/Shanghai）。asc 回读版本及审核单均为 `WAITING_FOR_REVIEW`。
- 发布方式：用户确认沿用 `AFTER_APPROVAL`，审核通过后自动发布。
- What’s New：`improve UX on text selection`。
- 流程：asc Release archive/export → publish appstore 上传、关联、应用文案 → validate → review submit。上传与提交均先核对 dry-run；未创建重复审核单。

| 项目 | 值 |
| --- | --- |
| App | KeeNotes / `6757829752` |
| Bundle ID | `cn.keevol.keenotes` |
| Platform / Version / Build | IOS / 1.9.4 / 1 |
| Version ID | `e4f09e15-f94c-4431-a617-548ec27eda20` |
| Build ID | `bbffd39e-e5ca-4958-a58c-8c18afa5d47a` |
| Submission ID | `65c6d49c-2520-4d57-bf39-e6e6ebbaff94` |

## 验证与产物

- Review 文字选择修复在 iPhone 17 Pro / iOS 27.0 完成旧代码失败对照与修复验证，完整 UI/E2E 11/11 通过。版本号更新后 Release 归档及导出成功；发行包包含新选区保护类型，不含 UI fixture、`.xctest` 或 `.storekit`。
- IPA 签名校验通过，版本为 1.9.4（1）、最低 iOS 15.0。SHA-256：`8df5def3fe600fa6fdf563565639e3b4556bcb3927cd8b43dba1800da9fc2492`。
- 构建状态 `VALID`。最终 validate 0 errors / 0 blocking；既有订阅仅有缺少可选 promotional image 的 warning。App Privacy 已通过现有 Chrome 会话只读确认 `Published 2 months ago`。
- 加密实现未改变，沿用已发布 1.9.3 构建的 `usesNonExemptEncryption=false`。未修改隐私、订阅商品、截图或审核账号。
- 产物及回执目录：`dist/ios-1.9.4-20261111/`；IPA：`KeeNotes-1.9.4-1.ipa`；关键回执：`review-submit.json`、`review-final.json`、`version-final.json`、`validation-final.json`。
- asc 默认 profile 的旧私钥符号链接被 CLI 拒绝，本次使用已有 Keychain `mkdown` profile（相同 Key ID），未改全局认证配置。导出限定系统 PATH，避免既有 rsync 版本冲突。未提交 Git。

## 后续只读查询

```sh
asc --profile mkdown review status --app 6757829752 --version-id e4f09e15-f94c-4431-a617-548ec27eda20 --platform IOS --output json
```
