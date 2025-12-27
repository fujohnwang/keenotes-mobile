## 关于JavaFX程序跨平台的时候项目设置问题

是采用mono单一项目还是多项目，目前来看，使用单一项目，然后代码中根据环境做条件判断是推荐做法：


```
import com.gluonhq.attach.util.Platform;   // 注意：不是 javafx.platform

if (Platform.isAndroid()) {
    // Android 特有逻辑：权限、通知栏、返回键处理等
} else if (Platform.isIOS()) {
    // iOS 特有逻辑：Face ID、深色模式适配等
} else if (Platform.isDesktop()) {
    // 桌面特有：菜单栏、系统托盘、多窗口等
}

// 更推荐的方式：使用 Gluon Attach 服务（ServiceLoader 机制）
PushService push = PushService.create().orElse(null);
if (push != null) {
    push.retrieveToken(token -> {
        // 无论哪个平台，只要支持就拿到 token
    });
}
```

式样和风格也是类似做法：

```
scene.getStylesheets().add(
    Platform.isAndroid() ? "android.css" :
    Platform.isIOS()     ? "ios.css" :
                           "desktop.css"
);
```









