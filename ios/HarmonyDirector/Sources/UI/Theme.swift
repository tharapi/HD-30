import SwiftUI

enum HDTheme {
    // Studio Hardware Chassis Palette (matched 1:1 with Android Color.kt)
    static let studioCharcoal = Color(hex: 0x111418)
    static let anodizedSlate = Color(hex: 0x1A1F26)
    static let consoleSurface = Color(hex: 0x232A34)
    static let consoleSurfaceBright = Color(hex: 0x2C3542)
    static let hardwareBorder = Color(hex: 0x364152)

    // Backlit LCD & Telemetry Accents
    static let lcdMidnight = Color(hex: 0x0A1418)
    static let lcdBorder = Color(hex: 0x1D3540)
    static let electricSky = Color(hex: 0x38BDF8)
    static let electricSkyContainer = Color(hex: 0x0E3A52)
    static let warmAmber = Color(hex: 0xF59E0B)
    static let warmAmberContainer = Color(hex: 0x452A06)
    static let pitchEmerald = Color(hex: 0x10B981)
    static let downbeatCrimson = Color(hex: 0xEF4444)

    // Text & Key Surfaces
    static let crispOffWhite = Color(hex: 0xF1F5F9)
    static let mutedSlateText = Color(hex: 0x94A3B8)
    static let ivoryKey = Color(hex: 0xF8FAFC)
    static let ivoryKeyPressed = Color(hex: 0xBAE6FD)
    static let ebonyKey = Color(hex: 0x18202C)
    static let ebonyKeyPressed = Color(hex: 0x0284C7)
}

extension Color {
    init(hex: UInt32, alpha: Double = 1.0) {
        let r = Double((hex >> 16) & 0xFF) / 255.0
        let g = Double((hex >> 8) & 0xFF) / 255.0
        let b = Double(hex & 0xFF) / 255.0
        self.init(.sRGB, red: r, green: g, blue: b, opacity: alpha)
    }
}
