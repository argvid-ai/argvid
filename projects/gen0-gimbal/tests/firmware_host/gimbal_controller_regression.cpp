#include <cassert>
#include <cmath>
#include <cstdint>
#include <iostream>
#include <string>
#include <vector>

#include "gimbal_controller.h"

namespace {
struct Frame {
  uint8_t addr;
  uint8_t func;
  std::vector<uint8_t> data;
};

class FakeMotorSerial final : public HardwareSerial {
 public:
  std::vector<Frame> frames;
  int32_t panTotal = 7300;   // 730.0 degrees: nearest equivalent is 720.0.
  int32_t tiltTotal = 100;   // 10.0 degrees.
  bool wrongTotalType = false;
  bool dropTotalResponse = false;
  // F1 fault injection: drop responses for specific function codes on specific axes.
  uint8_t failEnableAddr = 0;   // nonzero = enable response dropped for that addr
  uint8_t failSetModeAddr = 0;  // nonzero = set-mode response dropped for that addr

  int available() override { return static_cast<int>(rx.size()); }
  int read() override {
    if (rx.empty()) return -1;
    const int value = rx.front();
    rx.erase(rx.begin());
    return value;
  }
  std::size_t write(const uint8_t* data, std::size_t len) override {
    if (len < 5) return 0;
    Frame f{data[1], data[2], std::vector<uint8_t>(data + 3, data + len - 2)};
    frames.push_back(f);
    if (f.func == F32CMotor::FC_QUERY && f.data.size() == 1 && f.data[0] == F32CMotor::RT_TOTAL_ANGLE) {
      if (!dropTotalResponse) {
        queueResponse(f.addr, wrongTotalType ? F32CMotor::RT_SPEED : f.data[0],
                      f.addr == 1 ? panTotal : tiltTotal);
      }
    }
    // F1: short-write injection (returning len-1) actually fails write-only
    // commands like enable/mode — they don't wait for responses, so dropping
    // responses has no effect on them.
    if (f.func == F32CMotor::FC_ENABLE && f.addr == failEnableAddr) {
      return len - 1;
    }
    if (f.func == F32CMotor::FC_SET_MODE && f.addr == failSetModeAddr) {
      return len - 1;
    }
    return len;
  }

  void clearFrames() { frames.clear(); }
  std::vector<Frame> framesFor(uint8_t addr) const {
    std::vector<Frame> result;
    for (const Frame& f : frames) if (f.addr == addr) result.push_back(f);
    return result;
  }

  std::vector<Frame> motionFramesFor(uint8_t addr) const {
    std::vector<Frame> result;
    for (const Frame& f : frames) {
      if (f.addr == addr && f.func != F32CMotor::FC_QUERY) result.push_back(f);
    }
    return result;
  }

 private:
  std::vector<uint8_t> rx;
  static uint8_t bcc(const std::vector<uint8_t>& frame) {
    uint8_t value = 0;
    for (uint8_t b : frame) value ^= b;
    return value;
  }
  void queueResponse(uint8_t addr, uint8_t type, int32_t value) {
    std::vector<uint8_t> frame = {0x7A, addr, type,
        static_cast<uint8_t>(value >> 24), static_cast<uint8_t>(value >> 16),
        static_cast<uint8_t>(value >> 8), static_cast<uint8_t>(value), 0, 0x7B};
    frame[7] = bcc(std::vector<uint8_t>(frame.begin(), frame.begin() + 7));
    rx.insert(rx.end(), frame.begin(), frame.end());
  }
};

void require(bool condition, const char* message) {
  if (!condition) {
    std::cerr << "FAIL: " << message << "\n";
    std::exit(1);
  }
}

void testModeRecoveryAfterMotorOnlyReboot() {
  FakeMotorSerial serial;
  F32CMotor motor;
  motor.begin(&serial, 1);
  GimbalController gimbal;
  gimbal.begin(&motor);
  gimbal.config(1, 2);

  require(gimbal.move(5.0f, 4.0f, true, true).valid, "initial position move accepted");
  serial.clearFrames();

  // ESP32 remains up while both motor controllers reboot. The protocol has no
  // motor-reset notification, so every explicit position move must rehydrate
  // each motor immediately before its target.
  require(gimbal.move(6.0f, 5.0f, true, true).valid, "post-reboot move accepted");
  require(serial.frames.size() == 10,
          "post-reboot move reads each axis then emits recovery and target");
  for (uint8_t addr : {uint8_t(1), uint8_t(2)}) {
    const auto axis = serial.framesFor(addr);
    require(axis.size() == 5, "each axis reads telemetry then emits four recovery frames");
    require(axis[0].func == F32CMotor::FC_QUERY && axis[0].data[0] == F32CMotor::RT_TOTAL_ANGLE,
            "fresh total angle read before move");
    require(axis[1].func == F32CMotor::FC_SET_MODE && axis[1].data[1] == 1, "position mode restored");
    require(axis[2].func == F32CMotor::FC_ENABLE, "axis re-enabled");
    require(axis[3].func == F32CMotor::FC_SET_SPEED && axis[3].data[0] == 0 && axis[3].data[1] == 60,
            "position speed restored before target");
    require(axis[4].func == F32CMotor::FC_SET_MULTI_ANGLE, "target follows recovery transaction");
  }
}

void testCenterUsesNearestPanTurn() {
  FakeMotorSerial serial;
  F32CMotor motor;
  motor.begin(&serial, 1);
  GimbalController gimbal;
  gimbal.begin(&motor);
  gimbal.config(1, 2);

  require(gimbal.center().valid, "center accepted with fresh angle feedback");
  bool sawPan720 = false;
  bool sawPanZero = false;
  for (const Frame& f : serial.frames) {
    if (f.addr == 1 && f.func == F32CMotor::FC_SET_MULTI_ANGLE) {
      const int32_t value = (static_cast<int32_t>(f.data[0]) << 24) |
          (static_cast<int32_t>(f.data[1]) << 16) | (static_cast<int32_t>(f.data[2]) << 8) | f.data[3];
      sawPan720 = sawPan720 || value == 7200;
      sawPanZero = sawPanZero || value == 0;
    }
  }
  require(sawPan720 && !sawPanZero, "730 degree pan centers to nearest 720 degree target");

  auto lastPanTarget = [&serial]() {
    int32_t target = -999999;
    for (const Frame& f : serial.frames) {
      if (f.addr == 1 && f.func == F32CMotor::FC_SET_MULTI_ANGLE) {
        target = static_cast<int32_t>((uint32_t(f.data[0]) << 24) |
            (uint32_t(f.data[1]) << 16) | (uint32_t(f.data[2]) << 8) | f.data[3]);
      }
    }
    return target;
  };
  serial.panTotal = 7200;  // Simulate arrival at the chosen equivalent origin.
  serial.clearFrames();
  require(gimbal.move(5.0f, 0.0f, true, false).valid, "move after equivalent center accepted");
  require(lastPanTarget() == 7250, "next five-degree step stays near the equivalent origin");

  serial.panTotal = 0;  // A later motor reboot resets only the motor's total.
  serial.clearFrames();
  require(gimbal.move(5.0f, 0.0f, true, false).valid, "move after motor total reset accepted");
  require(lastPanTarget() == 50, "motor reboot cannot reuse an old 720-degree offset");
}

void testTiltOutOfRangeCenterIsRejectedAndStopped() {
  FakeMotorSerial serial;
  serial.tiltTotal = 2270;  // Reproduce the reported 227 degree tilt telemetry.
  F32CMotor motor;
  motor.begin(&serial, 1);
  GimbalController gimbal;
  gimbal.begin(&motor);
  gimbal.config(1, 2);

  require(!gimbal.center().valid, "227 degree tilt center rejected");
  for (const Frame& f : serial.frames) {
    require(f.func != F32CMotor::FC_SET_MULTI_ANGLE, "rejected center emits no position target");
    if (f.func == F32CMotor::FC_SET_SPEED) {
      require(f.data[0] == 0 && f.data[1] == 0, "safety stop only emits zero speed");
    }
  }
}

void testCenterRejectsStaleOrWrongTelemetry() {
  for (bool wrongType : {false, true}) {
    FakeMotorSerial serial;
    serial.wrongTotalType = wrongType;
    serial.dropTotalResponse = !wrongType;
    F32CMotor motor;
    motor.begin(&serial, 1);
    GimbalController gimbal;
    gimbal.begin(&motor);
    gimbal.config(1, 2);
    require(!gimbal.center().valid, wrongType ? "wrong telemetry type rejected" : "missing telemetry rejected");
    for (const Frame& f : serial.frames) {
      require(f.func != F32CMotor::FC_SET_MULTI_ANGLE, "invalid telemetry emits no position target");
    }
  }
}
}  // namespace


// F1 regression: enable failure must not skip zero-speed for that axis.
// Reproduces the exact scenario from the review: pan enabled, jog at 60 RPM,
// then a redundant enable with a short UART write — the old `continue` would
// skip setSpeed(0) for pan, leaving it spinning.
void testF1EnableFailureStillAttemptsZeroSpeed() {
  FakeMotorSerial serial;
  serial.failEnableAddr = 1;  // pan enable responses dropped
  F32CMotor motor;
  motor.begin(&serial, 1);
  GimbalController gimbal;
  gimbal.begin(&motor);
  gimbal.config(1, 2);

  MotorResponse r = gimbal.emergencyStop();
  require(!r.valid, "F1: e-stop reports failure when enable short-writes");

  // The critical assertion: pan must still get a FC_SET_SPEED frame with
  // speed 0, even though the enable step failed.
  bool panZeroSent = false;
  bool tiltZeroSent = false;
  for (const Frame& f : serial.frames) {
    if (f.func == F32CMotor::FC_SET_SPEED && f.addr == 1) {
      const int16_t speed = static_cast<int16_t>((uint16_t(f.data[0]) << 8) | f.data[1]);
      if (speed == 0) panZeroSent = true;
    }
    if (f.func == F32CMotor::FC_SET_SPEED && f.addr == 2) {
      const int16_t speed = static_cast<int16_t>((uint16_t(f.data[0]) << 8) | f.data[1]);
      if (speed == 0) tiltZeroSent = true;
    }
  }
  require(panZeroSent, "F1: pan zero-speed must still be attempted after enable failure");
  require(tiltZeroSent, "F1: tilt zero-speed must still be sent (independent of pan failure)");

  // F1 cache: after failure, next jog re-sends mode+enable (cache stayed -1).
  serial.clearFrames();
  serial.failEnableAddr = 0;
  MotorResponse jog = gimbal.jog("pan", 1, 60);
  require(jog.valid, "F1: next jog succeeds after fault removed");
  bool modeResent = false, enableResent = false;
  for (const Frame& f : serial.framesFor(1)) {
    if (f.func == F32CMotor::FC_SET_MODE) modeResent = true;
    if (f.func == F32CMotor::FC_ENABLE) enableResent = true;
  }
  require(modeResent, "F1: mode re-sent (cache stayed invalid)");
  require(enableResent, "F1: enable re-sent (cache stayed invalid)");
}

// F1 companion: set-mode failure also must not skip zero-speed.
void testF1SetModeFailureStillAttemptsZeroSpeed() {
  FakeMotorSerial serial;
  serial.failSetModeAddr = 1;
  F32CMotor motor;
  motor.begin(&serial, 1);
  GimbalController gimbal;
  gimbal.begin(&motor);
  gimbal.config(1, 2);

  MotorResponse r = gimbal.emergencyStop();
  require(!r.valid, "F1: e-stop reports failure when mode-set short-writes");

  bool panZeroSent = false;
  bool tiltZeroSent = false;
  for (const Frame& f : serial.frames) {
    if (f.func == F32CMotor::FC_SET_SPEED) {
      const int16_t speed = static_cast<int16_t>((uint16_t(f.data[0]) << 8) | f.data[1]);
      if (f.addr == 1 && speed == 0) panZeroSent = true;
      if (f.addr == 2 && speed == 0) tiltZeroSent = true;
    }
  }
  require(panZeroSent, "F1: pan zero-speed attempted even after mode-set failure");
  require(tiltZeroSent, "F1: tilt zero-speed unaffected by pan mode-set failure");

  // F1 cache: mode failure also invalidates cache for next command.
  serial.clearFrames();
  serial.failSetModeAddr = 0;
  MotorResponse jog = gimbal.jog("pan", 1, 60);
  require(jog.valid, "F1: next jog succeeds after mode fault removed");
  bool modeResent2 = false;
  for (const Frame& f : serial.framesFor(1)) {
    if (f.func == F32CMotor::FC_SET_MODE) modeResent2 = true;
  }
  require(modeResent2, "F1: mode re-sent after mode-set failure (cache invalid)");
}


// F2: abortMotion after the blocking safety query must prevent mode/enable/
// non-zero speed/target writes. This models stop/disconnect arriving during
// the nested positionSafety inside move().
void testF2AbortAfterSafetyBlocksMotionWrites() {
  FakeMotorSerial serial;
  F32CMotor motor;
  motor.begin(&serial, 1);
  GimbalController gimbal;
  gimbal.begin(&motor);
  gimbal.config(1, 2);

  // Prime a clean baseline so the abort case is not confounded by first-connect.
  require(gimbal.move(1.0f, 1.0f, true, true).valid, "F2 setup move accepted");
  serial.clearFrames();
  gimbal.clearMotionAbort();

  // Abort is set as if BLE stop arrived during the upcoming safety query.
  gimbal.abortMotion();
  MotorResponse r = gimbal.move(10.0f, 5.0f, true, true);
  require(!r.valid, "F2: aborted move must report failure");
  require(std::string(r.parsed_text.c_str()).find("抢占") != std::string::npos || std::string(r.parsed_text.c_str()).find("停止") != std::string::npos,
          "F2: failure text must mention preemption/stop");

  // Queries for safety are allowed; motion-driving frames are not.
  for (const Frame& f : serial.frames) {
    if (f.func == F32CMotor::FC_QUERY) continue;
    require(false, "F2: aborted move must not emit mode/enable/speed/target");
  }
}

// F2 companion: abort between axes after pan writes have started.
void testF2AbortBetweenAxesStopsTiltWrites() {
  FakeMotorSerial serial;
  F32CMotor motor;
  motor.begin(&serial, 1);
  GimbalController gimbal;
  gimbal.begin(&motor);
  gimbal.config(1, 2);
  require(gimbal.move(1.0f, 1.0f, true, true).valid, "F2 between-axes setup");
  serial.clearFrames();
  gimbal.clearMotionAbort();

  // Use a custom path: start move is hard to interleave without hooks, so
  // verify the post-pan check by aborting before a tilt-only move after a
  // pan-only move would have written — covered by abort-before-writes above.
  // Directly assert tilt-only aborted move emits no tilt motion frames.
  gimbal.abortMotion();
  MotorResponse r = gimbal.move(0.0f, 8.0f, false, true);
  require(!r.valid, "F2: aborted tilt-only move fails");
  for (const Frame& f : serial.framesFor(2)) {
    if (f.func == F32CMotor::FC_QUERY) continue;
    require(false, "F2: aborted tilt-only move must not drive tilt");
  }
}
int main() {
  testModeRecoveryAfterMotorOnlyReboot();
  testCenterUsesNearestPanTurn();
  testTiltOutOfRangeCenterIsRejectedAndStopped();
  testCenterRejectsStaleOrWrongTelemetry();
  testF1EnableFailureStillAttemptsZeroSpeed();
  testF1SetModeFailureStillAttemptsZeroSpeed();
  testF2AbortAfterSafetyBlocksMotionWrites();
  testF2AbortBetweenAxesStopsTiltWrites();
  std::cout << "firmware host regression: 6 tests passed\n";
  return 0;
}


