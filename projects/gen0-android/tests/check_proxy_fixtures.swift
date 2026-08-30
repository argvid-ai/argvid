import AppKit
import Foundation

let directory = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
let expected: [[Int]] = [
    [255, 0, 0], [255, 255, 0], [0, 255, 0], [0, 255, 255],
    [0, 0, 255], [255, 0, 255], [255, 255, 255], [0, 0, 0],
]
var uniqueFrames = Set<Data>()
for index in 1...16 {
    let name = String(format: "frame-%02d.jpg", index)
    let data = try Data(contentsOf: directory.appendingPathComponent(name))
    guard let bitmap = NSBitmapImageRep(data: data), bitmap.pixelsWide == 960, bitmap.pixelsHigh == 540 else {
        fatalError("\(name): expected a decodable 960x540 JPEG")
    }
    guard bitmap.bitsPerSample == 8, bitmap.samplesPerPixel >= 3 else {
        fatalError("\(name): expected decoded 8-bit RGB samples")
    }
    for (bar, rgb) in expected.enumerated() {
        var pixel = [Int](repeating: 0, count: bitmap.samplesPerPixel)
        bitmap.getPixel(&pixel, atX: bar * 120 + 60, y: 80)
        guard zip(pixel.prefix(3), rgb).allSatisfy({ abs($0 - $1) < 30 }) else {
            fputs("FAIL: \(name) bar \(bar) is not the expected RGB color\n", stderr)
            exit(1)
        }
    }
    uniqueFrames.insert(data)
}
guard uniqueFrames.count == 16 else {
    fatalError("Expected 16 distinct numbered frames, got \(uniqueFrames.count)")
}
print("PASS: 16 distinct 960x540 JPEGs, each with eight decoded color bars")
