# KeeNotes IAP 隐私政策发布草案与 ASC 申报核对

核对日期：2026-09-19。仅静态读取当前 iOS／Worker 源码与公开网页；未查询交易、笔记、用户记录或私钥，未发布、未修改 ASC。

## 可直接纠正的事实

已独立读取当前公开页面 `https://afoo.me/knotes_privacy_policy.html`，有效日期仍为 2026-01-15。页面公开运营者为 Fuqiang Wang，公开邮箱为 **i@afoo.me**，无需另问联系人。

现网页的“无个人数据收集／不上传内容”“app 内无支付处理方”“服务器没有可删除的数据”“卸载即移除所有相关设备数据”均不适用于当前完整实现，应删除这些绝对表述。后文提供整体替换文案，避免新增 IAP 段落与旧段落相互矛盾。

已核对的边界：

- 当前 iOS 在提交笔记前强制要求 PIN，并在设备上加密；上传 `text` 为密文，另上传 channel、时间与 request ID，通过 token 认证。IAP 交付请求只发送签名交易、environment、intent，不发送 PIN。[ApiService.swift](../keenotes-ios/KeeNotes/Services/ApiService.swift)、[AppleProvisioningClient.swift](../keenotes-ios/KeeNotes/Services/AppleProvisioningClient.swift)。这不等于承诺任何其他客户端都不会向同一 remote 发送明文。
- 解密后的本地笔记及待发送内容保存到 app SQLite 数据库。连接历史（endpoint/token/PIN）、当前配置及购买凭证缓存在 Keychain；历史删除只删除对应 history 条目，不会删除当前配置、旧迁移键或独立购买缓存。[DatabaseService.swift](../keenotes-ios/KeeNotes/Services/DatabaseService.swift)、[CredentialsStore.swift](../keenotes-ios/KeeNotes/Services/CredentialsStore.swift)、[KeychainService.swift](../keenotes-ios/KeeNotes/Services/KeychainService.swift)。不能承诺卸载会清空全部 Keychain 项。
- Worker 在 D1 保存笔记与同步元数据、token、Apple 交易／权益／通知审计记录，在 KV 与 Durable Objects 保存授权／同步状态。Apple 链路不会完整持久化收到的 JWS，但会接收、验证它，保存所需字段。详见[先前字段核对](keenotes-iap-privacy-review.md)。
- 当前 `SettingsView` 没有 Data／Privacy／删除远程资料入口。隐藏 DebugView 的 `Clear All Notes` 只清除本地 notes 与 sync_state，不清除 pending、Keychain 或服务器数据。[DebugView.swift](../keenotes-ios/KeeNotes/Views/DebugView.swift)。
- Worker `DELETE /user_privacy_choices` 只停用访问。Apple 分支标记 `access_disabled`，不删除 D1；普通分支删除 KV 授权，不删除 D1。已过期的 Apple 权益不能通过该认证入口操作。源码未发现定期永久清除这些数据的机制。
- 现有语音输入使用 Apple Speech framework，未强制仅设备识别；不能承诺语音绝不离开设备。App 未实现把原始录音保存到 KeeNotes Worker 的链路。[SpeechRecognitionService.swift](../keenotes-ios/KeeNotes/Services/SpeechRecognitionService.swift)。Apple 说明该 API 可能使用其服务器处理音频。[Apple Speech 文档](https://developer.apple.com/documentation/speech/asking-permission-to-use-speech-recognition)。

## 中文完整替换草案

以下内容可替换原页正文；发布时将“更新日期”设为实际部署日。标题和日期之外无未填占位符。保留／删除段落如实说明当前能力，不作尚未实现的承诺。

### KeeNotes 隐私政策

KeeNotes 由 Fuqiang Wang 开发和运营。本政策说明 KeeNotes iOS 应用及我们提供的远程同步与订阅服务如何处理信息。

**设备上的信息。** 应用在设备上保存笔记、待发送内容和偏好设置。您保存的服务地址、访问 token 和加密密码，以及已保存的连接历史和购买凭证，存储在 iOS Keychain 中，用于连接服务和切换配置。连接历史不会作为历史列表上传到我们的服务器。删除历史条目不会删除正在使用的配置、独立保存的购买凭证或远程笔记。卸载应用不会取消订阅，也不应视为清除服务器资料或所有 Keychain 凭证的方法。

**远程同步。** 配置并使用远程服务时，应用会向您选定的 endpoint 发送加密后的笔记及同步所需的时间、来源、请求标识和访问 token。当前 iOS 应用在设备上使用您设置的加密密码加密笔记；同步与 IAP 交付请求不发送该密码。我们的远程服务保存并转发收到的笔记内容及同步信息，不执行笔记解密。请妥善保管密码。您自行配置的第三方服务由其运营者负责，其隐私政策同样适用。

**Apple 应用内订阅。** Apple 处理订阅购买和支付。KeeNotes 的 IAP 服务不接收您的银行卡或银行账户资料。为开通、恢复和维护订阅，我们接收并验证 Apple 签名交易及订阅通知，并向 Apple 查询权益状态。我们保存相关交易标识、商品及环境信息、购买和到期时间、续订／撤销状态及必要的处理记录，并关联固定访问 token。我们不整体保存收到的签名交易 payload。Apple 订阅和其他渠道的服务凭证分别管理；购买 IAP 不会自动合并其他凭证下的笔记。

**服务提供方与权限。** 我们使用 Cloudflare 提供远程服务的运行、存储和同步基础设施；网络流量信息可能由其处理以提供和保护服务，详情见 [Cloudflare 隐私政策](https://www.cloudflare.com/privacypolicy/)。应用内购买适用 [Apple 隐私政策](https://www.apple.com/legal/privacy/)。如果您主动使用语音输入，应用会请求麦克风和语音识别权限；Apple 的语音识别可能在设备上或其服务器上处理音频。我们不会把原始录音上传到 KeeNotes 同步服务器；生成的文字按普通笔记处理。当前应用未集成广告或第三方行为分析 SDK。

**其他购买渠道。** 如果您在应用之外通过 Gumroad 等既有渠道取得我们提供的服务凭证，相关订单标识、邮箱和订阅状态会用于验证购买、开通访问以及发送服务邮件。现有邮件交付使用 Resend。此流程独立于 Apple IAP，不要求 Apple IAP 用户提供邮箱。相应服务提供方的隐私政策也适用。

**保留与隐私选择。** 远程笔记、凭证和订阅处理记录用于同步、恢复访问与维护权益。当前服务没有为这些记录设置自动永久删除期限。订阅到期、取消续订、退款或停用访问不会自动删除已存资料。当前服务的访问停用功能仅撤销访问权限，不等于永久删除资料；应用也尚未提供永久删除服务器资料的控制。Apple 订阅应在 App Store 中管理或取消。您可通过下方邮箱就资料访问、保留或删除提出请求或咨询；请勿通过邮件发送 PIN、完整 token 或支付资料。

**联系。** 如对本政策或信息处理有疑问，请联系 Fuqiang Wang： [i@afoo.me](mailto:i@afoo.me)。

## English replacement draft

### KeeNotes Privacy Policy

KeeNotes is developed and operated by Fuqiang Wang. This policy describes how the KeeNotes iOS app and our remote sync and subscription services handle information.

**Information on your device.** The app stores notes, pending notes, and preferences on your device. Saved service endpoints, access tokens, encryption passwords, connection history, and purchased credentials are stored in iOS Keychain to support connections and configuration switching. The connection-history list is not uploaded to our servers. Removing a history entry does not remove the active configuration, separately cached purchased credentials, or remote notes. Uninstalling the app does not cancel a subscription and should not be treated as deleting server records or all Keychain credentials.

**Remote sync.** When you configure and use a remote service, the app sends encrypted notes and the timestamps, source information, request identifiers, and access token needed for sync to your selected endpoint. The current iOS app encrypts notes on your device using the encryption password you provide. Sync and IAP provisioning requests do not send that password. Our remote service stores and forwards the submitted note content and sync information; it does not decrypt notes. Keep your password safe. If you configure a third-party service, that service is operated separately and its privacy policy also applies.

**Apple in-app subscriptions.** Apple handles subscription purchases and payment. The KeeNotes IAP service does not receive your payment-card or bank-account details. To provision, restore, and maintain a subscription, we receive and verify Apple-signed transactions and subscription notifications and query Apple for entitlement status. We store the relevant transaction identifiers, product and environment information, purchase and expiration dates, renewal and revocation status, and processing records, linked to a persistent access token. We do not store the complete signed transaction payload. Apple subscriptions and credentials obtained through other channels are managed separately. Purchasing IAP does not automatically merge notes associated with other credentials.

**Service providers and permissions.** We use Cloudflare infrastructure to run the remote service and provide storage and sync. Cloudflare may process network traffic information to deliver and protect the service, as described in its [Privacy Policy](https://www.cloudflare.com/privacypolicy/). In-app purchases are subject to [Apple’s Privacy Policy](https://www.apple.com/legal/privacy/). If you choose voice input, the app requests microphone and speech-recognition permissions. Apple’s speech recognition may process audio on your device or on Apple’s servers. We do not upload raw recordings to the KeeNotes sync server; resulting text is handled as note content. The current app does not integrate advertising or third-party behavioral analytics SDKs.

**Other purchase channels.** If you obtain credentials for our service outside the app through an existing channel such as Gumroad, relevant order identifiers, email addresses, and subscription status are used to verify purchases, grant access, and deliver service emails. The existing email delivery service uses Resend. This process is separate from Apple IAP and does not require Apple IAP users to provide an email address. The relevant providers’ privacy policies also apply.

**Retention and privacy choices.** Remote notes, credentials, and subscription processing records support sync, restored access, and entitlement maintenance. The current service does not set an automatic permanent-deletion period for these records. Subscription expiration, cancellation, refunds, or disabled access do not automatically delete stored records. The current access-disabling function revokes access; it does not permanently delete data. The app does not yet provide a control for permanent deletion of server records. Manage or cancel Apple subscriptions through the App Store. You may contact us below with requests or questions about access, retention, or deletion. Do not email your PIN, complete access token, or payment details.

**Contact.** For questions about this policy or our handling of information, contact Fuqiang Wang at [i@afoo.me](mailto:i@afoo.me).

## ASC 隐私标签：本次确定应增加／核对的项目

未读取或修改 ASC 当前问卷；主任务需与现有申报合并，而非清空重建。

| 数据类型 | 本次代码依据 | 用途／关联／追踪建议 |
| --- | --- | --- |
| Purchases → Purchase History | Apple transaction/original transaction/product ID、购买／到期／续订／撤销状态持续存储 | App Functionality；Linked to User：Yes；Used for Tracking：No |
| Identifiers → User ID | appTransactionId 派生身份与固定 token 跨会话关联权益和笔记，原交易 ID 仍保存 | App Functionality；Linked to User：Yes；Used for Tracking：No |

随机 token 和 hash 不等于匿名；这里的用户关联也不意味着收集真实姓名。支付卡号／银行信息不应仅因使用 IAP 就勾选；当前 IAP 不接收该资料。仅存设备的 PIN 与历史列表无需作为离设备收集申报。这些映射依据 Apple 对 Purchases、User ID、收集及用户关联的定义。[Apple App privacy details](https://developer.apple.com/app-store/app-privacy-details/)

**既有笔记同步需按实际可读性单独核对，不能用“新增 IAP 不上传明文”替整个产品下结论。** 本 iOS 的 note text 是端到端密文；Apple 的收集定义涉及离设备持久化且可读取的信息，不能仅看到 D1 中有密文字段便断言它属于可读 Other User Content。Worker 同时保存未加密的 channel／时间／标识元数据，并支持其他客户端直接提交内容。若现有整个产品存在服务方可读的笔记内容，则应申报 Other User Content，App Functionality，Linked：Yes，Tracking：No；若没有，保留上述两项确定新增项，同时由主任务按实际 metadata 使用方式核对现有标签。不可因任意笔记里“可能”写了地址等，就逐项增加所有数据种类。

语音由 Apple framework 处理不自动等于开发者收集 Audio Data；本 app 没有向 KeeNotes 上传原始录音的实现。Gumroad 邮箱来源是 app 外购买，不能未经判断便将整个外部商店的数据分类照搬成“从 iOS app 收集”。Cloudflare 控制台日志保留设置本次未查，不承诺“完全不记录 IP”。

## 删除能力的确定事实与审核推断

确定事实是“当前没有永久远程删除功能，停用不是删除”，不是“新建了注册账号”。当前 IAP 用 Apple 交易派生持久凭证，没有独立注册／登录表单。

Apple 指引要求支持账号创建（包括自动生成 guest 账号）的 app 提供 app 内发起删除；单纯停用不满足该要求，普通 app 也不应以发邮件作为唯一删除流程。因此，**若 App Review 将这种持久服务身份认定为自动创建账号**，现有停用接口不够。此为需在审核说明中解释的适用性判断，不能宣称 Apple 已判定本产品不合规；也不能把邮箱文案冒充已实现的删除能力。[Apple account deletion guidance](https://developer.apple.com/support/offering-account-deletion-in-your-app/)

本次不扩展实现范围、不新增删除流程。最小业务决策只有：**是否要将永久远程删除纳入本次版本承诺。** 未做该决策前，可先发布上述准确描述当前状态的政策并如实填写审核说明；若决定承诺永久删除，则需另行实现相应身份验证、数据删除及保留依据，不能仅改文案。无需为了修复本次已确定的错误再询问邮箱、虚构保留天数或承诺处理时限。

## 网站源码与发布线索

没有定位到当前 `knotes_privacy_policy.html` 的本机源文件。只读检索限于已有 workspace 的公开项目文件名和相关 blog 项目，没有检索密钥、浏览器配置、邮件或用户文档。

- `/Users/af/workspace.keevol/afoo-blog-builder/README.md` 说明它是 `afoo.me` 后处理器，构建后拷贝到另一个 `afoo.me` 目录使用；没有给出当前网页源目录。
- `/Users/af/workspace.keevol/nginx_conf_d_on_clound/afoo.me.conf` 的历史配置指向服务器 `/root/afoo.me`。这是仓库配置线索，不能据此确认当前生产部署位置。
- `/Users/af/workspace.keevol/afoo_blog_starter_DEPRECATED/deploy` 是已废弃 starter 的 `rsync --delete` 脚本，目标为测试目录，不适用于发布当前政策；未执行。
- `/Users/af/workspace.keevol/keevol.github.com/CNAME` 是 `keevol.com`，不是本隐私政策域名。

发布应由主任务使用已知现行站点流程或单文件更新方式完成，避免运行上述历史部署脚本。本文内容已可供其直接使用。
