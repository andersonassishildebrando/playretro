//  SPDX-License-Identifier: MIT
//
//  PlayRetro
//  libintl.h
//
//  Minimal message catalog API for Android, implemented in intl.cpp. Only the subset of
//  the GNU gettext interface used by the application is provided.
//

#ifndef PLAYRETRO_LIBINTL_H
#define PLAYRETRO_LIBINTL_H

#include <locale.h>

#ifdef __cplusplus
extern "C" {
#endif

char* gettext(const char* msgid);
char* dgettext(const char* domain, const char* msgid);
char* dcgettext(const char* domain, const char* msgid, int category);
char* ngettext(const char* msgid1, const char* msgid2, unsigned long n);
char* dngettext(const char* domain, const char* msgid1, const char* msgid2, unsigned long n);
char* dcngettext(const char* domain,
                 const char* msgid1,
                 const char* msgid2,
                 unsigned long n,
                 int category);
char* textdomain(const char* domain);
char* bindtextdomain(const char* domain, const char* dirname);
char* bind_textdomain_codeset(const char* domain, const char* codeset);

#ifdef __cplusplus
}
#endif

#endif // PLAYRETRO_LIBINTL_H
