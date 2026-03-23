---
author: 王福强
title: keenotes日期时间格式规范
dateCreated: 2026-03-23
---


## principles and rules

1. 所有 NOTE 相关的日期时间信息，以sqlite标准格式为准，即`YYYY-MM-DD HH:MM:SS`
2. 所有存储的日期时间字符串信息，都是UTC存储；
3. 发送NOTE的时候，如果用户没有明确指定时间字段，生成默认日期时间字段的时候，需要根据时区转为UTC作为最终的传输值（服务器端会以2要求的那样存入数据库）；
4. [可选] UI上，可以根据系统时区设置，将数据库中存的日期时间信息转为相应时区下的日期时间进行显示。（但绝对不会影响原始数据状态，因为这种情况下只读）
5. 导入的外部数据或者通过本地 API server 以及本地 MCP server 传入的 NOTE，必须对齐日期时间字段做标准化，可以允许多种格式存在，但标准化之后，必须是统一的sqlite标准日期时间格式，也就是`YYYY-MM-DD HH:MM:SS`

> TIP
> 
> 要根据不同计算机语言与生态的特点选择日期时间类型的format，比如在java中，可能是通过DateFormat的`yyyy-MM-dd HH:mm:ss`格式表达式来格式化日期时间, 在其它语言中，也应该因地制宜选择合适的代码逻辑完成等效的格式化。



## datetime in data flow 



```
          Take Note ---
                       \
import  ---             \
            \            \
MCP  --------------------- (normalize date) Send Note (UTC datetime) -> Remote Server (UTC datetime) -> DB (UTC datetime)
           /                                                   
API ------

```


ws同步到的都是标准化之后的datetime，所以， 存入本地cache db也是标准化后的datetime，最终所有datetime统一，不论是多端与多链路上都是如此。


