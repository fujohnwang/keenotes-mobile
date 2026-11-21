package cn.keevol.keenotes.mobilefx;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.Line;
import javafx.scene.shape.Shape;
import javafx.scene.shape.StrokeLineCap;
import javafx.util.Duration;

/** Clockwise send progress drawn directly in the scene, even for cards many screens tall. */
final class NoteSendBorder extends Region {
    private static final double STROKE = 3;
    private static final double RADIUS = 12;
    private static final Interpolator EASE_OUT_CUBIC = new Interpolator() {
        @Override protected double curve(double t) { return 1 - Math.pow(1 - t, 3); }
    };

    private final Line top = new Line(), right = new Line(), bottom = new Line(), left = new Line();
    private final Arc topRight = new Arc(), bottomRight = new Arc(), bottomLeft = new Arc(), topLeft = new Arc();
    private final DoubleProperty progress = new SimpleDoubleProperty(0);
    private final Timeline animation = new Timeline();

    NoteSendBorder(Color color) {
        getStyleClass().add("note-send-border");
        setMouseTransparent(true);
        setMinSize(0, 0);
        setPrefSize(0, 0);
        for (Shape segment : new Shape[]{top, topRight, right, bottomRight, bottom, bottomLeft, left, topLeft}) {
            segment.setManaged(false);
            segment.setFill(null);
            segment.setStroke(color);
            segment.setStrokeWidth(STROKE);
            segment.setStrokeLineCap(StrokeLineCap.ROUND);
            getChildren().add(segment);
        }
        progress.addListener(observable -> requestLayout());
    }

    void start() {
        animation.getKeyFrames().setAll(new KeyFrame(Duration.seconds(10), new KeyValue(progress, 1, EASE_OUT_CUBIC)));
        animation.playFromStart();
    }

    void finish(Runnable finished) {
        animation.stop();
        progress.set(1);
        // Fade each stroke independently. Fading the group could create a card-sized intermediate texture.
        KeyValue[] fade = getChildren().stream().map(node -> new KeyValue(node.opacityProperty(), 0, Interpolator.EASE_BOTH))
                .toArray(KeyValue[]::new);
        animation.getKeyFrames().setAll(new KeyFrame(Duration.millis(600), e -> finished.run(), fade));
        animation.playFromStart();
    }

    void stop() {
        animation.stop();
    }

    @Override protected void layoutChildren() {
        double inset = STROKE / 2;
        double x = Math.max(inset, getWidth() - inset);
        double y = Math.max(inset, getHeight() - inset);
        double radius = Math.min(RADIUS, Math.min(x - inset, y - inset) / 2);
        double horizontal = x - inset - 2 * radius;
        double vertical = y - inset - 2 * radius;
        double remaining = progress.get() * (2 * (horizontal + vertical) + 2 * Math.PI * radius);

        remaining = line(top, inset + radius, inset, x - radius, inset, remaining);
        remaining = arc(topRight, x - radius, inset + radius, radius, 90, remaining);
        remaining = line(right, x, inset + radius, x, y - radius, remaining);
        remaining = arc(bottomRight, x - radius, y - radius, radius, 0, remaining);
        remaining = line(bottom, x - radius, y, inset + radius, y, remaining);
        remaining = arc(bottomLeft, inset + radius, y - radius, radius, -90, remaining);
        remaining = line(left, inset, y - radius, inset, inset + radius, remaining);
        arc(topLeft, inset + radius, inset + radius, radius, -180, remaining);
    }

    private static double line(Line line, double x1, double y1, double x2, double y2, double remaining) {
        double length = Math.abs(x2 - x1) + Math.abs(y2 - y1);
        line.setVisible(remaining > 0 && length > 0);
        if (line.isVisible()) {
            double fraction = Math.min(remaining / length, 1);
            line.setStartX(x1);
            line.setStartY(y1);
            line.setEndX(x1 + (x2 - x1) * fraction);
            line.setEndY(y1 + (y2 - y1) * fraction);
        }
        return remaining - length;
    }

    private static double arc(Arc arc, double x, double y, double radius, double angle, double remaining) {
        double length = Math.PI * radius / 2;
        arc.setVisible(remaining > 0 && length > 0);
        if (arc.isVisible()) {
            arc.setCenterX(x);
            arc.setCenterY(y);
            arc.setRadiusX(radius);
            arc.setRadiusY(radius);
            arc.setStartAngle(angle);
            arc.setLength(-90 * Math.min(remaining / length, 1));
        }
        return remaining - length;
    }
}
