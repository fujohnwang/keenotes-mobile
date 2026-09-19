# Purchase 与凭据历史 UI 微调

已采用方案一，保留现有原生 List 和二次确认交互。

- 配置标题与 Purchase／购买、History／历史同一行；英文标题固定换行。两入口使用 small + roundedRectangle + footnote（Purchase 为 borderedProminent，History 为 bordered），取消撑高标签的最小高度；Purchase 使用50%透明度主题蓝底、History 浅灰底，Save Settings 保持实心蓝色，明确 1 > 2 > 3 的层级。
- 历史行增加IAP购买图标/通用钥匙图标；endpoint加粗并在必要时中间截断，完整地址仍可辅助访问及在确认框查看；token仅末4位、等宽次级文字。
- IAP橙色和通用蓝色胶囊；当前行右侧蓝色checkmark及辅助功能selected trait，非当前行chevron。PIN/完整token隐藏，切换仍需二次确认。
- 比较已上传build3：仅3个View与en/zh-Hans两份资源改变；全部Services源SHA256未变。现有测试只同步文案和当前标记的Image角色，无新增方法或削弱断言。
- 构建通过；4项UI用例通过（中英文跨地区、历史取消/确认/保密、英文购买保存切换演示）；深色英文用例补跑1/1通过。共4个不同用例、5次通过执行。
- 原单一iPhone17/iOS26.5，未创建或下载模拟器。深色检查后已恢复原light/large设置；本轮未改版本/Worker或Git提交。
- 已目视核验英文按钮、中文历史列表与深色历史列表；HTML完成资源/脚本静态检查，不冒充浏览器点击实测。

本轮截图独立保存，未覆盖已送审build3的原交互稿与证据。ASC此前提交4479ec8e-92b0-453b-97ca-035cc81a7979最后回读为WAITING_FOR_REVIEW；本轮未撤回或替换，本轮UI尚未上传。

结果包：`test-results/iap-20260919/ui-polish/ui-regression.xcresult` 与 `dark-english.xcresult`。参考 [Apple Buttons HIG](https://developer.apple.com/design/human-interface-guidelines/buttons)。

后续入口调整：构建通过，既有英文跨地区UI用例1/1通过（53.3秒），验证购买、恢复、历史、确认框和表单错误提示。结果包为 `test-results/iap-20260919/ui-polish/configuration-actions.xcresult`。英文四张流程截图重新导出；中文浅色和英文深色设置页从同一现有模拟器重新抓取并目视检查，最后恢复light。原 `en-01` 至 `en-07` 保留为上一轮证据，已在来源清单标记入口布局过时，当前HTML不引用它们。未改动的历史/确认页沿用上一轮验证截图。

最新布局修订：按用户要求将标题与两个入口合并为一行，英文标题内部换行；两按钮统一automatic样式。构建及中英/深浅视觉检查通过，未重复运行前述业务回归；三张设置页截图来自最新构建。

最终按钮层级微调：构建通过；三张设置页截图更新为紧凑按钮，英文浅色、中文浅色、英文深色均目视检查。此次仅视觉调整，未重跑业务回归，未改 ASC。

最终位置调整：History 紧跟配置标题，Purchase 靠右；现有小号按钮、50%主题蓝和灰色样式保持，构建及模拟器截图核验通过。

最新图标调整：History 文字改为历史记录图标（VoiceOver 保留 History／历史）；配置标题取消强制换行。按用户要求未截图，当前图片保留上一版文字按钮布局。

History 图标进一步改为 plain，去掉底色与边框，保留灰色图标及透明点击余量。构建通过；按用户要求不截图。
