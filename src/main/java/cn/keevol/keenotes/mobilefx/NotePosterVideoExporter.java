package cn.keevol.keenotes.mobilefx;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

public final class NotePosterVideoExporter {
    private static final String[] BGM_RESOURCE_PATHS = {
            "/poster/bgm/LoopLepr.mp3",
            "/poster/bgm/Win Battle.mp3",
            "/poster/bgm/Defend Castle.mp3"
    };

    private NotePosterVideoExporter() {
    }

    public static Optional<Path> findFfmpeg() {
        String[] candidates = {
                "/opt/homebrew/bin/ffmpeg",
                "/usr/local/bin/ffmpeg",
                "/usr/bin/ffmpeg"
        };
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.isExecutable(path)) {
                return Optional.of(path);
            }
        }

        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return Optional.empty();
        }

        String[] pathParts = pathEnv.split(File.pathSeparator);
        for (String pathPart : pathParts) {
            if (pathPart == null || pathPart.isBlank()) {
                continue;
            }
            Path candidate = Path.of(pathPart, executableName());
            if (Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    public static boolean isFfmpegAvailable() {
        return findFfmpeg().isPresent();
    }

    public static void exportVideo(BufferedImage posterImage, File outputFile) throws IOException, InterruptedException {
        Path ffmpeg = findFfmpeg()
                .orElseThrow(() -> new IOException("未找到 ffmpeg，视频生成不可用"));

        Path posterPath = Files.createTempFile("keenotes-poster-frame-", ".png");
        Path bgmPath = Files.createTempFile("keenotes-poster-bgm-", ".mp3");
        try {
            ImageIO.write(posterImage, "png", posterPath.toFile());
            copyRandomBgmTo(bgmPath);
            runFfmpeg(ffmpeg, posterPath, bgmPath, outputFile.toPath());
        } finally {
            Files.deleteIfExists(posterPath);
            Files.deleteIfExists(bgmPath);
        }
    }

    private static void copyRandomBgmTo(Path targetPath) throws IOException {
        String resourcePath = BGM_RESOURCE_PATHS[ThreadLocalRandom.current().nextInt(BGM_RESOURCE_PATHS.length)];
        try (InputStream input = NotePosterVideoExporter.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IOException("BGM resource missing: " + resourcePath);
            }
            Files.copy(input, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void runFfmpeg(Path ffmpeg, Path posterPath, Path bgmPath, Path outputPath) throws IOException, InterruptedException {
        Files.deleteIfExists(outputPath);
        String filter = "split[original][copy];"
                + "[copy]scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920,boxblur=20:20[blurred];"
                + "[original]scale=1080:1920:force_original_aspect_ratio=decrease[scaled];"
                + "[blurred][scaled]overlay=(W-w)/2:(H-h)/2";

        List<String> command = new ArrayList<>();
        command.add(ffmpeg.toString());
        command.add("-y");
        command.add("-loop");
        command.add("1");
        command.add("-i");
        command.add(posterPath.toString());
        command.add("-i");
        command.add(bgmPath.toString());
        command.add("-vf");
        command.add(filter);
        command.add("-c:v");
        command.add("libx264");
        command.add("-tune");
        command.add("stillimage");
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("192k");
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add("-shortest");
        command.add(outputPath.toString());

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("ffmpeg failed with exit code " + exitCode + ": " + summarize(output));
        }
    }

    private static String executableName() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        return osName.contains("win") ? "ffmpeg.exe" : "ffmpeg";
    }

    private static String summarize(String output) {
        if (output == null || output.isBlank()) {
            return "no output";
        }
        String trimmed = output.trim();
        int maxLength = 700;
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(trimmed.length() - maxLength);
    }
}
