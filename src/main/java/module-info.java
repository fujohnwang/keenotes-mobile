module com.keenotes.mobile {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.graphics;
    requires java.net.http;
    requires com.gluonhq.attach.display;
    requires com.gluonhq.attach.lifecycle;
    requires com.gluonhq.attach.storage;
    requires com.gluonhq.attach.util;

    exports cn.keevol.keenotes.mobilefx;
    opens cn.keevol.keenotes.mobilefx to javafx.fxml;
}
