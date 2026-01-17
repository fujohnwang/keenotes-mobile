import SwiftUI

/// Device type detection helper for adaptive layout
enum DeviceType {
    case phone
    case pad

    /// Returns the current device type
    static var current: DeviceType {
        #if os(iOS)
        return UIDevice.current.userInterfaceIdiom == .pad ? .pad : .phone
        #else
        return .phone
        #endif
    }

    /// Check if running on iPad
    static var isPad: Bool {
        current == .pad
    }

    /// Check if running on iPhone
    static var isPhone: Bool {
        current == .phone
    }

    /// Get appropriate horizontal padding based on device
    static var horizontalPadding: CGFloat {
        isPad ? 32 : 16
    }

    /// Get appropriate corner radius based on device
    static var cornerRadius: CGFloat {
        isPad ? 16 : 12
    }

    /// Get appropriate font scale for larger screens
    static var fontScale: CGFloat {
        isPad ? 1.2 : 1.0
    }
}
