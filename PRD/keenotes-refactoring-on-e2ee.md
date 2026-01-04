当前目录下是两个基于maven管理的java项目，分别服务于keenotes这个产品的client端和server端。

client端基于javafx技术打造，将编译为win/macos/linxu/ios/android不同平台的应用，桌面版基于jpackage打包（面向win/linux/macos）， ios和android分别打包。

当前设计是这样的：

- 客户端
	1. 提供基础的短笔记记录、回顾与搜索功能；
	2. 用户通过设置自己的keenotes server端来保存最终内容；
	3. 用户也可以设置PIN码来对短笔记进行端到端加密；

- 服务器端
	1. 基于armeria框架提供基础的HTTP(S) API接入（包括基础鉴权等）
	2. 使用sqlite作为数据存储的数据看
	3. 根据数据是否加密决定提供什么样的查询结果

现在，我们要对这个设计进行进一步的升级，原则是“隐私为重”。

1. 数据在客户端可以是明文的，但离开客户端必须加密，这就意味着，会在客户端要求用户必须输入PIN码或者说加密用的密码（主要用于AES-GCM对称加密密钥），此后所有离开端或者远程进入客户端的内容都需要这个密码加解密；
2. 以服务端的数据为Single Source of truth， 数据库schema的id类型为：`id INTEGER PRIMARY KEY AUTOINCREMENT`，每个客户端会有一个相同schema的本地全量缓存数据库，与服务器端表唯一的区别是： `id INTEGER PRIMARY KEY`， 之所以这样设计是因为，我们会在服务器端接收所有数据，然后再通过WSS（websocket）分发和同步给各个客户端，而数据的id将决定唯一的记录。
3. 为了保证数据的同步状态可以保持完整，在并发的情况下也不会数据不同步，我们会在客户端和服务器端同时有两个设计：
	- 客户端除了缓存短笔记的表，还会存一个last_sync_id的表，用来保存当前客户端最后同步的记录的id，在客户端通过ws连接到服务端之后，会将这个状态传递给服务器端，服务器端将通过这个值来同步数据。
	- 服务端会在每个ws的channel连接之后，为其分配两个关键状态，一个是记录数据是否已经catch up，一个是用来存储在数据同步期间新来数据的buffer，只有当之前数据都同步完成后，再发送buffer中的数据
	- 如果要同步的数据量很大，可以按批次发送并同步

## 架构设计

通过HTTP(S) POST写，通过Websocket同步数据到各端，整个写的方向都是单向的：

```
# write path

client1     client2     client3      client4       client5
   \              |            |             /              /
   http post.          http post                 http post
    ---------------------------------------------------------
						keenotes server
                  |
                sqlite


# sync path

client1     client2     client3      client4       client5
   \              |            |             /              /
   ws           ws            ws       ws           ws 
    ---------------------------------------------------------
						keenotes server
                  |
                sqlite
```


## 功能重构

- 搜索功能完全移到本地客户端侧进行（端侧明文，可以直接利用SQL的模糊查询）
- 回顾功能也完全移到本地客户端侧进行；
- 记录笔记的链路依然以HTTP POST到服务器端为主（因为我们以服务器端的数据为Single Source Of Truth）
- 新连接客户端， last_sync_id为-1，表示该端之前没有任何数据的空白状态。
	- 为了避免频繁单条插入本地的缓存数据看，可以按批次间隔更新last_sync_id，既利用了批量插入，也提高了带宽效率。




## 关于数据库schema

原来为了兼容不同版本的数据（没加PIN码之前与加了PIN码之后），我们新增了字段来区分content字段是否已经加密，现在，所有的content内容将被加密，这个字段就不需要了。

其它字段都是明文，比如timestamp这些字段，因为主要是content的内容才是重中之重。


## 其它要求

- ws的重连、心跳、超时等健康监测都要加上，以保证websocket连接的健康
- 所有的加解密在客户端进行，数据离端即加密。
- 加密时候采用AES-GCM对称加密，配合AEAD（比如使用时间戳字段）进一步保证数据完整性。



## 将来的可能改进

**这部分不做**，只是作为roadmap的记录。

- 使用[durable streams](https://github.com/durable-streams/durable-streams/pull/72)替代ws完成数据的同步
- 添加一个客户端的本地Web API，便于用户通过脚本批量导入短笔记内容，比如在桌面端所在电脑上跑一个脚本POST原来的一些短笔记给客户端，客户端再将其加密发送给远程保存。














