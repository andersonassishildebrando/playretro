//  SPDX-License-Identifier: MIT
//
//  PlayRetro
//  intl.cpp
//
//  Minimal gettext implementation for Android. Reads GNU .mo catalogs from
//  <dirname>/<language>/LC_MESSAGES/<domain>.mo, where the language is taken from the
//  LANGUAGE, LC_ALL, LC_MESSAGES or LANG environment variables (in that order).
//  Catalogs are expected to be UTF-8 encoded, so no character set conversion is done.
//
//  Returned strings point into loaded catalogs, which are never unloaded, so they stay
//  valid for the lifetime of the process.
//

#include "libintl.h"

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

namespace
{
    class Catalog
    {
    public:
        bool load(const std::string& path);
        const char* find(std::string_view msgid) const;
        const char* findPlural(std::string_view msgid, unsigned long n) const;

    private:
        uint32_t read32(size_t offset) const;
        unsigned long pluralIndex(unsigned long n) const;

        std::vector<char> mData;
        bool mSwapped {false};
        // Original string (up to the first NUL, so plural entries are keyed by msgid1)
        // mapped to the translation offset and length.
        std::unordered_map<std::string_view, std::pair<uint32_t, uint32_t>> mEntries;
        std::string mPluralExpression;
        unsigned long mPluralCount {2};
    };

    // Evaluates C-like plural form expressions such as "n != 1" or
    // "(n%10==1 && n%100!=11 ? 0 : n%10>=2 && n%10<=4 && (n%100<10 || n%100>=20) ? 1 : 2)".
    class PluralEvaluator
    {
    public:
        PluralEvaluator(std::string_view expression, unsigned long n)
            : mExpr {expression}
            , mN {n}
        {
        }

        bool evaluate(unsigned long& result)
        {
            result = ternary();
            skipSpaces();
            return !mError && (mPos == mExpr.size() || mExpr[mPos] == ';');
        }

    private:
        void skipSpaces()
        {
            while (mPos < mExpr.size() && (mExpr[mPos] == ' ' || mExpr[mPos] == '\t' ||
                                           mExpr[mPos] == '\n' || mExpr[mPos] == '\r'))
                ++mPos;
        }

        bool accept(std::string_view token)
        {
            skipSpaces();
            if (mExpr.substr(mPos, token.size()) != token)
                return false;
            // Don't match "<" when the input is "<=" and so on.
            if (token.size() == 1 && mPos + 1 < mExpr.size() && mExpr[mPos + 1] == '=' &&
                (token == "<" || token == ">" || token == "!" || token == "="))
                return false;
            mPos += token.size();
            return true;
        }

        unsigned long ternary()
        {
            const unsigned long condition {logicalOr()};
            if (!accept("?"))
                return condition;
            const unsigned long first {ternary()};
            if (!accept(":")) {
                mError = true;
                return 0;
            }
            const unsigned long second {ternary()};
            return condition ? first : second;
        }

        unsigned long logicalOr()
        {
            unsigned long value {logicalAnd()};
            while (accept("||")) {
                const unsigned long rhs {logicalAnd()};
                value = (value || rhs);
            }
            return value;
        }

        unsigned long logicalAnd()
        {
            unsigned long value {equality()};
            while (accept("&&")) {
                const unsigned long rhs {equality()};
                value = (value && rhs);
            }
            return value;
        }

        unsigned long equality()
        {
            unsigned long value {relational()};
            while (true) {
                if (accept("=="))
                    value = (value == relational());
                else if (accept("!="))
                    value = (value != relational());
                else
                    return value;
            }
        }

        unsigned long relational()
        {
            unsigned long value {additive()};
            while (true) {
                if (accept("<="))
                    value = (value <= additive());
                else if (accept(">="))
                    value = (value >= additive());
                else if (accept("<"))
                    value = (value < additive());
                else if (accept(">"))
                    value = (value > additive());
                else
                    return value;
            }
        }

        unsigned long additive()
        {
            unsigned long value {multiplicative()};
            while (true) {
                if (accept("+"))
                    value += multiplicative();
                else if (accept("-"))
                    value -= multiplicative();
                else
                    return value;
            }
        }

        unsigned long multiplicative()
        {
            unsigned long value {unary()};
            while (true) {
                if (accept("*")) {
                    value *= unary();
                }
                else if (accept("/") || accept("%")) {
                    const bool modulo {mExpr[mPos - 1] == '%'};
                    const unsigned long rhs {unary()};
                    if (rhs == 0) {
                        mError = true;
                        return 0;
                    }
                    value = modulo ? value % rhs : value / rhs;
                }
                else {
                    return value;
                }
            }
        }

        unsigned long unary()
        {
            if (accept("!"))
                return !unary();
            return primary();
        }

        unsigned long primary()
        {
            skipSpaces();
            if (mPos >= mExpr.size()) {
                mError = true;
                return 0;
            }
            if (accept("(")) {
                const unsigned long value {ternary()};
                if (!accept(")"))
                    mError = true;
                return value;
            }
            if (mExpr[mPos] == 'n') {
                ++mPos;
                return mN;
            }
            if (mExpr[mPos] >= '0' && mExpr[mPos] <= '9') {
                unsigned long value {0};
                while (mPos < mExpr.size() && mExpr[mPos] >= '0' && mExpr[mPos] <= '9')
                    value = value * 10 + static_cast<unsigned long>(mExpr[mPos++] - '0');
                return value;
            }
            mError = true;
            return 0;
        }

        std::string_view mExpr;
        unsigned long mN;
        size_t mPos {0};
        bool mError {false};
    };

    uint32_t Catalog::read32(size_t offset) const
    {
        uint32_t value;
        std::memcpy(&value, mData.data() + offset, sizeof(value));
        return mSwapped ? __builtin_bswap32(value) : value;
    }

    bool Catalog::load(const std::string& path)
    {
        std::ifstream file {path, std::ios::binary};
        if (!file)
            return false;
        mData.assign(std::istreambuf_iterator<char> {file}, std::istreambuf_iterator<char> {});
        if (mData.size() < 28)
            return false;

        uint32_t magic;
        std::memcpy(&magic, mData.data(), sizeof(magic));
        if (magic == 0x950412de)
            mSwapped = false;
        else if (magic == 0xde120495)
            mSwapped = true;
        else
            return false;

        const uint32_t count {read32(8)};
        const uint32_t originalsOffset {read32(12)};
        const uint32_t translationsOffset {read32(16)};
        const size_t size {mData.size()};

        if (originalsOffset > size || translationsOffset > size ||
            count > (size - originalsOffset) / 8 || count > (size - translationsOffset) / 8)
            return false;

        mEntries.reserve(count);
        for (uint32_t i {0}; i < count; ++i) {
            const uint32_t origLength {read32(originalsOffset + i * 8)};
            const uint32_t origOffset {read32(originalsOffset + i * 8 + 4)};
            const uint32_t transLength {read32(translationsOffset + i * 8)};
            const uint32_t transOffset {read32(translationsOffset + i * 8 + 4)};
            // Both strings must be NUL terminated within the file.
            if (origOffset > size || origLength >= size - origOffset || transOffset > size ||
                transLength >= size - transOffset)
                continue;
            const std::string_view original {mData.data() + origOffset,
                                             std::strlen(mData.data() + origOffset)};
            mEntries.emplace(original, std::make_pair(transOffset, transLength));
        }

        // The header is the translation of the empty string.
        if (const char* header {find("")}) {
            const std::string_view headerView {header};
            const size_t formsPos {headerView.find("Plural-Forms:")};
            if (formsPos != std::string_view::npos) {
                std::string_view forms {headerView.substr(formsPos)};
                forms = forms.substr(0, forms.find('\n'));
                const size_t nplurals {forms.find("nplurals=")};
                if (nplurals != std::string_view::npos)
                    mPluralCount = std::strtoul(forms.data() + nplurals + 9, nullptr, 10);
                if (mPluralCount == 0)
                    mPluralCount = 2;
                const size_t plural {forms.find("plural=")};
                if (plural != std::string_view::npos)
                    mPluralExpression = std::string {forms.substr(plural + 7)};
            }
        }
        return true;
    }

    const char* Catalog::find(std::string_view msgid) const
    {
        const auto it = mEntries.find(msgid);
        if (it == mEntries.cend())
            return nullptr;
        return mData.data() + it->second.first;
    }

    unsigned long Catalog::pluralIndex(unsigned long n) const
    {
        if (mPluralExpression.empty())
            return n == 1 ? 0 : 1;
        unsigned long index {0};
        PluralEvaluator evaluator {mPluralExpression, n};
        if (!evaluator.evaluate(index))
            return n == 1 ? 0 : 1;
        return index;
    }

    const char* Catalog::findPlural(std::string_view msgid, unsigned long n) const
    {
        const auto it = mEntries.find(msgid);
        if (it == mEntries.cend())
            return nullptr;

        // Plural translations are stored NUL separated.
        const char* translation {mData.data() + it->second.first};
        const char* end {translation + it->second.second};
        unsigned long index {pluralIndex(n)};
        if (index >= mPluralCount)
            index = 0;
        for (; index > 0; --index) {
            const char* next {translation + std::strlen(translation) + 1};
            if (next >= end)
                return nullptr;
            translation = next;
        }
        return translation;
    }

    std::mutex sMutex;
    std::string sDomain {"messages"};
    std::map<std::string, std::string> sBindings;
    std::map<std::string, std::string> sCodesets;
    // Keyed by catalog path; nullptr marks a catalog which doesn't exist.
    std::map<std::string, std::unique_ptr<Catalog>> sCatalogs;
    // Storage for strings returned by textdomain() and the binding functions.
    std::map<std::string, std::string> sReturnedStrings;

    // Must be called with sMutex held.
    const std::vector<std::string>& requestedLanguages()
    {
        static std::string lastValue;
        static std::vector<std::string> languages;

        const char* value {nullptr};
        for (const char* variable : {"LANGUAGE", "LC_ALL", "LC_MESSAGES", "LANG"}) {
            value = std::getenv(variable);
            if (value != nullptr && value[0] != '\0')
                break;
            value = nullptr;
        }
        if (value == nullptr) {
            lastValue.clear();
            languages.clear();
            return languages;
        }
        if (value == lastValue && !languages.empty())
            return languages;

        lastValue = value;
        languages.clear();
        std::string_view list {value};
        while (!list.empty()) {
            std::string_view entry {list.substr(0, list.find(':'))};
            list = (entry.size() == list.size()) ? std::string_view {} : list.substr(entry.size() + 1);
            // Strip the codeset and modifier, e.g. "pt_BR.UTF-8@euro" -> "pt_BR".
            entry = entry.substr(0, entry.find_first_of(".@"));
            if (entry.empty() || entry == "C" || entry == "POSIX")
                continue;
            languages.emplace_back(entry);
            const size_t territory {entry.find('_')};
            if (territory != std::string_view::npos)
                languages.emplace_back(entry.substr(0, territory));
        }
        return languages;
    }

    // Must be called with sMutex held.
    const Catalog* catalogFor(const std::string& domain, const std::string& language)
    {
        const auto binding = sBindings.find(domain);
        const std::string directory {binding == sBindings.cend() ? std::string {}
                                                                   : binding->second};
        if (directory.empty())
            return nullptr;
        const std::string path {directory + "/" + language + "/LC_MESSAGES/" + domain + ".mo"};
        auto it = sCatalogs.find(path);
        if (it == sCatalogs.end()) {
            auto catalog = std::make_unique<Catalog>();
            if (!catalog->load(path))
                catalog.reset();
            it = sCatalogs.emplace(path, std::move(catalog)).first;
        }
        return it->second.get();
    }

    const char* translate(const char* domainArg,
                          const char* msgid1,
                          const char* msgid2,
                          unsigned long n,
                          bool plural)
    {
        if (msgid1 == nullptr)
            return nullptr;
        const char* fallback {(plural && n != 1 && msgid2 != nullptr) ? msgid2 : msgid1};

        std::lock_guard<std::mutex> lock {sMutex};
        const std::string domain {domainArg != nullptr ? domainArg : sDomain};
        for (const std::string& language : requestedLanguages()) {
            const Catalog* catalog {catalogFor(domain, language)};
            if (catalog == nullptr)
                continue;
            const char* translation {plural ? catalog->findPlural(msgid1, n) :
                                              catalog->find(msgid1)};
            if (translation != nullptr && translation[0] != '\0')
                return translation;
        }
        return fallback;
    }

    char* storeString(const std::string& key, const std::string& value)
    {
        std::string& stored {sReturnedStrings[key]};
        stored = value;
        return const_cast<char*>(stored.c_str());
    }
} // namespace

extern "C" {

char* gettext(const char* msgid)
{
    return const_cast<char*>(translate(nullptr, msgid, nullptr, 0, false));
}

char* dgettext(const char* domain, const char* msgid)
{
    return const_cast<char*>(translate(domain, msgid, nullptr, 0, false));
}

char* dcgettext(const char* domain, const char* msgid, int)
{
    return const_cast<char*>(translate(domain, msgid, nullptr, 0, false));
}

char* ngettext(const char* msgid1, const char* msgid2, unsigned long n)
{
    return const_cast<char*>(translate(nullptr, msgid1, msgid2, n, true));
}

char* dngettext(const char* domain, const char* msgid1, const char* msgid2, unsigned long n)
{
    return const_cast<char*>(translate(domain, msgid1, msgid2, n, true));
}

char* dcngettext(
    const char* domain, const char* msgid1, const char* msgid2, unsigned long n, int)
{
    return const_cast<char*>(translate(domain, msgid1, msgid2, n, true));
}

char* textdomain(const char* domain)
{
    std::lock_guard<std::mutex> lock {sMutex};
    if (domain != nullptr)
        sDomain = (domain[0] == '\0') ? "messages" : domain;
    return storeString("\x01textdomain", sDomain);
}

char* bindtextdomain(const char* domain, const char* dirname)
{
    if (domain == nullptr || domain[0] == '\0')
        return nullptr;
    std::lock_guard<std::mutex> lock {sMutex};
    if (dirname != nullptr)
        sBindings[domain] = dirname;
    const auto it = sBindings.find(domain);
    if (it == sBindings.cend())
        return nullptr;
    return storeString(std::string {"\x02"} + domain, it->second);
}

char* bind_textdomain_codeset(const char* domain, const char* codeset)
{
    if (domain == nullptr || domain[0] == '\0')
        return nullptr;
    std::lock_guard<std::mutex> lock {sMutex};
    // Catalogs are always UTF-8, the codeset is only recorded.
    if (codeset != nullptr)
        sCodesets[domain] = codeset;
    const auto it = sCodesets.find(domain);
    if (it == sCodesets.cend())
        return nullptr;
    return storeString(std::string {"\x03"} + domain, it->second);
}

} // extern "C"
