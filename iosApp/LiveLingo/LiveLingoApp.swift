import SwiftUI
import Shared

@main
struct LiveLingoApp: App {
    // One native owner for the complete application lifetime, including the audio callbacks.
    private let native = AppleServices()

    var body: some Scene {
        WindowGroup {
            ComposeHost(native: native)
                // Shared Compose owns safeDrawing/IME insets on both platforms.
                .ignoresSafeArea()
        }
    }
}

private struct ComposeHost: UIViewControllerRepresentable {
    let native: AppleServices

    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(native: native)
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
