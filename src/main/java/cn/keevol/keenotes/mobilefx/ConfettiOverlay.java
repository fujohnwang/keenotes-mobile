package cn.keevol.keenotes.mobilefx;

import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.transform.Rotate;

/**
 * Confetti overlay using Canvas + AnimationTimer.
 * Physics ported from Swiftetti Default Preset (matching iOS/Android implementations).
 */
public class ConfettiOverlay extends Pane {

    private static final int PARTICLE_COUNT = 150;
    private static final double BURST_SPEED_MIN = 2500;
    private static final double BURST_SPEED_MAX = 12000;
    private static final double BURST_DIRECTION = 270; // degrees, 270 = upward
    private static final double CONE_SPREAD = 150;
    private static final double GRAVITY = 1000;
    private static final double MASS_MIN = 1.0;
    private static final double MASS_MAX = 2.5;
    private static final double DRAG_MIN = 0.3;
    private static final double DRAG_MAX = 0.6;
    private static final double FALL_DURATION_BASE = 0.1;
    private static final double WOBBLE_AMP_MIN = 5;
    private static final double WOBBLE_AMP_MAX = 15;
    private static final double WOBBLE_FREQ_MIN = 2;
    private static final double WOBBLE_FREQ_MAX = 5;
    private static final double WOBBLE_DECAY = 1.0;
    private static final double SIZE_MIN = 3;
    private static final double SIZE_MAX = 24;
    private static final double FADE_START = 0.6;
    private static final double FADE_DURATION = 0.4;

    private static final Color[] COLORS = {
            Color.WHITE,
            Color.web("#C0C0C0"),
            Color.web("#B2B0B0"),
            Color.web("#007AFF")
    };

    private final Canvas canvas;
    private AnimationTimer timer;
    private Particle[] particles;
    private long startNanos;

    public ConfettiOverlay() {
        canvas = new Canvas();
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        getChildren().add(canvas);
        setMouseTransparent(true);
        setPickOnBounds(false);
    }

    public void fire() {
        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) return;

        double burstX = w * 0.5;
        double burstY = h * 0.85; // 从底部附近向上迸发
        double dirRad = Math.toRadians(BURST_DIRECTION);
        double coneRad = Math.toRadians(CONE_SPREAD);

        particles = new Particle[PARTICLE_COUNT];
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            double angle = dirRad + random(-coneRad / 2, coneRad / 2);
            double speed = random(BURST_SPEED_MIN, BURST_SPEED_MAX);
            double mass = random(MASS_MIN, MASS_MAX);
            double drag = random(DRAG_MIN, DRAG_MAX);
            double totalDuration = FALL_DURATION_BASE + (1.0 / mass) + (drag * 0.5);
            double size = random(SIZE_MIN, SIZE_MAX);
            boolean isCircle = Math.random() < 0.2;
            double aspectRatio = isCircle ? 1.0 : random(0.4, 0.8);

            particles[i] = new Particle(
                    burstX + random(-40, 40), burstY,
                    Math.cos(angle) * speed, Math.sin(angle) * speed,
                    mass,
                    random(WOBBLE_AMP_MIN, WOBBLE_AMP_MAX),
                    random(WOBBLE_FREQ_MIN, WOBBLE_FREQ_MAX),
                    random(-360, 360),
                    size, aspectRatio, isCircle,
                    COLORS[(int) (Math.random() * COLORS.length)],
                    totalDuration,
                    random(0, 0.05)
            );
        }

        startNanos = System.nanoTime();

        if (timer != null) timer.stop();
        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                double elapsed = (now - startNanos) / 1_000_000_000.0;
                boolean allDone = renderFrame(elapsed);
                if (allDone) {
                    stop();
                    clearCanvas();
                }
            }
        };
        timer.start();
    }

    private boolean renderFrame(double elapsed) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        boolean allDone = true;

        for (Particle p : particles) {
            double localT = elapsed - p.delay;
            if (localT < 0) { allDone = false; continue; }

            double progress = Math.min(localT / p.totalDuration, 1.0);
            if (progress >= 1.0) continue;
            allDone = false;

            // X: linear velocity + wobble
            double wobbleDecayF = 1.0 - progress * WOBBLE_DECAY;
            double wobble = Math.sin(localT * p.wobbleFreq) * p.wobbleAmp * wobbleDecayF;
            double x = p.startX + p.vx * localT * 0.15 + wobble;

            // Y: linear velocity only (no gravity, no deceleration)
            double y = p.startY + p.vy * localT * 0.15;

            // Opacity
            double opacity;
            if (progress < FADE_START) {
                opacity = 1.0;
            } else if (FADE_DURATION > 0) {
                opacity = Math.max(0, 1.0 - (progress - FADE_START) / FADE_DURATION);
            } else {
                opacity = 0;
            }
            if (opacity <= 0) continue;

            double pw = p.size;
            double ph = p.size * p.aspectRatio;
            double rotation = p.rotationSpeed * localT;

            gc.save();
            gc.setGlobalAlpha(opacity);
            gc.translate(x, y);
            gc.rotate(rotation);
            gc.setFill(p.color);
            if (p.isCircle) {
                gc.fillOval(-pw / 2, -ph / 2, pw, ph);
            } else {
                gc.fillRect(-pw / 2, -ph / 2, pw, ph);
            }
            gc.restore();
        }
        return allDone;
    }

    private void clearCanvas() {
        canvas.getGraphicsContext2D().clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
    }

    private static double random(double min, double max) {
        return min + Math.random() * (max - min);
    }

    private record Particle(
            double startX, double startY,
            double vx, double vy,
            double mass,
            double wobbleAmp, double wobbleFreq,
            double rotationSpeed,
            double size, double aspectRatio, boolean isCircle,
            Color color,
            double totalDuration, double delay
    ) {}
}
