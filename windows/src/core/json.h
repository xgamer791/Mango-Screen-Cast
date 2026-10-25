// A small JSON reader and string quoting, enough for HELLO, WELCOME and
// REFUSE. Portable C++17.
#pragma once

#include <string>
#include <utility>
#include <vector>

namespace mango::json {

struct Value {
    enum class Type { Null, Bool, Number, String, Array, Object };
    Type type = Type::Null;
    bool boolean = false;
    double number = 0;
    std::string string;
    std::vector<Value> array;
    std::vector<std::pair<std::string, Value>> object;

    bool isNull() const { return type == Type::Null; }
    bool isObject() const { return type == Type::Object; }
    // The member called `key`, or nullptr (also when this isn't an object).
    const Value* get(const std::string& key) const;
    std::string getString(const std::string& key, const std::string& fallback = "") const;
    double getNumber(const std::string& key, double fallback = 0) const;
    bool getBool(const std::string& key, bool fallback = false) const;
};

// Parses a whole JSON text. On failure returns false and says why in *error.
bool parse(const std::string& text, Value& out, std::string* error = nullptr);

// `s` as a JSON string literal, quotes included.
std::string quote(const std::string& s);

}  // namespace mango::json
