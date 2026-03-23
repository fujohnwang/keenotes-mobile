

## JavaFX application devevlopment best practices

- 对于javafx程序来说，如果有长时间可能阻塞UI响应的任务要跑，一般会采用Worker/Task/Service来实现，而不是像传统程序那样用new Thread来实现。
- 对于非JavaFX Application Thread的线程来说，如果他们要引用和使用UI相关的组件，需要在Platform.runLater中调用。 UI线程对多线程更新的要求极其严格！
- JavaFX原生支持reactive设计，能用Property这些基础设施的场景，尽量用这些基础设施，而不是用多种局部变量的组合等命令式编程等常规方式。
- 生命周期管理要严谨！
    - 不要add了某个listener却永远不remove
    - 不要start了某个服务却永远不stop
    - 不要open了某个资源却从来不close
    - etc.



## 其它编程最佳实践

- 能用try-with-resources的地方，就不要自己管理资源的生命周期。






