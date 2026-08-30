# RFC 0001: Gen0.5 gimbal GATT v1

- Status: Proposed
- Owner: RFC proposer
- Required review: protocol and safety
- Hardware-in-the-loop: Pending

## Summary

Define a fixed, adapter-local Bluetooth Low Energy GATT boundary between a Gen0.5 gimbal and a controller. This is an L1 adapter protocol. It does not add motor commands or device-specific fields to the public L2 contracts.

The proposal uses fixed little-endian packets no larger than the default 20-byte BLE payload. Production clients must not pin these UUIDs or fixture hashes until this RFC is accepted.

## Scope

This RFC defines service discovery, capability negotiation, setpoints, operating modes, state notifications, emergency stop, watchdog behavior, replay protection, and conformance fixtures. It does not define directing behavior, motor tuning, pairing policy, firmware implementation structure, or user-interface behavior.

## GATT service

| Item | UUID | Operation |
|---|---|---|
| Service | `8E400001-F315-4F60-9FB8-838830DAEA50` | primary service |
| Capability | `8E400002-F315-4F60-9FB8-838830DAEA50` | read |
| Setpoint | `8E400003-F315-4F60-9FB8-838830DAEA50` | write without response |
| Mode | `8E400004-F315-4F60-9FB8-838830DAEA50` | write with response |
| State | `8E400005-F315-4F60-9FB8-838830DAEA50` | notify |
| EStop | `8E400006-F315-4F60-9FB8-838830DAEA50` | write with response |

All multi-byte integers are little-endian. Signed values use two's complement. Reserved bits and bytes must be zero when sent and ignored when received unless a later compatible revision assigns them. Field-specific receiver requirements take precedence over this generic ignore rule: receivers reject nonzero Setpoint.flags and unknown Capability.axis_flags or Capability.capability_flags bits.

## Packet layouts

### Capability, 20 bytes

| Offset | Field | Type | Unit or meaning |
|---:|---|---|---|
| 0 | version | u8 | `1` |
| 1 | axis_flags | u8 | bit 0 pan, bit 1 tilt |
| 2 | pan_min_cdeg | i16 | 0.01 degree |
| 4 | pan_max_cdeg | i16 | 0.01 degree |
| 6 | tilt_min_cdeg | i16 | 0.01 degree |
| 8 | tilt_max_cdeg | i16 | 0.01 degree |
| 10 | max_dps_cdeg | u16 | 0.01 degree/second |
| 12 | state_hz | u8 | notifications/second |
| 13 | capability_flags | u8 | supported mode bits 0 through 4 |
| 14 | watchdog_ms | u16 | lost-contact hold deadline |
| 16 | fw_major | u8 | firmware version |
| 17 | fw_minor | u8 | firmware version |
| 18 | fw_patch | u8 | firmware version |
| 19 | fw_build | u8 | firmware build |

The decoder rejects an unknown version, unknown axis or capability bits, inverted supported ranges, zero maximum speed, zero state rate, or a watchdog outside `1..500` ms.

### Setpoint, 16 bytes

| Offset | Field | Type | Unit or meaning |
|---:|---|---|---|
| 0 | version | u8 | `1` |
| 1 | flags | u8 | reserved, must be zero |
| 2 | seq | u16 | command sequence |
| 4 | pan_cdeg | i16 | 0.01 degree |
| 6 | tilt_cdeg | i16 | 0.01 degree |
| 8 | max_dps_cdeg | u16 | 0.01 degree/second |
| 10 | deadline_ms | u16 | maximum local command age |
| 12 | session_nonce | u32 | connection-scoped nonce |

Unsupported axes, positions outside advertised bounds, and speeds above the advertised maximum are rejected; they are never silently clamped.

### Mode and EStop, 8 bytes each

| Offset | Field | Type | Unit or meaning |
|---:|---|---|---|
| 0 | version | u8 | `1` |
| 1 | mode_or_reason | u8 | mode enum or EStop reason |
| 2 | seq | u16 | command sequence |
| 4 | session_nonce | u32 | connection-scoped nonce |

Mode values are `0 manual`, `1 hold`, `2 home`, `3 scan`, and `4 track`. A mode not advertised by `capability_flags` is rejected. EStop reason values are adapter diagnostics; unknown nonzero reasons still stop motion and are reported as unknown.

### State, 16 bytes

| Offset | Field | Type | Unit or meaning |
|---:|---|---|---|
| 0 | version | u8 | `1` |
| 1 | state | u8 | motion state enum |
| 2 | ack_seq | u16 | last accepted command |
| 4 | pan_cdeg | i16 | 0.01 degree |
| 6 | tilt_cdeg | i16 | 0.01 degree |
| 8 | fault_flags | u16 | observable safety state |
| 10 | temp_decic | i16 | 0.1 degree Celsius |
| 12 | uptime_ms | u32 | firmware monotonic uptime |

State values are `0 idle`, `1 moving`, `2 settling`, `3 holding`, `4 stalled`, and `5 fault`. Fault bits are: bit 0 EStop latched, bit 1 soft limit, bit 2 over-temperature, bit 3 driver fault, bit 4 watchdog hold, bit 5 nonce mismatch, bit 6 sequence rejected, and bit 7 deadline expired.

## Session, acknowledgement, and expiry

The controller creates a fresh, unpredictable `session_nonce` after service discovery. The firmware binds the active BLE connection to that nonce on its first valid command. A command with another nonce cannot move the gimbal and makes the mismatch observable in State.

Sequence numbers advance modulo 65536. The firmware accepts a sequence only if it is newer within the active session, acknowledges the last accepted sequence in State, and reports duplicates or out-of-order commands without moving. The controller never treats local BLE queueing as acknowledgement.

`deadline_ms` is command-age metadata, not a wall-clock timestamp. A sender drops a queued command when local elapsed time since creation exceeds the deadline. A receiver or conformance harness that has command-age evidence rejects an expired command separately from byte decoding. Firmware never extends its watchdog beyond the smaller of the advertised watchdog and a valid command deadline.

## Safety behavior

Only one GATT operation may be in flight from a controller. The firmware owns hard limits, thermal and driver faults, EStop, and the lost-contact watchdog. If valid contact is absent for the advertised watchdog, and always within 500 ms, firmware stops driven motion and enters `holding` with the watchdog fault bit set.

A well-formed v1 EStop immediately stops and latches motion even when its nonce or reason is stale; diagnostic rejection is reported after the safe action. Clearing a latched EStop is outside this v1 packet set and requires an explicit firmware-safe path.

## Compatibility impact

This is the initial pre-alpha L1 gimbal adapter proposal and is behavioral for that adapter. It makes no L2 schema change. Existing L2 intent, capability, target-pose, safety, and evidence contract families remain device-independent.

## Alternatives considered

- A vendor UART-style stream over a single characteristic was rejected because it obscures operation ordering and capability discovery.
- Floating-point angles were rejected because fixed centidegrees are deterministic across Kotlin, Python, and firmware.
- Packets larger than 20 bytes were rejected to avoid depending on negotiated MTU.
- Placing setpoints in L2 was rejected because actuator commands belong to the L1 adapter boundary.

## Verification plan

- Decode the canonical valid Setpoint and State fixtures byte for byte.
- Reject exact-length violations, unknown versions, unsupported capabilities, stale nonces, out-of-order acknowledgements, and expired deadlines.
- Run the same fixture corpus against the deterministic simulator, Android codec after acceptance, and firmware host tests.
- Record simulator, host firmware, and physical HIL results separately. Physical HIL remains `pending` until executed.

## Acceptance

Acceptance requires protocol and safety approval, green repository and conformance checks, and a durable accepted status update. Until then this document and its UUIDs remain a proposal.
