package cn.keevol.keenotes.mobilefx;

import javafx.animation.Animation;
import javafx.animation.AnimationTimer;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.beans.InvalidationListener;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Decorative companions that occupy only the sidebar's remaining space. */
final class SidebarCompanionsView extends Region {
    private static final double ART_WIDTH = 570;
    private static final double ART_HEIGHT = 198;
    private static final long BOUNCE_INTERVAL_NANOS = 90_000_000L;
    private static final Color INK = Color.web("#111111");
    private static final Color SKY = Color.web("#8ac5f4");

    private final Group artwork = new Group();
    private final Rectangle viewportClip = new Rectangle();
    private final Scale artworkScale = new Scale(1, 1, 0, 0);
    private final List<Eye> eyes = new ArrayList<>();
    private final Map<Group, BlinkAnimation> blinkAnimations = new LinkedHashMap<>();
    private final List<BounceAnimation> bounceAnimations = new ArrayList<>();
    private final EnumSet<KeyCode> pressedKeys = EnumSet.noneOf(KeyCode.class);
    private Scene observedScene;
    private Window observedWindow;
    private Point2D pointer;
    private long lastPulse;
    private long lastBounce;
    private boolean running;
    private boolean disposed;

    private final ChangeListener<Scene> sceneListener = (obs, oldScene, newScene) -> observeScene(newScene);
    private final ChangeListener<Window> windowListener = (obs, oldWindow, newWindow) -> observeWindow(newWindow);
    private final InvalidationListener visibilityListener = obs -> updateAnimationState();
    private final InvalidationListener positionListener = obs -> requestMotion();
    private final InvalidationListener bouncePositionListener = obs -> {
        // Keep looking at the pointer while the character's coordinate system moves.
        if (pointer != null) {
            requestMotion();
        }
    };
    private final EventHandler<KeyEvent> keyHandler = event -> {
        if (!canAnimate()) {
            pressedKeys.clear();
            return;
        }
        if (event.getEventType() == KeyEvent.KEY_PRESSED) {
            // Track ignored shortcuts and throttled presses too, so release cannot replay them.
            pressedKeys.add(event.getCode());
        } else if (pressedKeys.remove(event.getCode())) {
            return;
        }
        // macOS IME composition can suppress PRESSED while still delivering RELEASED.
        // Only an unmatched release triggers the fallback; no text/IME state is observed.
        if (event.getCode().isModifierKey()
                || event.isControlDown() || event.isAltDown() || event.isMetaDown()) {
            return;
        }
        long now = System.nanoTime();
        if (lastBounce != 0 && now - lastBounce < BOUNCE_INTERVAL_NANOS) {
            return;
        }
        List<BounceAnimation> available = bounceAnimations.stream()
                .filter(bounce -> bounce.jump.getStatus() == Animation.Status.STOPPED).toList();
        if (!available.isEmpty()
                && available.get(ThreadLocalRandom.current().nextInt(available.size())).play()) {
            lastBounce = now;
        }
        // Observe only: typing and keyboard shortcuts retain their normal event handling.
    };
    private final EventHandler<MouseEvent> pointerHandler = event -> {
        if (canAnimate()) {
            pointer = new Point2D(event.getSceneX(), event.getSceneY());
            // Blink only after a quiet interval; movement reopens the eyes and restarts the wait.
            for (BlinkAnimation blink : blinkAnimations.values()) {
                blink.stop();
                blink.schedule();
            }
            requestMotion();
        }
    };
    private final EventHandler<MouseEvent> exitHandler = event -> {
        // A Scene handler also sees exits from its children; those are not window exits.
        if (event.getTarget() == observedScene) {
            pointer = null;
            requestMotion();
        }
    };
    private final AnimationTimer motion = new AnimationTimer() {
        @Override
        public void handle(long now) {
            if (!canAnimate()) {
                resetEyes();
                return;
            }
            try {
                double elapsed = lastPulse == 0 ? 1.0 / 60 : (now - lastPulse) / 1_000_000_000.0;
                lastPulse = now;
                double blend = 1 - Math.exp(-elapsed / 0.06);
                boolean settled = true;
                for (Eye eye : eyes) {
                    settled &= eye.follow(pointer, blend);
                }
                if (settled) {
                    stopMotion();
                }
            } catch (RuntimeException error) {
                resetEyes();
                throw error;
            }
        }
    };

    SidebarCompanionsView() {
        getStyleClass().add("sidebar-companions");
        setMouseTransparent(true);
        setFocusTraversable(false);
        setMinSize(0, 0);
        setPrefSize(0, 0);
        setClip(viewportClip);
        artwork.setManaged(false);
        artwork.getTransforms().add(artworkScale);
        createCharacters();
        getChildren().add(artwork);

        sceneProperty().addListener(sceneListener);
        visibleProperty().addListener(visibilityListener);
        localToSceneTransformProperty().addListener(positionListener);
    }

    @Override
    protected void layoutChildren() {
        // Also contains a jump if the sidebar is resized while it is in progress.
        viewportClip.setWidth(getWidth());
        viewportClip.setHeight(getHeight());
        double scale = Math.min(1, Math.min(getWidth() / ART_WIDTH, getHeight() / ART_HEIGHT));
        // Below this size the eyes become unreadable. Navigation always gets priority.
        artwork.setVisible(scale >= 0.26);
        artworkScale.setX(scale);
        artworkScale.setY(scale);
        artwork.setLayoutX((getWidth() - ART_WIDTH * scale) / 2);
        artwork.setLayoutY(getHeight() - ART_HEIGHT * scale);
        updateAnimationState();
        if (pointer != null) {
            requestMotion();
        }
    }

    private void observeScene(Scene scene) {
        resetEyes();
        if (observedScene != null) {
            observedScene.removeEventFilter(MouseEvent.MOUSE_MOVED, pointerHandler);
            observedScene.removeEventFilter(MouseEvent.MOUSE_DRAGGED, pointerHandler);
            observedScene.removeEventFilter(KeyEvent.KEY_PRESSED, keyHandler);
            observedScene.removeEventFilter(KeyEvent.KEY_RELEASED, keyHandler);
            observedScene.removeEventHandler(MouseEvent.MOUSE_EXITED, exitHandler);
            observedScene.windowProperty().removeListener(windowListener);
        }
        observedScene = scene;
        observeWindow(scene == null ? null : scene.getWindow());
        if (scene != null) {
            scene.addEventFilter(MouseEvent.MOUSE_MOVED, pointerHandler);
            scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, pointerHandler);
            scene.addEventFilter(KeyEvent.KEY_PRESSED, keyHandler);
            scene.addEventFilter(KeyEvent.KEY_RELEASED, keyHandler);
            scene.addEventHandler(MouseEvent.MOUSE_EXITED, exitHandler);
            scene.windowProperty().addListener(windowListener);
        }
    }

    private void observeWindow(Window window) {
        resetEyes();
        if (observedWindow != null) {
            observedWindow.showingProperty().removeListener(visibilityListener);
            observedWindow.focusedProperty().removeListener(visibilityListener);
            if (observedWindow instanceof Stage stage) {
                stage.iconifiedProperty().removeListener(visibilityListener);
            }
        }
        observedWindow = window;
        if (window != null) {
            window.showingProperty().addListener(visibilityListener);
            window.focusedProperty().addListener(visibilityListener);
            if (window instanceof Stage stage) {
                stage.iconifiedProperty().addListener(visibilityListener);
            }
        }
        updateAnimationState();
    }

    private void updateAnimationState() {
        if (!canAnimate()) {
            resetEyes();
            return;
        }
        for (BlinkAnimation blink : blinkAnimations.values()) {
            blink.schedule();
        }
    }

    private boolean canAnimate() {
        if (disposed || observedWindow == null || !observedWindow.isShowing()
                || !observedWindow.isFocused() || !artwork.isVisible()
                || (observedWindow instanceof Stage stage && stage.isIconified())) {
            return false;
        }
        for (Node node = this; node != null; node = node.getParent()) {
            if (!node.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private void requestMotion() {
        if (!running && canAnimate()) {
            lastPulse = 0;
            running = true;
            motion.start();
        }
    }

    private void stopMotion() {
        motion.stop();
        running = false;
        lastPulse = 0;
    }

    private void resetEyes() {
        stopMotion();
        pointer = null;
        lastBounce = 0;
        pressedKeys.clear();
        for (BounceAnimation bounce : bounceAnimations) {
            bounce.stop();
        }
        for (BlinkAnimation blink : blinkAnimations.values()) {
            blink.stop();
        }
        for (Eye eye : eyes) {
            eye.pupil.setTranslateX(0);
            eye.pupil.setTranslateY(0);
        }
    }

    void dispose() {
        disposed = true;
        sceneProperty().removeListener(sceneListener);
        visibleProperty().removeListener(visibilityListener);
        localToSceneTransformProperty().removeListener(positionListener);
        observeScene(null);
        for (BounceAnimation bounce : bounceAnimations) {
            bounce.character.translateYProperty().removeListener(bouncePositionListener);
        }
    }

    private void createCharacters() {
        // Paths and eye coordinates from the separately exported Star/Ghost/Cyclops/Cactus/Crowned SVGs.
        Group star = character("Star", 0, 24);
        star.getChildren().add(path("M91 18l20 44 47 4-36 31 11 46-42-25-42 25 11-46-36-31 47-4z", Color.web("#f5cf63")));
        addEye(star, 78, 82, 14, 6, 5, true);
        addEye(star, 107, 82, 14, 6, 5, true);
        star.getChildren().add(path("M78 112q14 12 29 0", null));

        Group ghost = character("Ghost", 115, 12);
        ghost.getChildren().add(path("M42 145V71c0-28 19-47 48-47s48 19 48 47v74l-16-12-16 12-16-12-16 12-16-12z", SKY));
        addEye(ghost, 72, 72, 13, 6, 4.8, false);
        addEye(ghost, 109, 72, 13, 6, 4.8, false);
        ghost.getChildren().add(path("M76 106q14 12 29 0", null));

        Group cyclops = character("Cyclops", 225, 2);
        cyclops.getChildren().add(path("M55 149V61c0-27 15-42 36-42s36 15 36 42v88l-18-14-18 14-18-14z", SKY));
        addEye(cyclops, 91, 75, 23, 9, 7, false);
        cyclops.getChildren().add(path("M73 112q18 12 36 0", null));

        Group cactus = character("Cactus", 305, 22);
        cactus.getChildren().addAll(
                path("M72 154V73c0-24 11-38 28-38s28 14 28 38v81z", Color.web("#93c572")),
                path("M74 101H50c-14 0-23-9-23-23V61m101 46h24c14 0 23-9 23-23V67", null));
        addEye(cactus, 88, 88, 11, 5, 4.2, false);
        addEye(cactus, 114, 88, 11, 5, 4.2, false);
        cactus.getChildren().add(path("M89 119q12 9 25 0", null));

        Group crowned = character("Crowned", 405, 14);
        crowned.getChildren().addAll(
                path("M45 66l13-37 24 25 20-32 18 34 28-23-6 42", Color.web("#f08a7a")),
                path("M43 86c0-29 18-45 48-45s48 16 48 45v59H43z", Color.WHITE));
        addEye(crowned, 73, 88, 12, 5.5, 4.7, false);
        addEye(crowned, 109, 88, 12, 5.5, 4.7, false);
        crowned.getChildren().add(path("M79 119q13 8 26 0", null));
        // Overlapping arms stay behind the neighboring faces.
        cactus.toBack();
    }

    private Group character(String name, double x, double y) {
        Group character = new Group();
        character.setId("companion-" + name.toLowerCase(java.util.Locale.ROOT));
        character.setLayoutX(x);
        character.setLayoutY(y);
        blinkAnimations.put(character, new BlinkAnimation());
        bounceAnimations.add(new BounceAnimation(character));
        artwork.getChildren().add(character);
        return character;
    }

    private static SVGPath path(String data, Color fill) {
        SVGPath path = new SVGPath();
        path.setContent(data);
        path.setFill(fill);
        path.setStroke(INK);
        path.setStrokeWidth(6);
        path.setStrokeLineCap(StrokeLineCap.ROUND);
        path.setStrokeLineJoin(StrokeLineJoin.ROUND);
        return path;
    }

    private void addEye(Group character, double x, double y, double radius,
                        double pupilRadius, double range, boolean shine) {
        Circle white = new Circle(radius, Color.WHITE);
        white.setStroke(INK);
        white.setStrokeWidth(5);
        Group pupil = new Group(new Circle(pupilRadius, INK));
        if (shine) {
            Circle highlight = new Circle(-3, -3, 2.2, Color.WHITE);
            highlight.setOpacity(0.85);
            pupil.getChildren().add(highlight);
        }
        Group appearance = new Group(white, pupil);
        Scale eyelidScale = new Scale(1, 1, 0, 0);
        eyelidScale.yProperty().bind(blinkAnimations.get(character).openness);
        appearance.getTransforms().add(eyelidScale);
        // Keep the gaze coordinate system independent from the eyelid's vertical compression.
        Group eye = new Group(appearance);
        eye.setLayoutX(x);
        eye.setLayoutY(y);
        character.getChildren().add(eye);
        // Keep the entire pupil inside the inner edge of the eye's outline.
        eyes.add(new Eye(eye, pupil, Math.min(range, radius - 2.5 - pupilRadius)));
    }

    private final class BounceAnimation {
        private final Group character;
        private final Timeline jump = new Timeline();

        private BounceAnimation(Group character) {
            this.character = character;
            character.translateYProperty().addListener(bouncePositionListener);
        }

        private boolean play() {
            double scale = artworkScale.getY();
            if (scale <= 0) {
                return false;
            }
            // Work in artwork coordinates and keep a two-pixel margin above the character.
            double headroom = (artwork.getLayoutY() - 2) / scale
                    + character.getLayoutY() + character.getBoundsInLocal().getMinY();
            double height = Math.min(ThreadLocalRandom.current().nextDouble(18, 42), headroom);
            if (height * scale < 1) {
                return false;
            }
            double millis = ThreadLocalRandom.current().nextDouble(300, 420);
            jump.getKeyFrames().setAll(
                    new KeyFrame(Duration.ZERO, new KeyValue(character.translateYProperty(), 0)),
                    new KeyFrame(Duration.millis(millis * 0.45),
                            new KeyValue(character.translateYProperty(), -height, Interpolator.EASE_OUT)),
                    new KeyFrame(Duration.millis(millis),
                            new KeyValue(character.translateYProperty(), 0, Interpolator.EASE_IN)));
            jump.playFromStart();
            return true;
        }

        private void stop() {
            jump.stop();
            character.setTranslateY(0);
        }
    }

    private final class BlinkAnimation {
        private final DoubleProperty openness = new SimpleDoubleProperty(1);
        private final PauseTransition wait = new PauseTransition();
        private final Timeline blink = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(openness, 1)),
                new KeyFrame(Duration.millis(65), new KeyValue(openness, 0.08, Interpolator.EASE_IN)),
                new KeyFrame(Duration.millis(100), new KeyValue(openness, 0.08)),
                new KeyFrame(Duration.millis(205), new KeyValue(openness, 1, Interpolator.EASE_OUT)));

        private BlinkAnimation() {
            wait.setOnFinished(event -> {
                if (canAnimate()) {
                    blink.playFromStart();
                } else {
                    stop();
                }
            });
            blink.setOnFinished(event -> schedule());
        }

        private void schedule() {
            if (!canAnimate()) {
                stop();
                return;
            }
            if (wait.getStatus() != Animation.Status.STOPPED || blink.getStatus() != Animation.Status.STOPPED) {
                return;
            }
            wait.setDuration(Duration.seconds(ThreadLocalRandom.current().nextDouble(3, 7)));
            wait.playFromStart();
        }

        private void stop() {
            wait.stop();
            blink.stop();
            openness.set(1);
        }
    }

    private record Eye(Group origin, Group pupil, double range) {
        boolean follow(Point2D pointer, double blend) {
            Point2D local = pointer == null ? Point2D.ZERO : origin.sceneToLocal(pointer);
            if (local == null) {
                local = Point2D.ZERO;
            }
            double factor = range / Math.max(45, local.magnitude());
            double targetX = local.getX() * factor;
            double targetY = local.getY() * factor;
            double dx = targetX - pupil.getTranslateX();
            double dy = targetY - pupil.getTranslateY();
            boolean settled = Math.hypot(dx, dy) < 0.015;
            pupil.setTranslateX(settled ? targetX : pupil.getTranslateX() + dx * blend);
            pupil.setTranslateY(settled ? targetY : pupil.getTranslateY() + dy * blend);
            return settled;
        }
    }
}
