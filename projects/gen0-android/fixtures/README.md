# Synthetic fixture provenance

All fixture inputs are generated or authored synthetic examples. No people, camera recordings, screenshots, private logs, datasets, weights or external images are included.

- `src/data/media/src/androidTest/assets/proxy-frames/frame-01.jpg` through `frame-16.jpg`: 960×540 JPEG color bars with authored pixel-digit labels 01–16. Generate using `src/scripts/generate-proxy-fixtures.swift` on macOS/Swift/AppKit. Eight fixed RGB bars and 5×7 digit rectangles use no font or image inputs. Pixel verification is in `tests/check_proxy_fixtures.swift`. JPEG bytes may vary with system encoder versions; pixel-level checks define reproducibility.
- `src/testing/fixtures/src/main/resources/parity`: authored synthetic session/moment scenarios with literal states, timestamps and expected transitions. Their local envelope version is a fixture-loader format, not canonical L2 or released protocol compatibility.
- Unit-test byte arrays, fake URIs, IDs and timestamps are synthetic and exercise failure paths without external services.
- Launcher foreground/background vectors are simple authored camera geometry, not a third-party logo or bitmap.

Project-authored source, fixture definitions, generated frames and vectors use the declared Apache-2.0 project license, subject to the required publication/rights review. See [THIRD_PARTY](../THIRD_PARTY.md) for runtime/build components and platform tooling. Do not replace these examples with personal media without explicit provenance, rights and privacy review.
