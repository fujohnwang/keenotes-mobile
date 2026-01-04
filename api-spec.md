# KeeNotes Web API Specification

## Base URL

```
{endpoint_url}  # 在 Settings 中配置
```

## Authentication

所有 API 请求需要在 Header 中携带 Bearer Token：

```
Authorization: Bearer {token}
```

---

## 1. 保存笔记

### Request

```
POST /
Content-Type: application/json
Authorization: Bearer {token}
```

### Request Body

```json
{
  "channel": "mobile",
  "text": "笔记内容",
  "ts": "2025-12-06 18:30:00",
  "encrypted": false
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| channel | string | 来源渠道，固定为 "mobile" |
| text | string | 笔记内容（如 encrypted=true，则为 AES-GCM 加密后的 Base64 字符串） |
| ts | string | 时间戳，格式 "yyyy-MM-dd HH:mm:ss" |
| encrypted | boolean | 是否为加密内容，默认 false |

### Response

**成功 (2xx)**
```json
{
  "success": true,
  "message": "Note saved"
}
```

**失败 (4xx/5xx)**
```json
{
  "success": false,
  "message": "Error description"
}
```

---

## 2. 搜索笔记

### Request

```
GET /search?q={query}&page={page}&size={size}
Authorization: Bearer {token}
```

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| q | string | 是 | - | 搜索关键词 |
| page | int | 否 | 0 | 页码，从 0 开始 |
| size | int | 否 | 20 | 每页数量，最大 100 |

### Response

**成功 (200)**
```json
{
  "total": 100,
  "page": 0,
  "size": 20,
  "results": [
    {
      "id": "note-123",
      "content": "笔记内容...",
      "createdAt": "2025-12-06T10:30:00Z",
      "updatedAt": "2025-12-06T10:30:00Z"
    }
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| total | int | 总结果数 |
| page | int | 当前页码 |
| size | int | 每页数量 |
| results | array | 搜索结果列表 |
| results[].id | string | 笔记唯一标识 |
| results[].content | string | 笔记完整内容（如 encrypted=true，则为加密内容） |
| results[].createdAt | string | 创建时间 (ISO 8601) |
| results[].updatedAt | string | 更新时间 (ISO 8601) |
| results[].encrypted | boolean | 是否为加密内容 |

**无结果 (200)**
```json
{
  "total": 0,
  "page": 0,
  "size": 20,
  "results": []
}
```

---

## Error Codes

| HTTP Status | 说明 |
|-------------|------|
| 200 | 成功 |
| 400 | 请求参数错误 |
| 401 | 未授权（Token 无效或过期） |
| 500 | 服务器内部错误 |

---

## 3. 获取笔记列表（回顾）

### Request

```
GET /notes?days={days}&page={page}&size={size}
Authorization: Bearer {token}
```

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| days | int | 否 | 7 | 获取最近 N 天的笔记 |
| page | int | 否 | 0 | 页码，从 0 开始 |
| size | int | 否 | 20 | 每页数量，最大 100 |

### Response

**成功 (200)**
```json
{
  "total": 50,
  "page": 0,
  "size": 20,
  "results": [
    {
      "id": "note-123",
      "content": "笔记内容...",
      "createdAt": "2025-12-06T10:30:00Z"
    }
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| total | int | 总结果数 |
| page | int | 当前页码 |
| size | int | 每页数量 |
| results | array | 笔记列表 |
| results[].id | string | 笔记唯一标识 |
| results[].content | string | 笔记完整内容（如 encrypted=true，则为加密内容） |
| results[].createdAt | string | 创建时间 (ISO 8601) |
| results[].encrypted | boolean | 是否为加密内容 |

---

## Notes

- 搜索支持 debounce（500ms），避免频繁请求
- 客户端分页显示每页 20 条，支持 "Load More" 加载更多
- 回顾天数默认 7 天，用户可自定义并持久化保存

---

## E2E Encryption

KeeNotes 支持端到端加密（E2E），加密密码仅存储在客户端本地，服务器端无法解密内容。

### 加密方式

- 算法：AES-256-GCM
- 密钥派生：PBKDF2WithHmacSHA256（65536 iterations）
- 格式：Base64(salt[16] + iv[12] + ciphertext)

### 工作流程

1. 用户在 Settings 中设置加密密码
2. 发送笔记时，客户端使用密码加密内容，设置 `encrypted: true`
3. 服务器原样存储加密内容
4. 查询时，服务器返回 `encrypted` 字段标识
5. 客户端根据 `encrypted` 字段决定是否解密显示

### 注意事项

- 密码丢失将无法恢复加密内容
- 不同设备需使用相同密码才能解密
- 服务器端无法对加密内容进行全文搜索
