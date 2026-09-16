import AppKit
import ApplicationServices

// Opt-in native acceptance fixture. It never activates, focuses, or injects input.
// Only its own controls and environmental metadata (not clipboard contents) are reported.
final class Fixture: NSObject {
    let target: NSWindow
    let cover: NSWindow
    let text = NSTextField(frame: NSRect(x: 20, y: 180, width: 240, height: 28))
    var count = 0

    init(title: String) {
        let bounds = NSRect(x: 40, y: 40, width: 320, height: 280)
        target = NSWindow(contentRect: bounds, styleMask: [.titled, .closable], backing: .buffered, defer: false)
        cover = NSWindow(contentRect: bounds, styleMask: [.titled], backing: .buffered, defer: false)
        super.init()
        target.isReleasedWhenClosed = false
        cover.isReleasedWhenClosed = false
        target.title = title
        cover.title = title + "-cover"
        for (window, color) in [(target, NSColor.red), (cover, NSColor.blue)] {
            window.contentView!.wantsLayer = true
            window.contentView!.layer!.backgroundColor = color.cgColor
        }
        text.stringValue = "Initial fixture value"
        text.setAccessibilityLabel("Fixture input")
        target.contentView!.addSubview(text)
        let button = NSButton(title: "Increment fixture", target: self, action: #selector(increment))
        button.frame = NSRect(x: 20, y: 130, width: 200, height: 30)
        target.contentView!.addSubview(button)
        let password = NSSecureTextField(frame: NSRect(x: 20, y: 80, width: 240, height: 28))
        password.stringValue = "fixture-password-must-not-leak"
        target.contentView!.addSubview(password)
        // Both windows remain behind the user's windows. Blue completely occludes red.
        cover.orderBack(nil)
        target.orderBack(nil)
    }

    @objc func increment() { count += 1 }

    func respond(_ request: [String: Any]) -> [String: Any] {
        switch request["action"] as? String {
        case "edit": text.stringValue = "Edited independently"
        case "close": target.close()
        case "snapshot": break
        default: return ["error": "unsupported"]
        }
        let mouse = CGEvent(source: nil)?.location ?? .zero
        return ["count": count, "text": text.stringValue,
                "foreground": NSWorkspace.shared.frontmostApplication?.processIdentifier ?? -1,
                "mouse_x": mouse.x, "mouse_y": mouse.y,
                "clipboard_sequence": NSPasteboard.general.changeCount]
    }
}

let app = NSApplication.shared
app.setActivationPolicy(.accessory)
let fixture = Fixture(title: CommandLine.arguments[1])
DispatchQueue.global(qos: .userInitiated).async {
    while let line = readLine() {
        let request = (try? JSONSerialization.jsonObject(with: Data(line.utf8))) as? [String: Any]
        DispatchQueue.main.sync {
            let response = request.map(fixture.respond) ?? ["error": "malformed"]
            do {
                let data = try JSONSerialization.data(withJSONObject: response, options: [.sortedKeys])
                FileHandle.standardOutput.write(data)
                FileHandle.standardOutput.write(Data([10]))
            } catch {
                FileHandle.standardError.write(Data("Fixture response serialization failed\n".utf8))
                exit(1)
            }
        }
    }
    DispatchQueue.main.async { app.terminate(nil) }
}
app.run()
