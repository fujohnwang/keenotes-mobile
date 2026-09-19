import SwiftUI
import StoreKit

struct SubscriptionView: View {
    @ObservedObject var service: StoreKitPurchaseService
    @ObservedObject var draft: SettingsDraft
    let onFilled: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var visible = false
    @State private var operation = UUID()

    var body: some View {
        NavigationView {
            List {
                Section {
                    Text("KeeNotes Cloud Sync").font(.title2.bold()).accessibilityIdentifier("subscriptionHeading")
                    Text("Your subscription provides separate sync service credentials. Choose an encryption password and use the same password on every device.")
                    Text("You can still enter an endpoint, token, and encryption password from another service in Settings.")
                        .font(.footnote).foregroundColor(.secondary)
                }
                Section(header: Text("Annual Subscription")) {
                    if let product = service.product {
                        Text(product.name).accessibilityIdentifier("subscriptionProduct")
                        Text(String(format: NSLocalizedString("%@ / year", comment: "IAP and connection configuration"), product.displayPrice)).font(.headline).accessibilityIdentifier("subscriptionPrice")
                        Button("Subscribe for a Year") { perform { await service.purchase() } }
                            .disabled(service.isBusy)
                            .accessibilityIdentifier("subscriptionPurchase")
                        Text("Renews automatically. Manage or cancel in the App Store. Payment is handled by your Apple Account.")
                            .font(.footnote).foregroundColor(.secondary)
                    } else {
                        Text("Annual subscription is unavailable")
                        Button("Reload Product") { Task { await service.loadProducts() } }.disabled(service.isBusy).accessibilityIdentifier("subscriptionReload")
                    }
                    if service.isBusy { ProgressView() }
                    if !service.message.isEmpty { Text(service.message).font(.footnote).accessibilityIdentifier("subscriptionStatus") }
                }
                Section {
                    Button("Restore Purchases") { perform { await service.restore() } }.disabled(service.isBusy).accessibilityIdentifier("subscriptionRestore")
                    Button("Retry Delivery") { perform { await service.retryDelivery() } }.disabled(service.isBusy).accessibilityIdentifier("subscriptionRetry")
                    Button("Manage Subscription") {
                        Task {
                            if let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first(where: { $0.activationState == .foregroundActive }) {
                                try? await AppStore.showManageSubscriptions(in: scene)
                            }
                        }
                    }
                    .accessibilityIdentifier("subscriptionManage")
                    if let credentials = service.purchased {
                        Button("Fill Connection Credentials") { fill(credentials, revision: draft.revision) }.accessibilityIdentifier("subscriptionFill")
                        Text("\(credentials.endpoint) · …\(credentials.token.suffix(4))").font(.caption).foregroundColor(.secondary)
                        Text("Your encryption password is preserved. Tap Save Settings after filling the credentials to use them.")
                            .font(.footnote).foregroundColor(.secondary)
                    }
                }
                Section {
                    if let url = service.configuration.termsURL { Link("Terms of Use", destination: url).accessibilityIdentifier("subscriptionTerms") }
                    if let url = service.configuration.privacyURL { Link("Privacy Policy", destination: url).accessibilityIdentifier("subscriptionPrivacy") }
                }
            }
            .navigationTitle("Subscription").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() }.accessibilityIdentifier("subscriptionDone") } }
        }
        .navigationViewStyle(.stack)
        .onAppear { visible = true }
        .onDisappear { visible = false; operation = UUID(); draft.invalidateDelivery() }
        .task { await service.loadProducts() }
    }

    private func perform(_ action: @escaping () async -> PurchasedCredentials?) {
        let revision = draft.revision
        let id = UUID()
        operation = id
        Task {
            let credentials = await action()
            guard visible, operation == id, let credentials else { return }
            fill(credentials, revision: revision)
        }
    }
    private func fill(_ credentials: PurchasedCredentials, revision: Int) {
        if draft.fill(endpoint: credentials.endpoint, token: credentials.token, ifRevision: revision) { onFilled() }
    }
}
