import AppKit
import Foundation

let output = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)

// Authored 5x7 digits avoid fonts or external image inputs.
let digits: [[Int]] = [
    [14, 17, 19, 21, 25, 17, 14], [4, 12, 4, 4, 4, 4, 14],
    [14, 17, 1, 2, 4, 8, 31], [30, 1, 1, 14, 1, 1, 30],
    [2, 6, 10, 18, 31, 2, 2], [31, 16, 16, 30, 1, 1, 30],
    [14, 16, 16, 30, 17, 17, 14], [31, 1, 2, 4, 8, 8, 8],
    [14, 17, 17, 14, 17, 17, 14], [14, 17, 17, 15, 1, 1, 14],
]
let colors: [NSColor] = [.red, .yellow, .green, .cyan, .blue, .magenta, .white, .black]

for index in 0..<16 {
    guard let bitmap = NSBitmapImageRep(
        bitmapDataPlanes: nil,
        pixelsWide: 960,
        pixelsHigh: 540,
        bitsPerSample: 8,
        samplesPerPixel: 4,
        hasAlpha: true,
        isPlanar: false,
        colorSpaceName: .deviceRGB,
        bytesPerRow: 0,
        bitsPerPixel: 0
    ) else {
        fatalError("Unable to allocate fixture bitmap")
    }
    guard let drawingContext = NSGraphicsContext(bitmapImageRep: bitmap) else {
        fatalError("Unable to create fixture drawing context")
    }
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = drawingContext
    drawingContext.shouldAntialias = false
    for (bar, color) in colors.enumerated() {
        color.setFill()
        NSBezierPath(rect: NSRect(x: bar * 120, y: 0, width: 120, height: 540)).fill()
    }
    NSColor.black.setFill()
    NSBezierPath(rect: NSRect(x: 330, y: 165, width: 300, height: 210)).fill()
    NSColor.white.setFill()
    for (position, digit) in [(index + 1) / 10, (index + 1) % 10].enumerated() {
        for (row, bits) in digits[digit].enumerated() {
            for column in 0..<5 where bits & (1 << (4 - column)) != 0 {
                NSBezierPath(rect: NSRect(
                    x: 360 + position * 140 + column * 20,
                    y: 330 - row * 20, width: 20, height: 20
                )).fill()
            }
        }
    }
    NSGraphicsContext.restoreGraphicsState()

    guard let jpeg = bitmap.representation(using: .jpeg, properties: [.compressionFactor: 0.82]) else {
        fatalError("Unable to encode fixture JPEG")
    }
    let name = String(format: "frame-%02d.jpg", index + 1)
    try jpeg.write(to: output.appendingPathComponent(name), options: .atomic)
}
