# JavaFX 桌面本地搜索（Issue #140）

本文件记录按讨论及最新标注修订后的实施方案和实现边界。范围仅为 JavaFX 桌面端。

## 用户行为

- 只有原来的一个搜索入口，没有关键词/混合模式选择。SQL `LIKE '%query%'` 恢复为最高权重的匹配来源，Lucene + Java jieba（索引 INDEX、查询 SEARCH）和可选的语义搜索补充召回。
- Settings → AI 下按 MCP、Local Search 排列，默认打开 MCP；示例文本右上角可一键复制完整 JSON。Local Search 将 None 与已保存模型统一放在卡片网格中，整卡可点选，无 radio 圆点；通过边框、背景和 Selected 文字显示当前选项。右上角 `+ Add model` 打开配置弹窗，卡片上的独立 `Configure` 只编辑配置。选择模型即启用 semantic search，选择 `None` 即关闭，保留全部配置和索引；没有单独的启用 section 或开关。添加模型只保存为候选。旧配置自动迁移，原来关闭的配置对应 None。
- 模型配置兼容 OpenAI 的 embeddings API：API Base URL、Model、可选 API Key，以及可选 document/query prefix。URL 填 API 前缀，例如 `http://localhost:11434/v1`；客户端追加 `/embeddings`。
- 没配置或未启用时查询 SQL wildcard 和关键词，不调用模型。启用后，先显示这两路融合结果，再补充语义结果；服务失败或超时保留本地两路结果。连接测试只发送固定测试文本。
- SQL 命中的结果不显示来源 tag（包括同时被其它路命中的 note）；其它结果按实际来源显示 KS / SS。三路按 note ID 去重，不固定置顶 SQL 结果。
- 每条完整 note 对应一个 embedding，不分 chunk，不静默截断。超出模型输入限制的 note 保留为失败项；需要改用适合的模型或修正内容。
- Keyword index、Semantic index 分为两个操作区，各自提供 `Rebuild index`、`Cancel` 和 `Retry failed notes`，运行状态、错误、全量发布与取消相互独立；重试只重置对应类型的失败任务。两路可同时构建；None 状态下仍可重建关键词索引，语义重建和重试不可用，不做定期重建。
- SQL 查询直接覆盖本地已同步笔记，无需重建索引。关键词和语义搜索的历史覆盖需要点击各自的全量按钮；新增同步笔记持续进入后台增量索引，历史索引未完成时提示补充搜索结果可能不完整。

## 模块与落点

| 模块 | 职责 |
| --- | --- |
| `LocalCacheService` / `SearchStore` | 远程同步事务内记录索引任务；保存快照及失败状态；SQL wildcard 召回与融合结果的时间同分排序 |
| `LocalSearchService` | JavaFX Task、后台调度、响应式状态、取消查询、应用生命周期 |
| `LocalSearchEngine` | 增量调度、全量构建、模型迁移、查询与固定 RRF |
| `IndexFamily` | 每路独立的 Lucene Base/Delta、可见性、持久化、Reader 切换及向量复用 |
| `OpenAiEmbeddingClient` | 标准 `/embeddings` 请求、响应 index 对齐、向量校验、超时与单请求取消 |
| `SearchSettingsPane` / `EmbeddingModelDialog` / `MainContentArea` | 模型卡片与添加/编辑弹窗、维护入口、统一搜索列表 |

索引目录使用**实际缓存数据库**的父目录：`search/<epoch>/{keyword,vector}/<profile>/`。正常默认路径为 `~/.keenotes/search/`。数据库仍保存笔记；Lucene 只存搜索所需字段。每路最多召回 100 条：SQL 路按 `created_at DESC, id DESC` 排名，其余两路按各自相关性排名。三路统一按固定加权 RRF（`k=60`，SQL / keyword / semantic 权重 `3 / 2 / 1`）计算分数，按分数降序，同分时按 `created_at`、ID 倒序，缺失时间最后，最后保留最多 100 条。回查笔记、预览和最终列表均保留该顺序，不再全局按时间重排。

新增表均为本地搜索 bookkeeping：`search_source`、`search_pending`、`search_profiles`、`search_meta`、`search_builds`、`search_build_notes`。不增加业务 append_seq，不借用远程 ID 作为连续成功水位，不修改远程协议。

## 索引与发布原则

1. **触发时机**：只有远程同步后的本地事务提交，才会使持久化任务可读。草稿、pending note、发送回执不触发。解密失败及空正文不送给 embedding 服务。
2. **异步执行**：同步事务只记录任务，不在其中做分词、Lucene 写入或 HTTP。查询、构建、增量和配置操作均在后台执行；UI 状态在 JavaFX Application Thread 更新。
3. **增量独立**：关键词和向量使用不同队列、索引和后台调度。关键词小批 commit + Reader refresh 后再确认完成。向量请求按 note 隔离错误，成功后立即 commit + refresh。失败按 note/profile/hash 保留，不会被较大的成功 ID 越过。
4. **固定全量范围**：以 SQLite 事务复制当前同步笔记正文和 ID，形成固定快照；Base 在独立目录构建，按批次保存检查点。全量不追赶不断增长的数据，期间的新 note 继续写入 Delta。
5. **安全发布**：校验快照覆盖、input hash、索引可打开后，先提交持久化元数据，再在锁内替换查询视图。失败或取消保留旧 Base 和在线 Delta；再次点击重建会继续未完成快照。当前进程退出时尚未落盘的构建批次可能重算。
6. **模型隔离**：URL、model、输入前缀和归一化版本构成 profile；API Key 和启用状态不改变向量空间。同 profile、同 note 输入可复用向量。改变模型配置后需要全量重建，切换前保留已发布模型。
7. **迁移覆盖检查**：新 Base 固定快照完成后，补齐一次旧模型已可查询的增量集合，并在发布锁内再次检查。若候选 Delta 尚未覆盖，保留旧视图和构建成果，等增量赶上后重试发布。发布事务同时停用旧 profile 的任务捕获并清理退出使用的队列。
8. **查询一致性**：每个 Lucene 路在合并 Base/Delta 的同一 Reader 视图中排名，按 note ID 和输入版本遮蔽旧副本；全量发布时对照当前源记录剔除早于快照的旧 Delta，保留构建期间的新 Delta。SQL、关键词、语义三路再按 note ID 加权 RRF，返回最多 100 条；Base/Delta 不作为额外信号融合。SQL 使用绑定参数，保留原有 `%` / `_` wildcard 语义，并过滤空正文及已知解密失败笔记。
9. **恢复与清理**：任务持久化后可重试，索引确认前先 commit。清空账号数据轮换 epoch，旧异步结果无法写入新代次或返回到新账号；旧构建退出后回收旧目录，启动也回收遗留目录。缺失 Base 时可保留 Delta 并手动重建。

## 配置及运行边界

- 本地模型服务优先。启用远程 provider 后，note 正文和查询会发送到该服务；设置页明确说明这一点。不会记录 API Key、note 正文或 provider 响应正文到搜索错误信息中。
- API Key 沿用项目现有 `CryptoHelper` 存储方式；已保存模型以稳定 ID 保存在 settings 中，选择状态映射到原来的单一运行配置；模型显示名称不影响索引 profile。保留各已配置 profile 的加密凭据，供迁移中重启后访问旧模型。搜索数据库元数据不保存明文 API Key。
- 向量必须为非空、有限、非零、维度一致的 float 数组，归一化后使用 dot product。搜索 codec 沿用 Lucene 标准磁盘格式，将写入维度上限从默认 1024 扩展到 16384；3072 维写入和重启读取有回归测试。连接测试会验证 query/document 维度一致及本地索引写入。
- 建议服务端 model 名标识固定模型版本。同一个 model 名背后静默更换权重，客户端无法可靠识别；不要把不同向量空间当成同一 profile。重建默认会复用同 profile 的向量。
- HTTP 查询总超时 8 秒，文档请求总超时 40 秒。新查询会取消过期查询的 HTTP Call；取消全量等待当前操作退出，不取消增量任务。
- 增量失败最多自动尝试 3 次，之后通过对应索引的 `Retry failed notes` 重试。选择 None 会停止向量工作，但保留已有索引。
- 构建快照与 Lucene 索引会额外占用磁盘；取消的快照保留到继续完成或清空数据。搜索文件属于本地敏感缓存，与本地笔记数据库同等管理。
- 不包含定期重建、自动回滚、动态 RRF 权重、reranker、chunk、拼音或 n-gram 子串搜索。

## 验证

```sh
mvn test
mvn -Dtest=SearchFxTest -Dkeenotes.search.fx.tests=true test
mvn -Dtest=SearchScaleTest -Dkeenotes.search.scale=true test
mvn -DskipTests package
```

自动化用例覆盖：SQL 子串及 wildcard、未索引历史、参数绑定、三路加权融合与去重、同分时间排序与截断、SQL 来源隐藏标签、中文分词和英文大小写、无 provider 查询、提交后增量可见、低 ID 补入、解密恢复、全量固定范围、取消/重启恢复、向量复用、HTTP 取消及响应 index 校验、失败重试、模型切换覆盖、旧 profile 停用、旧账号文件清理和凭据恢复。

原生 JavaFX 测试使用临时 `user.home` 和本地模拟 HTTP，不访问真实用户数据或外部模型；检查设置页、异步可查询、连续慢查询取消及关键词预览。截图输出到 `target/search-settings-smoke.png`。

2026-09-19 本机容量冒烟：100,000 条合成短笔记、768 维模拟向量，关键词 Base 构建 16.8 秒，向量 Base 构建 105.4 秒，索引约 327 MiB；20 次混合查询中位数 8 ms、P95 19 ms。包含本地 SQLite/Lucene 开销，不含真实 embedding 推理和网络时间，不代表语义质量或跨机器性能保证。
