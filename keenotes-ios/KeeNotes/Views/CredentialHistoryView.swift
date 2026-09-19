import SwiftUI

struct CredentialHistoryView: View {
    var onActivated: () -> Void = {}
    @EnvironmentObject var appState: AppState
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme
    @State private var selected: CredentialHistoryEntry?
    @State private var errorMessage = ""
    @State private var applying = false

    var body: some View {
        NavigationView {
            List {
                if appState.settingsService.history.isEmpty { Text("No complete configurations saved yet").accessibilityIdentifier("historyEmpty") }
                ForEach(appState.settingsService.history) { entry in
                    let isCurrent = entry.configuration == appState.settingsService.configuration
                    let tint = sourceColor(entry.source)
                    Button { selected = entry } label: {
                        HStack(spacing: 12) {
                            Image(systemName: entry.source == .iap ? "creditcard" : "key")
                                .font(.title3.weight(.medium)).foregroundColor(tint)
                                .frame(width: 36, height: 36)
                                .background(tint.opacity(0.10), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                                .accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: 7) {
                                Text(entry.configuration.endpoint)
                                    .font(.subheadline.weight(.semibold)).foregroundColor(.primary)
                                    .lineLimit(1).truncationMode(.middle)
                                    .accessibilityLabel(entry.configuration.endpoint)
                                HStack(spacing: 8) {
                                    Text("…\(entry.configuration.tokenSuffix)")
                                        .font(.system(.caption, design: .monospaced)).foregroundColor(.secondary)
                                    Text(entry.source.title).font(.caption.weight(.medium)).foregroundColor(tint)
                                        .padding(.horizontal, 8).padding(.vertical, 3)
                                        .background(tint.opacity(colorScheme == .dark ? 0.18 : 0.10), in: Capsule())
                                        .accessibilityIdentifier("credentialSourceTag")
                                }
                            }.frame(maxWidth: .infinity, alignment: .leading)
                            if isCurrent {
                                Image(systemName: "checkmark.circle.fill")
                                    .font(.body.weight(.semibold)).foregroundColor(.blue)
                                    .accessibilityLabel(Text("Currently in use"))
                                    .accessibilityIdentifier("historyCurrent")
                            } else {
                                Image(systemName: "chevron.right")
                                    .font(.caption.weight(.semibold)).foregroundColor(.secondary.opacity(0.6))
                                    .accessibilityHidden(true)
                            }
                        }
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        .padding(.vertical, 8).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(isCurrent ? .isSelected : [])
                    .disabled(applying)
                }.onDelete { offsets in
                    let entries = appState.settingsService.history
                    do {
                        for index in offsets { try appState.settingsService.deleteHistory(id: entries[index].id) }
                    } catch { errorMessage = error.localizedDescription }
                }.deleteDisabled(applying)
                if !errorMessage.isEmpty { Text(errorMessage).foregroundColor(.red) }
            }
            .navigationTitle("Credential History").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() }.disabled(applying).accessibilityIdentifier("historyDone") } }
            .alert(item: $selected) { entry in
                let changesSpace = entry.configuration.endpoint != appState.settingsService.endpointUrl || entry.configuration.token != appState.settingsService.token
                return Alert(title: Text("Switch configuration?"),
                             message: Text("\(entry.configuration.endpoint)\n…\(entry.configuration.tokenSuffix) · \(entry.source.title)\n" + NSLocalizedString("Replace the complete connection configuration and reconnect.", comment: "IAP and connection configuration") + (changesSpace ? " " + NSLocalizedString("Synced notes cached on this device will be reloaded.", comment: "IAP and connection configuration") : "")),
                             primaryButton: .default(Text("Switch")) { activate(entry) },
                             secondaryButton: .cancel(Text("Cancel")))
            }
        }.navigationViewStyle(.stack).interactiveDismissDisabled(applying)
    }

    private func sourceColor(_ source: CredentialSource) -> Color {
        if source == .iap {
            return colorScheme == .dark ? Color(red: 1.0, green: 0.68, blue: 0.28) : Color(red: 0.60, green: 0.28, blue: 0.04)
        }
        return colorScheme == .dark ? Color(red: 0.40, green: 0.67, blue: 1.0) : Theme.brandColor
    }

    private func activate(_ entry: CredentialHistoryEntry) {
        guard !applying,
              appState.settingsService.history.contains(where: { $0.id == entry.id && $0.configuration == entry.configuration }) else { return }
        applying = true
        appState.settingsDraft.invalidateDelivery()
        Task {
            defer { applying = false }
            do {
                try await appState.configurationCoordinator.apply(entry.configuration, source: entry.source)
                appState.settingsDraft.replace(entry.configuration)
                onActivated()
                dismiss()
            } catch { errorMessage = error.localizedDescription }
        }
    }
}
