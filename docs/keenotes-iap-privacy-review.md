# keenotes 隐私政策：Workers 代码核对与中文修订稿

审阅日期：2026-09-14。仅核对 `/Users/af/workspace.keenotes/keenotes-remote-workers` 的最终部署代码；29 个源码、配置、迁移及测试文件与已验收发布清单 SHA-256 全部一致。对应已验收 Worker version：`6e38bb5f-e802-4f77-9a4d-6f070d617054`。

现文的三项表述来自主任务对[现有隐私政策](https://afoo.me/knotes_privacy_policy.html)的 Chrome 阅读反馈；本次未独立取得全文。以下是代码事实及局部替换建议，不是完整政策审查，也不代表已发布。

## 必须修正的三处差异

1. **“不向我们的服务器上传 content”**：使用此 remote 同步时，内容确实上传、保存到 D1，并按 token 分发。HTTP 接口的 `encrypted` 默认是 `false`；WebSocket 虽固定写入 `true`，也没有验证内容是密文。只能说“按客户端提交的形式保存；客户端加密后提交的内容以密文保存”，不能凭本仓库承诺所有客户端始终只发送密文。[HTTP 接收](/Users/af/workspace.keenotes/keenotes-remote-workers/src/handlers.ts:35)、[数据库写入](/Users/af/workspace.keenotes/keenotes-remote-workers/src/db.ts:11)、[WebSocket 保存和转发](/Users/af/workspace.keenotes/keenotes-remote-workers/src/websocket.ts:198)。
2. **“app 内没有支付处理方”**：新增链路接收 Apple 签名交易和订阅通知，并调用 Apple App Store Server API 核验权益。应增加 Apple IAP 及交易资料处理说明。此 Workers IAP 接口不要求银行卡、账单地址或用户邮箱，但实际客户端支付界面不属于本次核对范围。[验签与 Apple API 调用](/Users/af/workspace.keenotes/keenotes-remote-workers/src/apple/storekit.ts:125)。
3. **“没有服务器个人数据可删除”**：服务器保留笔记、固定访问凭据、可关联的 Apple 交易标识和审计记录。现有 privacy choices 接口执行访问停用，不会清除这些 D1 数据；不能把它描述成数据删除。[停用处理](/Users/af/workspace.keenotes/keenotes-remote-workers/src/handlers.ts:176)。

## 实际接收与持久化字段

| 类别 | 接收／处理 | 应用代码持久化 |
| --- | --- | --- |
| IAP 请求与签名 | 开通请求：完整 `signed_transaction` JWS、`environment`、可选 `intent`。通知：完整 `signedPayload`，包括嵌套交易／续订 JWS。Apple API 响应也包含签名交易、续订和状态。完整 payload 可能包含下列落库字段之外的 Apple 数据。 | 不整体保存 JWS、请求 JSON 或完整解码对象。`intent` 用于判断显式恢复，没有单独落库。环境会保存。 |
| 身份与访问 | `environment`、`bundleId`、`appTransactionId` 用于建立稳定身份；服务器产生随机固定 token，并向客户端返回 token、endpoint、权益状态、UTC 秒单位的到期时间。 | `userauth`：`user_id`、token、创建时间；`apple_identities`：环境、bundle、app transaction ID、user_id、token、停用标志、版本／已应用版本、下次对账时间及失败次数。`user_id` 是上述三元组的 SHA-256 派生值，原始交易标识仍保存，因此不应称为完全匿名。 |
| 交易与权益 | Apple 的交易、续订、撤销及当前权益信息。 | `apple_transactions`：环境、bundle、app/original/current transaction ID、product ID；购买、到期、签名、撤销、撤销事件、撤销恢复、服务端有效／撤销状态水位、续订签名时间；宽限期截止时间、自动续订状态、账单重试状态。此表时间字段单位为毫秒。 |
| 通知与审计 | 通知 UUID、类型、子类型、签名时间；历史通知分页。 | `apple_events`：环境、bundle、通知 UUID／类型／子类型、签名及处理时间、关联 token；TEST 不关联 token。`subscriptions`：user_id、token、期限、动作、来源 `apple`、外部事件标识、创建时间；Apple 分支不写入完整 payload 到 `meta`。`apple_replay`：环境、bundle、回放起止时间、分页游标。 |
| 笔记与同步状态 | HTTP 的 `text` 或 WebSocket 的 `content`，以及 channel、时间、加密标记、可选 request ID；同步游标。 | D1 `notes`：id、原样内容、channel、created_at、token、encrypted、request_id。KV 保存 token→user_id 和权益元数据；Durable Object 保存 token、权益投影／闹钟，WebSocket attachment 保存同步游标及同步状态。 |

字段和写入依据：[入口](/Users/af/workspace.keenotes/keenotes-remote-workers/src/apple/routes.ts:47)、[Apple 持久化](/Users/af/workspace.keenotes/keenotes-remote-workers/src/apple/entitlements.ts:33)、[表结构](/Users/af/workspace.keenotes/keenotes-remote-workers/schema.sql:61)、[KV 与 DO](/Users/af/workspace.keenotes/keenotes-remote-workers/src/websocket.ts:118)。解码后的交易／通知会经内部请求传到 DO，属于处理过程；不能把“不整体持久化 JWS”写成“服务器不接收签名资料”。

**PIN 与 IP 的表述边界：**IAP 不需要 PIN，也没有 PIN 存储字段。开通接口读取整个 JSON 后才拒绝顶层 `pin`／`PIN`，所以不能由此证明“服务端从不接收 PIN”，也不能证明客户端从不发送 PIN。当前应用代码未发现主动提取或保存请求 IP 的逻辑；Apple 日志仅记录错误码、环境及对账统计，未发现打印完整 JWS。Cloudflare 平台日志的字段和保留期未在本次代码核对中确认，不能承诺基础设施完全不记录 IP。旧 Gumroad／邮件链路仍会记录邮箱等信息，不能把 Apple 分支结论扩大为整个服务“无个人资料日志”。[PIN 校验与 Apple 日志](/Users/af/workspace.keenotes/keenotes-remote-workers/src/apple/routes.ts:16)、[旧链路日志](/Users/af/workspace.keenotes/keenotes-remote-workers/src/handlers.ts:311)。

## 可审阅的局部替换文案

> 使用 keenotes remote 同步服务时，客户端会将笔记内容及同步所需的信息发送到服务端。服务端通过 Cloudflare Workers、D1、KV 和 Durable Objects 提供存储、访问控制及设备间同步，并按客户端提交的形式保存和转发笔记。客户端加密后提交的内容以密文保存；本 remote 不执行笔记内容的加密或解密。
>
> 对于 Apple 应用内订阅，我们接收并验证 Apple 签名的交易和订阅通知，并向 Apple 查询订阅状态。我们保存交易标识、产品及环境信息、购买和到期时间、续订和撤销状态，以及必要的处理记录，用于开通、恢复和维护访问权限。我们还保存与这些记录关联的固定访问凭据，用于识别和同步属于该凭据的笔记。Workers IAP 接口不要求您提供银行卡资料或笔记 PIN；应用存储逻辑不整体保存收到的签名 payload。
>
> 当前的隐私选择接口提供访问停用。停用会撤销当前凭据的服务访问权限，但保留服务器上的笔记、身份、凭据及交易记录。该操作不等于删除这些资料，也不会取消 Apple 订阅。Apple 用户主动恢复且经验证仍有有效权益时，可重新启用同一凭据。

这三段是局部文案，尚不能替代完整政策。发布前仍需确定并补充：数据保留期限、永久删除申请渠道及实际处理方式、客户端 PIN／加密行为，以及平台日志说明。本仓库没有证明这些未定事项；本次不替产品设定期限、联系地址或服务承诺。旧 Gumroad／邮件服务的披露也不应因增加 Apple IAP 而删除。

## 停用流程的实现限制

- 路由为 `DELETE /user_privacy_choices`，需要 Bearer token。Apple 用户还必须通过当前权益认证，因此已到期或已停用用户不能通过该入口完成请求。
- Apple 分支设置 `access_disabled`，移除 KV 授权并关闭对应 WebSocket；D1 中的固定身份、token、笔记和交易记录保留。自动开通、通知和定时对账不会自动解除主动停用；显式 `user_initiated` 恢复还需验证有效权益。
- 旧授权分支只删除 KV 条目，不删除 D1 身份或笔记。本次检查的 `src/` 中未发现永久数据删除接口或按保留期限自动清除这些记录的任务。

依据：[认证及停用](/Users/af/workspace.keenotes/keenotes-remote-workers/src/handlers.ts:176)、[显式恢复](/Users/af/workspace.keenotes/keenotes-remote-workers/src/websocket.ts:33)、[授权投影](/Users/af/workspace.keenotes/keenotes-remote-workers/src/websocket.ts:118)。

本次仅静态读取代码并生成非秘密报告；未查询生产用户数据，未修改生产代码、线上政策、App 隐私申报或业务协议，未提交 Git。
