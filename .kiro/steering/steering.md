---
inclusion: always
---
<!------------------------------------------------------------------------------------
   Add rules to this file or a short description and have Kiro refine them for you.
   
   Learn about inclusion modes: https://kiro.dev/docs/steering/#inclusion-modes
-------------------------------------------------------------------------------------> 

## 项目概括

参看 #[[file:README.md]]


## API spec

参看 #[[file:api-spec.md]]

## 对于JavaFX程序来说

- 多个地方的状态更新与同步，尽量使用Property这种reactive的方式完成，不要用传统命令式的方式完成（这种会导致代码难以维护）
- 对于后台任务，建议使用JavaFX提供的Worker/Task/Service支持。 不牵扯UI的后台任务，则可以使用传统线程模型。
- 对于UI上多处需要状态同步的场景，尽量通过单一property+多个listener的方式实现observable类似的模式设计和实现。









