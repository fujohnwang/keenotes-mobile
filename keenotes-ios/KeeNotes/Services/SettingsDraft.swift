import Foundation

@MainActor
final class SettingsDraft: ObservableObject {
    @Published var endpoint = "" { didSet { revision += 1 } }
    @Published var token = "" { didSet { revision += 1 } }
    @Published var pin = "" { didSet { revision += 1 } }
    @Published var confirmPin = "" { didSet { revision += 1 } }
    private(set) var revision = 0
    private(set) var loaded = false

    var configuration: ConnectionConfiguration { ConnectionConfiguration(endpoint: endpoint, token: token, pin: pin) }
    func loadOnce(_ configuration: ConnectionConfiguration) {
        guard !loaded else { return }
        replace(configuration)
    }
    func replace(_ configuration: ConnectionConfiguration) {
        loaded = true
        endpoint = configuration.endpoint; token = configuration.token
        pin = configuration.pin; confirmPin = configuration.pin
    }
    /// Only an active, explicit UI operation may call this; background delivery never does.
    @discardableResult
    func fill(endpoint: String, token: String, ifRevision expected: Int) -> Bool {
        guard expected == revision else { return false }
        self.endpoint = endpoint; self.token = token
        return true
    }
    func invalidateDelivery() { revision += 1 }
}
