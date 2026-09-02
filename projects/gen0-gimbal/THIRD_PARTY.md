# Third-party and data review

Dependencies are declared, not redistributed: each is fetched from its public registry by the documented build steps, so no third-party source is committed here. Identities and licenses below still require human review before acceptance.

| Dependency | Identity and source | Version | License | Use |
|---|---|---|---|---|
| ArduinoJson | github.com/bblanchon/ArduinoJson | 7.4.3 (Library Manager) | MIT | Firmware JSON parsing (src/firmware) |
| Flutter SDK | flutter.dev | 3.x stable | BSD-3-Clause | App toolchain (src/app) |
| flutter_blue_plus | pub.dev/packages/flutter_blue_plus | 1.36.8 | BSD-3-Clause | BLE client in the app |
| provider | pub.dev/packages/provider | 6.1.5 | MIT | App state management |
| pyserial | pypi.org/project/pyserial | >=3.5 | BSD-style | Serial transport in src/tools |
| Flask | pypi.org/project/flask | >=2.0 | BSD-3-Clause | Web debug console in src/tools |

Data and materials notes:

- No weights, datasets, media assets, or hardware design sources are included.
- The F32C wire protocol semantics (frame format, function codes) were independently documented from observed hardware behavior. The vendor manual and vendor MicroPython examples are excluded; no publication rights for vendor documents are claimed or granted.
- No device serial numbers, credentials, serial logs, authentication output, or personal media are included. Fixtures are synthetic-only.
- Human review evidence: pending maintainer review of this table before acceptance.
