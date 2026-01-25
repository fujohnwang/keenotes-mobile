两种方式导入数据到Keenotes：

1. 使用本地API导入
2. 在`Settings -> Data Import` 选择本地NDJSON文件导入


## 使用本地API导入

```bash
http localhost:1979 content="API test for local import server" channel="cli" created_at="2024-10-24 11:11:01"
```

即以POST形式发送JSON格式的Payload即可， JSON必要字段如上所示，可选字段是encrypted，主要针对导入其它KeeNotes服务器的数据，对于普通用户来说，一般不需要了解。

## 择本地NDJSON文件导入

NDJSON格式其实就是一行一个JSON对象。

用户如果想把其它系统的数据导入KeeNotes， 只要将原系统的数据导出为NDJSON格式，然后在KeeNotes桌面版的`Settings -> Data Import`界面选择目标NDJSON文件，KeeNotes桌面版就会自动后台导入数据。

NDJSON每一行JSON的内容和必要字段，跟[使用本地API导入](#使用本地API导入)使用的格式一样。(不过也多个可选项，就是created_at字段也可以用ts字段代替，这两个字段在NDJSON这里都可以)



> NOTE
> 
> 润色后的版本见： [https://afoo.me/posts/2026-01-25-keenotes-desk-data-import.html](https://afoo.me/posts/2026-01-25-keenotes-desk-data-import.html)