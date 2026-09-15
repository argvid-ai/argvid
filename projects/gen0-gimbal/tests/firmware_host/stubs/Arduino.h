#pragma once

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <iomanip>
#include <sstream>
#include <string>
#include <vector>

using byte = uint8_t;
using size_t = std::size_t;
#ifndef HEX
#define HEX 16
#endif

class String {
 public:
  String() = default;
  String(const char* s) : value_(s ? s : "") {}
  String(const std::string& s) : value_(s) {}
  String(char c) : value_(1, c) {}
  String(int v, int base = 10) { assignInteger(v, base); }
  String(double v, int decimals) { assignFloat(v, static_cast<unsigned int>(decimals)); }

  const char* c_str() const { return value_.c_str(); }
  std::size_t length() const { return value_.size(); }
  void reserve(std::size_t n) { value_.reserve(n); }
  void toUpperCase() {
    for (char& c : value_) c = static_cast<char>(std::toupper(static_cast<unsigned char>(c)));
  }
  String& operator+=(const String& rhs) { value_ += rhs.value_; return *this; }
  String& operator+=(const char* rhs) { value_ += rhs ? rhs : ""; return *this; }
  String& operator+=(char rhs) { value_ += rhs; return *this; }
  bool operator==(const String& rhs) const { return value_ == rhs.value_; }
  bool operator==(const char* rhs) const { return value_ == (rhs ? rhs : ""); }
  bool operator!=(const char* rhs) const { return !(*this == rhs); }
  operator std::string() const { return value_; }

 private:
  std::string value_;
  template <typename T>
  void assignInteger(T v, int base) {
    if (base == 16) {
      std::ostringstream out;
      out << std::hex << static_cast<unsigned long long>(v);
      value_ = out.str();
    } else {
      value_ = std::to_string(v);
    }
  }
  template <typename T>
  void assignFloat(T v, unsigned int decimals) {
    std::ostringstream out;
    out << std::fixed << std::setprecision(decimals) << v;
    value_ = out.str();
  }
};

inline String operator+(const String& lhs, const String& rhs) { String out(lhs); out += rhs; return out; }
inline String operator+(const String& lhs, const char* rhs) { String out(lhs); out += rhs; return out; }
inline String operator+(const char* lhs, const String& rhs) { String out(lhs); out += rhs; return out; }

class HardwareSerial {
 public:
  virtual ~HardwareSerial() = default;
  virtual int available() = 0;
  virtual int read() = 0;
  virtual std::size_t write(const uint8_t* data, std::size_t len) = 0;
  virtual void flush() {}
};

inline uint32_t millis() {
  static const auto start = std::chrono::steady_clock::now();
  return static_cast<uint32_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
      std::chrono::steady_clock::now() - start).count());
}
inline void delay(unsigned long ms) { (void)ms; }
