# keenotes图片海报生成视频功能需求调研


## 调研问题一

iOS里有什么框架可以实现类似如下命令的效果吗？

ffmpeg -loop 1 -i "Weixin Image_20260514162233_131_3.png" -i "/Users/fq/Music/hero3死亡阴影mp3/Win Battle.mp3" -vf "split[original][copy];[copy]scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920,boxblur=20:20[blurred];[original]scale=1080:1920:force_original_aspect_ratio=decrease[scaled];[blurred][scaled]overlay=(W-w)/2:(H-h)/2" -c:v libx264 -tune stillimage -c:a aac -b:a 192k -pix_fmt yuv420p -shortest output3.mp4


---

你所提供的这个 FFmpeg 命令实现了一个在短视频领域（如抖音、Reels）非常经典的特效：**“将一张单图与一段音频合成视频，由于图片比例不符合 1080x1920，底层用放大并模糊的图片做背景，上层居中显示原图，视频总时长与音频一致。”**

在 iOS 开发中，要实现一模一样的效果，主要有两种主流的选择：一种是使用 **原生的 iOS 框架组合**，另一种是直接引入 **FFmpeg 的 iOS 封装库**。

以下是详细的方案对比和实现思路：

---

### 方案一：直接使用 FFmpeg 的 iOS 包装库（最省时、直接）

既然你已经写出了完美的 FFmpeg 命令，最简单的办法就是直接在 iOS 里跑这行命令。

**推荐框架：`FFmpegKit` (前身是 `MobileFFmpeg`)**
*   **GitHub**: [https://github.com/arthenica/ffmpeg-kit](https://github.com/arthenica/ffmpeg-kit)
*   **特点**：几乎支持所有命令行参数，直接把你的命令作为字符串传进去就能运行。

**Swift 实现示例：**
```swift
import FFmpegKit

// 你的输入路径和输出路径
let imagePath = "Weixin Image_20260514162233_131_3.png"
let audioPath = "Win Battle.mp3"
let outputPath = NSTemporaryDirectory() + "output3.mp4"

// 注意：在 iOS 中，推荐将 -c:v libx264 替换为 -c:v h264_videotoolbox
// 因为 libx264 涉及 GPL 开源协议问题，而 h264_videotoolbox 是调用苹果系统底层的硬件编码，速度更快且免去版权烦恼。

let command = """
-loop 1 -i "\(imagePath)" \
-i "\(audioPath)" \
-vf "split[original][copy];[copy]scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920,boxblur=20:20[blurred];[original]scale=1080:1920:force_original_aspect_ratio=decrease[scaled];[blurred][scaled]overlay=(W-w)/2:(H-h)/2" \
-c:v h264_videotoolbox -c:a aac -b:a 192k -pix_fmt yuv420p -shortest "\(outputPath)"
"""

// 异步执行命令
FFmpegKit.executeAsync(command) { session in
    guard let session = session else { return }
    let returnCode = session.getReturnCode()
    
    if ReturnCode.isSuccess(returnCode) {
        print("合成成功！视频路径: \(outputPath)")
    } else {
        print("合成失败，错误日志: \(session.getFailStackTrace() ?? "未知错误")")
    }
}
```

**⚠️ 注意事项**：
* 如果坚持用 `libx264`，你需要引入 `ffmpeg-kit-ios-gpl` 版本。
* 如果改成苹果原生的硬件加速 `h264_videotoolbox`，引入普通的 `ffmpeg-kit-ios-min` 版本即可，可大大减小 App 安装包体积，并避免 GPL 传染协议。

---

### 方案二：使用 iOS 原生框架（纯原生、性能最高、包体积小）

如果不希望引入体积庞大（几十MB）的 FFmpeg 库，你可以使用苹果自家的多媒体框架：**`AVFoundation` + `CoreImage` + `CoreMedia`**。

这个方案虽然不需要外部依赖，但**代码量较大**（需要手写几百行代码）。它的底层逻辑与 FFmpeg 完全一致：

1. **`CoreImage` (对应 `-vf` 滤镜部分)**
   负责用图片生成你想要的 1080x1920 的帧画面：
   * 用 `CIAffineTransform` 和 `CILanczosScaleTransform` 对图片进行缩放（对应 scale）。
   * 用 `CIGaussianBlur` 或 `CIBoxBlur` 进行模糊处理（对应 boxblur）。
   * 用 `CISourceOverCompositing` 把清晰的原图叠加在模糊背景上（对应 overlay）。
   * 最终把处理好的 `CIImage` 转换为 `CVPixelBuffer`。

2. **`AVAssetReader` (对应读取音频)**
   * 读取你的 `Win Battle.mp3`，获取它的总时长（这就是最终视频的时长，对应 `-shortest`）。
   * 将音频解码提取出 `CMSampleBuffer`。

3. **`AVAssetWriter` (对应 `output3.mp4` 和 `-c:v` 编码)**
   * 创建一个 `AVAssetWriter`。
   * 添加一个 **Audio Input**（音频输入），把从 `AVAssetReader` 读到的音频数据直接塞进去。
   * 添加一个 **Video Input**（视频输入），并配合 `AVAssetWriterInputPixelBufferAdaptor`。
   * **循环写入视频帧（对应 `-loop 1`）**：写一个循环，以 30fps（每秒30帧）的频率，根据音频的总时长，不断地把第1步生成的同一个 `CVPixelBuffer` 写入视频轨道，加上递增的时间戳（`CMTime`），直到时间达到音频的总时长为止。

**原生方案的优缺点：**
* **优点**：完全原生，使用苹果硬件加速加速（VideoToolbox API 默认集成在 AVAssetWriter 中），导出极快，耗电低，不增加任何 App 体积，无第三方协议风险。
* **缺点**：学习成本极高，需要手动控制音频流和视频流的音视频同步（Timestamp 的处理很容易出错）。

### 总结与建议

* **如果你是在做独立开发、内部分发工具，或者是为了快速实现功能上线**：直接上 **`FFmpegKit`**，并将编码器改为 `-c:v h264_videotoolbox`，这是最爽的路线。
* **如果你是在开发一款核心业务为“视频剪辑/导出”的千万级日活 App**：建议花时间用 **`AVFoundation + CoreImage`** 手写一遍，这样可以把 App 体积控制到极致，而且内存管理更可控。对于 iOS 开发者来说，理解 `AVAssetWriter` 写入循环是一个必备进阶技能。