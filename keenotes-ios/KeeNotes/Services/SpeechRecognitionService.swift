import Foundation
import Speech
import AVFoundation
import UIKit

/// Speech recognition service with silence detection and continuous mode.
/// After detecting a pause in speech (~1.5s), auto-commits recognized text
/// to the editor and restarts listening.
@MainActor
class SpeechRecognitionService: ObservableObject {
    @Published var isRecording = false
    @Published var isAuthorized = false
    @Published var errorMessage: String?
    @Published var partialText: String?
    
    private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest?
    private var recognitionTask: SFSpeechRecognitionTask?
    private var audioEngine: AVAudioEngine?
    
    /// Incremented on each new recognition session to ignore stale callbacks
    private var sessionId = 0
    
    var onPartialResult: ((String) -> Void)?
    var onFinalResult: ((String) -> Void)?
    
    private var lastRecognizedText = ""
    private var silenceTimer: Timer?
    private let silenceThreshold: TimeInterval = 1.0
    private var continuousMode = false
    
    var currentLocale: Locale?
    
    // MARK: - Authorization
    
    func requestAuthorization() {
        SFSpeechRecognizer.requestAuthorization { [weak self] status in
            DispatchQueue.main.async {
                switch status {
                case .authorized:
                    self?.isAuthorized = true
                default:
                    self?.isAuthorized = false
                    self?.errorMessage = "Speech recognition not authorized"
                }
            }
        }
    }
    
    // MARK: - Speech recognizer factory
    
    private func makeSpeechRecognizer() -> SFSpeechRecognizer? {
        if let locale = currentLocale,
           let recognizer = SFSpeechRecognizer(locale: locale),
           recognizer.isAvailable {
            return recognizer
        }
        return SFSpeechRecognizer()
    }

    // MARK: - Silence detection
    
    private func resetSilenceTimer() {
        silenceTimer?.invalidate()
        silenceTimer = Timer.scheduledTimer(withTimeInterval: silenceThreshold, repeats: false) { [weak self] _ in
            DispatchQueue.main.async {
                self?.onSilenceDetected()
            }
        }
    }
    
    private func onSilenceDetected() {
        guard isRecording, !lastRecognizedText.isEmpty else { return }
        commitPendingText()
        if continuousMode {
            // Tear down everything and start fresh
            tearDownAll()
            startAudioAndRecognition()
        }
    }
    
    private func commitPendingText() {
        silenceTimer?.invalidate()
        guard !lastRecognizedText.isEmpty else { return }
        let text = lastRecognizedText
        lastRecognizedText = ""
        partialText = nil
        onFinalResult?(text)
    }
    
    // MARK: - Teardown (safe to call multiple times)
    
    private func tearDownAll() {
        sessionId += 1
        silenceTimer?.invalidate()
        silenceTimer = nil
        
        recognitionRequest?.endAudio()
        recognitionTask?.cancel()
        recognitionRequest = nil
        recognitionTask = nil
        
        if let engine = audioEngine {
            if engine.isRunning {
                engine.stop()
            }
            engine.inputNode.removeTap(onBus: 0)
        }
        audioEngine = nil
    }

    // MARK: - Start audio engine + recognition task as one unit
    
    /// Creates a fresh audio engine, starts it, and begins a recognition task.
    /// Returns false if setup fails.
    @discardableResult
    private func startAudioAndRecognition() -> Bool {
        guard let speechRecognizer = makeSpeechRecognizer(), speechRecognizer.isAvailable else {
            errorMessage = "Speech recognizer not available"
            return false
        }
        
        // Fresh audio engine every time
        let engine = AVAudioEngine()
        self.audioEngine = engine
        
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        self.recognitionRequest = request
        
        let mySession = sessionId
        
        recognitionTask = speechRecognizer.recognitionTask(with: request) { [weak self] result, error in
            DispatchQueue.main.async {
                guard let self = self, self.sessionId == mySession else { return }
                self.handleCallback(result: result, error: error)
            }
        }
        
        let inputNode = engine.inputNode
        let format = inputNode.outputFormat(forBus: 0)
        inputNode.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in
            request.append(buffer)
        }
        
        do {
            engine.prepare()
            try engine.start()
            return true
        } catch {
            errorMessage = "Audio engine failed to start"
            tearDownAll()
            return false
        }
    }
    
    private func handleCallback(result: SFSpeechRecognitionResult?, error: Error?) {
        if let result = result {
            let text = result.bestTranscription.formattedString
            if !text.isEmpty {
                lastRecognizedText = text
                resetSilenceTimer()
            }
            partialText = text
            onPartialResult?(text)
            
            if result.isFinal {
                commitPendingText()
                if continuousMode {
                    tearDownAll()
                    startAudioAndRecognition()
                }
            }
        }
        
        if error != nil {
            commitPendingText()
            if continuousMode && isRecording {
                tearDownAll()
                startAudioAndRecognition()
            } else {
                shutdown()
            }
        }
    }

    // MARK: - Full shutdown
    
    private func shutdown() {
        commitPendingText()
        tearDownAll()
        isRecording = false
        continuousMode = false
        partialText = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
    
    // MARK: - Public API
    
    func startRecording() {
        guard !isRecording else { return }
        
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .measurement, options: .duckOthers)
            try session.setActive(true, options: .notifyOthersOnDeactivation)
        } catch {
            errorMessage = "Audio session setup failed"
            return
        }
        
        continuousMode = true
        isRecording = true
        lastRecognizedText = ""
        partialText = nil
        errorMessage = nil
        
        if !startAudioAndRecognition() {
            isRecording = false
            continuousMode = false
        }
    }
    
    func stopRecording() {
        guard isRecording else { return }
        continuousMode = false
        shutdown()
    }
    
    func toggleRecording() {
        if isRecording {
            stopRecording()
        } else {
            startRecordingWithAuthCheck()
        }
    }
    
    func startRecordingWithAuthCheck() {
        if isAuthorized {
            startRecording()
        } else {
            SFSpeechRecognizer.requestAuthorization { [weak self] status in
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    switch status {
                    case .authorized:
                        self.isAuthorized = true
                        self.startRecording()
                    default:
                        self.isAuthorized = false
                        self.errorMessage = "Speech recognition not authorized"
                    }
                }
            }
        }
    }
}
