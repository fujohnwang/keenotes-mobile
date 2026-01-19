package cn.keevol.keenotes.utils.javafx;

import javafx.animation.FadeTransition;
import javafx.animation.SequentialTransition;
import javafx.scene.control.Label;
import javafx.util.Duration;

public class JFX {

    // 常見的「提示訊息出現幾秒後自動淡出」寫法
    public static void showMessage(Label messageLabel, String text) {
        messageLabel.setText(text);
        messageLabel.setOpacity(0.0);

        FadeTransition fadeIn = new FadeTransition(Duration.millis(800), messageLabel);
        fadeIn.setFromValue(0.0);
        fadeIn.setToValue(1.0);

        FadeTransition fadeOut = new FadeTransition(Duration.millis(800), messageLabel);
        fadeOut.setFromValue(1.0);
        fadeOut.setToValue(0.0);
        fadeOut.setDelay(Duration.seconds(1));  // 停留3秒後才淡出

        // 串接動畫
        SequentialTransition sequence = new SequentialTransition(fadeIn, fadeOut);
        sequence.setOnFinished(e -> messageLabel.setText("")); // 可選：清空文字

        sequence.play();
    }

}
