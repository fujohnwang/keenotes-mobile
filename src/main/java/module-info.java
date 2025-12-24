module com.keenotes.mobile {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.graphics;
    requires javafx.base;
    requires java.net.http;
    requires java.sql;
    requires io.vertx.core;
    requires io.netty.codec.http;
    requires com.gluonhq.attach.display;
    requires com.gluonhq.attach.lifecycle;
    requires com.gluonhq.attach.storage;
    requires com.gluonhq.attach.util;

    exports cn.keevol.keenotes.mobilefx;
    opens cn.keevol.keenotes.mobilefx to javafx.fxml, javafx.graphics, javafx.base;
}
