module com.keenotes.mobile {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.graphics;
    requires javafx.base;
    requires java.net.http;
    requires java.sql;

    // OkHttp WebSocket客户端
    requires okhttp3;

    // Vert.x用于JSON处理
    requires io.vertx.core;

    // Bouncy Castle用于Argon2+HKDF
    requires org.bouncycastle.provider;

    requires com.gluonhq.attach.display;
    requires com.gluonhq.attach.lifecycle;
    requires com.gluonhq.attach.storage;
    requires com.gluonhq.attach.util;

    exports cn.keevol.keenotes.mobilefx;

    opens cn.keevol.keenotes.mobilefx to javafx.fxml, javafx.graphics, javafx.base;
}
