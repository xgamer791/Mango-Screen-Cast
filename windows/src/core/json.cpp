#include "json.h"

#include <cstdio>
#include <cstdlib>

namespace mango::json {

const Value* Value::get(const std::string& key) const {
    if (type != Type::Object) return nullptr;
    for (const auto& kv : object)
        if (kv.first == key) return &kv.second;
    return nullptr;
}

std::string Value::getString(const std::string& key, const std::string& fallback) const {
    const Value* v = get(key);
    return v && v->type == Type::String ? v->string : fallback;
}

double Value::getNumber(const std::string& key, double fallback) const {
    const Value* v = get(key);
    return v && v->type == Type::Number ? v->number : fallback;
}

bool Value::getBool(const std::string& key, bool fallback) const {
    const Value* v = get(key);
    return v && v->type == Type::Bool ? v->boolean : fallback;
}

namespace {

struct Parser {
    explicit Parser(const std::string& text) : s(text) {}
    const std::string& s;
    size_t i = 0;
    std::string error;
    int depth = 0;

    bool fail(const char* what) {
        if (error.empty()) error = std::string(what) + " at offset " + std::to_string(i);
        return false;
    }

    void space() {
        while (i < s.size() && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++;
    }

    bool literal(const char* word) {
        size_t n = 0;
        while (word[n]) n++;
        if (s.compare(i, n, word) != 0) return fail("unexpected token");
        i += n;
        return true;
    }

    static void utf8(std::string& out, unsigned cp) {
        if (cp < 0x80) {
            out += static_cast<char>(cp);
        } else if (cp < 0x800) {
            out += static_cast<char>(0xC0 | (cp >> 6));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            out += static_cast<char>(0xE0 | (cp >> 12));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else {
            out += static_cast<char>(0xF0 | (cp >> 18));
            out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
            out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        }
    }

    bool hex4(unsigned& cp) {
        if (i + 4 > s.size()) return fail("short \\u escape");
        cp = 0;
        for (int k = 0; k < 4; k++) {
            char c = s[i++];
            cp <<= 4;
            if (c >= '0' && c <= '9') cp |= static_cast<unsigned>(c - '0');
            else if (c >= 'a' && c <= 'f') cp |= static_cast<unsigned>(c - 'a' + 10);
            else if (c >= 'A' && c <= 'F') cp |= static_cast<unsigned>(c - 'A' + 10);
            else return fail("bad \\u escape");
        }
        return true;
    }

    bool str(std::string& out) {
        if (i >= s.size() || s[i] != '"') return fail("expected string");
        i++;
        while (i < s.size()) {
            char c = s[i++];
            if (c == '"') return true;
            if (static_cast<unsigned char>(c) < 0x20) return fail("control character in string");
            if (c != '\\') {
                out += c;
                continue;
            }
            if (i >= s.size()) break;
            char e = s[i++];
            switch (e) {
                case '"': out += '"'; break;
                case '\\': out += '\\'; break;
                case '/': out += '/'; break;
                case 'b': out += '\b'; break;
                case 'f': out += '\f'; break;
                case 'n': out += '\n'; break;
                case 'r': out += '\r'; break;
                case 't': out += '\t'; break;
                case 'u': {
                    unsigned cp = 0;
                    if (!hex4(cp)) return false;
                    if (cp >= 0xD800 && cp < 0xDC00 && i + 6 <= s.size() && s[i] == '\\' && s[i + 1] == 'u') {
                        i += 2;
                        unsigned lo = 0;
                        if (!hex4(lo)) return false;
                        if (lo >= 0xDC00 && lo < 0xE000) cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                        else cp = 0xFFFD;
                    } else if (cp >= 0xD800 && cp < 0xE000) {
                        cp = 0xFFFD;
                    }
                    utf8(out, cp);
                    break;
                }
                default: return fail("bad escape");
            }
        }
        return fail("unterminated string");
    }

    bool num(double& out) {
        size_t start = i;
        if (i < s.size() && s[i] == '-') i++;
        while (i < s.size() && ((s[i] >= '0' && s[i] <= '9') || s[i] == '.' || s[i] == 'e' || s[i] == 'E' ||
                                s[i] == '+' || s[i] == '-'))
            i++;
        if (start == i) return fail("expected number");
        std::string text = s.substr(start, i - start);
        char* end = nullptr;
        out = std::strtod(text.c_str(), &end);
        if (!end || *end != '\0') return fail("bad number");
        return true;
    }

    bool value(Value& v) {
        if (++depth > 64) return fail("nested too deeply");
        space();
        if (i >= s.size()) return fail("unexpected end");
        bool ok = true;
        char c = s[i];
        if (c == '{') {
            v.type = Value::Type::Object;
            i++;
            space();
            if (i < s.size() && s[i] == '}') {
                i++;
            } else {
                for (;;) {
                    space();
                    std::string key;
                    if (!str(key)) return false;
                    space();
                    if (i >= s.size() || s[i] != ':') return fail("expected ':'");
                    i++;
                    Value member;
                    if (!value(member)) return false;
                    v.object.emplace_back(std::move(key), std::move(member));
                    space();
                    if (i < s.size() && s[i] == ',') {
                        i++;
                        continue;
                    }
                    if (i < s.size() && s[i] == '}') {
                        i++;
                        break;
                    }
                    return fail("expected ',' or '}'");
                }
            }
        } else if (c == '[') {
            v.type = Value::Type::Array;
            i++;
            space();
            if (i < s.size() && s[i] == ']') {
                i++;
            } else {
                for (;;) {
                    Value item;
                    if (!value(item)) return false;
                    v.array.push_back(std::move(item));
                    space();
                    if (i < s.size() && s[i] == ',') {
                        i++;
                        continue;
                    }
                    if (i < s.size() && s[i] == ']') {
                        i++;
                        break;
                    }
                    return fail("expected ',' or ']'");
                }
            }
        } else if (c == '"') {
            v.type = Value::Type::String;
            ok = str(v.string);
        } else if (c == 't') {
            v.type = Value::Type::Bool;
            v.boolean = true;
            ok = literal("true");
        } else if (c == 'f') {
            v.type = Value::Type::Bool;
            ok = literal("false");
        } else if (c == 'n') {
            v.type = Value::Type::Null;
            ok = literal("null");
        } else {
            v.type = Value::Type::Number;
            ok = num(v.number);
        }
        depth--;
        return ok;
    }
};

}  // namespace

bool parse(const std::string& text, Value& out, std::string* error) {
    Parser p(text);
    out = Value();
    bool ok = p.value(out);
    if (ok) {
        p.space();
        if (p.i != text.size()) ok = p.fail("trailing characters");
    }
    if (!ok && error) *error = p.error;
    return ok;
}

std::string quote(const std::string& s) {
    std::string out = "\"";
    for (unsigned char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof buf, "\\u%04x", c);
                    out += buf;
                } else {
                    out += static_cast<char>(c);
                }
        }
    }
    out += '"';
    return out;
}

}  // namespace mango::json
