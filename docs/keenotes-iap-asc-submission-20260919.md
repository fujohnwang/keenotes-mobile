# KeeNotes IAP 正式提交记录

2026-09-19 19:40:08（Asia/Shanghai），已提交 App Store 审核，回执状态 **WAITING_FOR_REVIEW**。这表示 Apple 已受理并等待审核，尚未审核通过或公开发布；发布方式为 **MANUAL**。

| 项目 | 已核对值 |
| --- | --- |
| App / Platform | KeeNotes / IOS |
| App ID / Bundle ID | 6757829752 / cn.keevol.keenotes |
| Version / Build | 1.9.0 / 3 |
| App Store version ID | 7b27e67a-0b2c-4d15-bf09-186f4776deb4 |
| Build ID | cf2e6da1-f268-4862-bca3-7cb913ed3a8d（VALID） |
| Submission ID | 4479ec8e-92b0-453b-97ca-035cc81a7979 |
| 年订阅 | cn.keevol.keenotes.remote.yearly，US$8/年，美国价格 |
| Subscription / version | 6811796283 / b851450d-1851-415f-b158-fcd2cfc75706 |
| Subscription group / version | 22383447 / cfcb7640-37ba-49da-b699-2f1720988cb2 |
| Worker version | edabe337-6ead-4913-9bb9-24a4fa7d703d |

送审流程：asc archive/export/upload → build VALID →关联版本→ readiness/dry-run → ASC 网页完成首次订阅组/年订阅选入 → 复用同一 draft 加入 app → CLI 正式提交。最终草稿核对恰好三项，未创建重复审核。最终 readiness 为0 errors/0 blocking；可选推广图未启用，首次订阅待提交提示已由本次三项同审解决。App Privacy 在网页完成发布（User ID、Purchase History，用于 App Functionality，linked，不用于 tracking）。

## 已完成与验证范围

- IAP 年订阅、恢复、重试交付和管理入口；通用凭据继续按原输入/保存路径使用。
- Keychain 保存完整 endpoint/token/PIN history；列表仅展示 endpoint、token 后4位、通用/IAP tag及当前标记，隐藏PIN；二次确认后切换。
- 订阅入口为醒目品牌色/加重，历史入口为小号灰字；IAP/历史/错误/状态反馈使用系统 en/zh-Hans 文案。
- 35个不同业务/UI用例均有PASS结果：首轮33项＋两项语言定位修复补跑；失败结果保留。Worker 39项测试＋10项线上smoke通过。
- 按用户明确选择，只复用一个已有模拟器；真实 Apple Sandbox / TestFlight 购买未测。原 SKTestSession Code3 环境问题仍记录，未被独立本地系统StoreKit 1/1观察结果替代。
- 新中英文隐私政策已部署到 https://kns.afoo.me/privacy，并更新App与ASC链接。未更换Worker资源或读取用户笔记。
- Release IPA 检查：正式商品与Production/Sandbox URL正确，含两个locale资源，无.storekit、xctest或DEBUG fixture开关；101项应用源文件在归档后hash无变化。沿用现有签名/profile与已发布版本的加密声明。
- 未stage/commit Git，未下载额外模拟器，测试fixture进程已停止。

## 产物

- [实际截图交互稿](iap-interaction-demo/index.html) / [连线总览](iap-interaction-demo/overview.png)。截图中的$1是本地fixture价，正式ASC美国价为$8/年。
- [详细验收记录](keenotes-iap-20260919-verification.md)。
- 项目内Git ignored回执与IPA：`dist/iap-asc-review-20260919/`；主要回执为 submitted.json、submitted-items.json、submitted-version.json、final-versions.json、final-draft-items.json、final-version-readiness.json。
- IPA SHA-256：`2d7d606d05885f9822445f9cab7e824636a277da0c7584089ff431ecb01cac7f`。

查询此次审核：

```sh
asc review submissions-get --id 4479ec8e-92b0-453b-97ca-035cc81a7979 --include items,appStoreVersionForReview --output table
```
