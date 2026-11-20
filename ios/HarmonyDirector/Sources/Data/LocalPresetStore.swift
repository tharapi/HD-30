import Foundation

/// Offline on-device persistence for HD-300 rehearsal presets (equivalent to Android's LocalFallbackPresetRepository).
final class LocalPresetStore {
    private static let storageKey = "harmony_director_local_presets_v1"
    private let defaults: UserDefaults
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func loadPresets() -> [RehearsalPreset] {
        guard let data = defaults.data(forKey: Self.storageKey),
              let decoded = try? decoder.decode([RehearsalPreset].self, from: data) else {
            return []
        }
        return decoded.sorted { $0.updatedAt > $1.updatedAt }
    }

    @discardableResult
    func savePresets(_ presets: [RehearsalPreset]) -> [RehearsalPreset] {
        let sorted = presets.sorted { $0.updatedAt > $1.updatedAt }
        if let data = try? encoder.encode(sorted) {
            defaults.set(data, forKey: Self.storageKey)
        }
        return sorted
    }
}
