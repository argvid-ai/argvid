# Gen0.5 gimbal firmware

## Responsibility

Define host-testable firmware boundaries for bounded motion, watchdog behavior, capability reporting, and safety status.

## Non-goals

Application-specific motion tuning, unsupported controller boards, mobile-base navigation, or model execution in the safety path.

## Inputs and outputs

Inputs are bounded adapter commands; outputs are actuator requests, watchdog events, and safety status.

## Dependencies

Depends on the public gimbal adapter contract and selected reference hardware.

## Invariants

Lost contact stops motion, limits are enforced locally, and model output cannot disable safety.

## Start condition

Start after the simulator contract and board/toolchain ADR are accepted.

## Ownership and review

Firmware owner with mandatory safety and hardware review.

## Verification

Host tests first, simulator integration second, hardware-in-the-loop evidence last.

## Public use cases

Demonstrate host-tested deterministic L0 limits, emergency stop, and watchdog behavior for a reproducible public device boundary.

## License and data

Source and documentation default to Apache-2.0. Use synthetic or explicitly licensed public inputs with recorded provenance; publication rights require human review. Third-party assets, weights, datasets, and hardware design sources need separate license review. This documented boundary is not an activated implementation, released compatibility guarantee, or completed HIL result.
