现在javafx桌面版程序长期跑一直开着驻留系统

当点击UI切换功能的时候，还是会僵死。

需要再整体排查下有哪些潜在的资源泄漏，尤其是网络多次间歇性中断后再重连这些情况。

这次的现象是，我点击“On this day in years past”按钮后，没反应。 再点其他按钮，也都没有反应。

看日志，倒是没看到有效信息，只有一段运行日志（已脱敏）：

```
[REDACTED] runtime log removed before open source publication.
```





