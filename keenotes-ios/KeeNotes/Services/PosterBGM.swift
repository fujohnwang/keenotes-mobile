import Foundation

enum PosterBGM {
    private static let trackNames = [
        "LoopLepr",
        "Win Battle",
        "Defend Castle",
    ]

    static func randomTrackURL() -> URL? {
        guard let name = trackNames.randomElement() else { return nil }
        if let url = Bundle.main.url(forResource: name, withExtension: "mp3", subdirectory: "PosterBGM") {
            return url
        }
        return Bundle.main.url(forResource: name, withExtension: "mp3")
    }
}
