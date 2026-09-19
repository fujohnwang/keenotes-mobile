# KeeNotes IAP 两端集成契约

日期：2026-09-14。用于本次 iOS 与 Workers 两个实施任务的共同边界。产品与 UI 要求以 [实施计划书](/Users/af/workspace.javafx/keenotes-mobile/docs/keenotes-ios-iap-implementation-plan.md) 为准；修改本契约需先通知 supervisor，避免两端各自改变约定。

## 凭据交付

`POST /iap/apple/provision`，Content-Type 为 application/json，不要求已有 access token。

请求：`{"signed_transaction":"<StoreKit transaction JWS>","environment":"Production 或 Sandbox","intent":"automatic 或 user_initiated"}`。

intent 缺省为 automatic，仅表示此次操作是否由用户明确发起，不替代任何验签/授权。后台监听、启动恢复、自动重试使用 automatic；用户本次主动购买、恢复或明确重试交付可以使用 user_initiated。界面意图结束后不得把 user_initiated 持久化给以后所有后台请求。

若用户通过现有 DELETE /user_privacy_choices 停用了 Apple 凭据，服务端持久化 access_disabled。续订通知、对账及 automatic provision 均不清除该状态；只有 user_initiated provision 且服务端确认当前有效权益后才允许重新激活同一 token。原其他渠道行为不变。

HTTP 200：

```json
{
  "endpoint": "https://<对应环境的同步服务>",
  "token": "<固定不变的 IAP token>",
  "entitlement_status": "active",
  "expires_at": 1800000000,
  "provisioning_seconds": 60
}
```

- 只有有效授权返回 200，状态为 active 或 grace；expires_at 为当前有效截止时间的 UTC Unix 整数秒，grace 时为实际宽限期截止时间。
- endpoint/token 均非空；正式与真实 Sandbox 使用 HTTPS；客户端校验结果有效后才能持久化交付并完成交易。不得上传 PIN。
- provisioning_seconds 为 0–60 的传播等待提示，不能用于计算付费有效期，也不保证连接成功。
- 服务端基于 Apple 验签后的环境、应用、白名单商品和永久身份决定权限；environment 请求参数不是可信授权数据。
- 包含凭据的响应使用 Cache-Control: no-store。不得在日志中输出完整 token、签名交易、PIN 或笔记内容。

## 错误结构与处理

错误响应：`{"code":"稳定错误码","message":"可读说明","retryable":true}`。可重试错误可附加 retry_after_seconds 正整数与 Retry-After 响应头。

| code | HTTP | retryable | 客户端行为 |
| --- | --- | --- | --- |
| INVALID_REQUEST | 400 | false | 报告请求错误，保留交易，不假定服务已交付。 |
| INVALID_STOREKIT_JWS | 400 | false | 不交付、不授予权限；不能据此 finish 未验证交易。 |
| UNSUPPORTED_ENVIRONMENT | 400 | false | 报告环境不匹配，保留恢复途径。 |
| PRODUCT_NOT_ALLOWED | 400 | false | 拒绝非目标商品，不改变通用配置。 |
| ENTITLEMENT_INACTIVE | 403 | false | 仅在服务端已确认无有效权益时使用；客户端处理已验证终态交易，但不删除通用配置、历史或已购身份。 |
| ACCESS_DISABLED | 403 | false | 当前 Apple 凭据被用户停用；停止本次自动交付重试，不当作订阅到期或自动清除历史。提示可主动恢复购买以重新领取。 |
| STOREKIT_NOT_CONFIGURED | 503 | true | 已购待交付，可重试。 |
| STOREKIT_UPSTREAM_UNAVAILABLE | 503 | true | Apple 网络/验证依赖暂时失败，可重试，不误判无效签名。 |
| PROVISIONING_PENDING | 503 | true | 权益已记录但授权交付尚未可靠完成，可重试。 |
| STOREKIT_INTERNAL_ERROR | 500 | true | 暂时失败；保留待交付状态。 |

未知 5xx、超时、断网均按可恢复交付失败处理；不引导重复付费。新增错误码需保持兼容并通知另一端。

## Apple 通知

`POST /iap/apple/notifications`，请求为 `{"signedPayload":"<Apple notification JWS>"}`。

通知外层及携带的 transaction/renewalInfo 均需验证。HTTP 200 返回 `{"status":"ok","duplicate":false}`，duplicate 为布尔值。只有事件及授权更新已成功或已可靠安排可恢复后续处理时才返回 200；未可靠持久化时返回可重试错误。通知去重不得跳过以前失败的授权更新。

## 环境与配置

- Bundle ID 基线为 cn.keevol.keenotes；Product ID、公开交付 URL、App Apple ID、条款与隐私链接均可配置。尚未确认的值不得冒充真实生产配置。
- iOS 分别配置 Production / Sandbox 完整 provision URL，按验证后的 StoreKit 交易环境路由，不按 Debug/Release 路由，也不从用户编辑的 endpoint 推导。
- Xcode 本地 StoreKit 测试使用独立测试依赖/fixture；不得向真实服务注入跳过验签开关或将 Xcode 签名当作 Apple Sandbox 签名。
- 2026-09-14 用户更新授权：复用现有生产 Worker/D1/KV/DO，真实 Sandbox 测试数据按 Apple environment 隔离身份和固定 token；不创建单独测试资源。Production 使用 `/iap/apple/provision` 和 `/iap/apple/notifications`；Sandbox 使用 `/iap/apple/sandbox/provision` 和 `/iap/apple/sandbox/notifications`，均在 `https://kns.afoo.me`。服务端受控路径决定 verifier / Server API 环境，body.environment 只校验一致性，不决定或回退验签环境；cron 分别处理两环境与各自游标。
- 用户已授权配置商品、生产备份/增量迁移/部署及 TestFlight 测试上传；实施和部署仍需 supervisor 验收。仅沿用账号 Business 设定，为 KeeNotes 创建自己的 IAP key，不读取或复用其他项目私钥；正式公开 App Store 发布尚未要求。

## 协作交付

- 两端实现者在各自 Codex worktree 实施，不提交 Git，不修改另一个仓库或 supervisor 的计划/验收文件。
- 各自记录 implementation_note.md，提供基线 SHA、工作目录、完整变更文件、测试命令与结果、已知外部阻塞。
- 不把 mock/本地 StoreKit 的通过报告为真实 Sandbox 购买通过。supervisor 独立核验后负责将通过验收的变更应用回用户工作目录。
