import SwiftUI

private enum ConsoleTab: Int {
    case harmony
    case metronome
    case presets
}

struct HarmonyDirectorRootView: View {
    @StateObject private var viewModel = ConsoleViewModel()
    @State private var selectedTab: ConsoleTab = .harmony
    @State private var showMidiSheet = false
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    var body: some View {
        ZStack(alignment: .bottom) {
            VStack(spacing: 0) {
                ConsoleLcdHeaderView(
                    viewModel: viewModel,
                    onOpenMidi: { showMidiSheet = true }
                )

                Divider()
                    .background(HDTheme.hardwareBorder)

                if horizontalSizeClass == .regular {
                    // iPad / Wide layout: Dual console + Presets tab
                    TabView(selection: $selectedTab) {
                        HStack(spacing: 0) {
                            HarmonyPanelView(viewModel: viewModel)
                            Divider().background(HDTheme.hardwareBorder)
                            MetronomePanelView(viewModel: viewModel)
                                .frame(maxWidth: 420)
                        }
                        .tabItem {
                            Label("Console", systemImage: "pianokeys")
                        }
                        .tag(ConsoleTab.harmony)

                        PresetsPanelView(viewModel: viewModel)
                            .tabItem {
                                Label("Presets", systemImage: "music.note.list")
                            }
                            .tag(ConsoleTab.presets)
                    }
                    .tint(HDTheme.electricSky)
                } else {
                    // iPhone compact layout: 3 tabs matching Android NavigationBar
                    TabView(selection: $selectedTab) {
                        HarmonyPanelView(viewModel: viewModel)
                            .tabItem {
                                Label("Harmony", systemImage: "pianokeys")
                            }
                            .tag(ConsoleTab.harmony)

                        MetronomePanelView(viewModel: viewModel)
                            .tabItem {
                                Label("Metronome", systemImage: "metronome")
                            }
                            .tag(ConsoleTab.metronome)

                        PresetsPanelView(viewModel: viewModel)
                            .tabItem {
                                Label("Presets", systemImage: "music.note.list")
                            }
                            .tag(ConsoleTab.presets)
                    }
                    .tint(HDTheme.electricSky)
                }
            }
            .background(HDTheme.studioCharcoal.ignoresSafeArea())

            if let toast = viewModel.toastMessage {
                Text(toast)
                    .font(.system(.caption, design: .monospaced).weight(.semibold))
                    .foregroundColor(HDTheme.crispOffWhite)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 9)
                    .background(HDTheme.consoleSurfaceBright.opacity(0.95))
                    .overlay(
                        RoundedRectangle(cornerRadius: 10)
                            .stroke(HDTheme.electricSky, lineWidth: 1)
                    )
                    .cornerRadius(10)
                    .padding(.bottom, 64)
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
            }
        }
        .animation(.easeInOut(duration: 0.2), value: viewModel.toastMessage)
        .sheet(isPresented: $showMidiSheet) {
            MidiConnectionSheetView(viewModel: viewModel)
        }
        .preferredColorScheme(.dark)
    }
}
