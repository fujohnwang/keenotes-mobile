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
  "ts": "2025-12-06 18:30:00"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| channel | string | 来源渠道，固定为 "mobile" |
| text | string | 笔记内容 |
| ts | string | 时间戳，格式 "yyyy-MM-dd HH:mm:ss" |

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
| results[].content | string | 笔记完整内容 |
| results[].createdAt | string | 创建时间 (ISO 8601) |
| results[].updatedAt | string | 更新时间 (ISO 8601) |

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
| results[].content | string | 笔记完整内容 |
| results[].createdAt | string | 创建时间 (ISO 8601) |

---

## Notes

- 当前客户端搜索和回顾功能使用 Mock 数据，尚未对接真实 API
- 搜索支持 debounce（500ms），避免频繁请求
- 客户端分页显示每页 20 条，支持 "Load More" 加载更多
- 回顾天数默认 7 天，用户可自定义并持久化保存
