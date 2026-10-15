//  SPDX-License-Identifier: MIT
//
//  Play Retro
//  Branding.h
//
//  Application identity and third-party service credentials.
//  Credentials are injected at build time (see CMakeLists.txt) and are empty by default,
//  in which case the corresponding service is unavailable.
//

#ifndef ES_CORE_BRANDING_H
#define ES_CORE_BRANDING_H

#define APPLICATION_NAME "Play Retro"
#define APPLICATION_NAME_UPPER "PLAY RETRO"
#define APPLICATION_DIRECTORY_NAME "PlayRetro"
#define APPLICATION_USER_AGENT "PlayRetro/"
#define APPLICATION_SCRAPER_SOFT_NAME "PlayRetro"

// Leave empty to disable the in-app updater (Android builds are updated via the app store).
#if !defined(APPLICATION_UPDATER_URL)
#define APPLICATION_UPDATER_URL ""
#endif
#if !defined(APPLICATION_WEBSITE)
#define APPLICATION_WEBSITE ""
#endif

#if !defined(THEMES_LIST_REPOSITORY_URL)
#define THEMES_LIST_REPOSITORY_URL "https://github.com/andersonassishildebrando/playretro-themes-list.git"
#endif

// Developer credentials registered for PlayRetro at screenscraper.fr and thegamesdb.net.
#if !defined(SCREENSCRAPER_DEV_ID)
#define SCREENSCRAPER_DEV_ID ""
#endif
#if !defined(SCREENSCRAPER_DEV_PASSWORD)
#define SCREENSCRAPER_DEV_PASSWORD ""
#endif
#if !defined(THEGAMESDB_API_KEY)
#define THEGAMESDB_API_KEY ""
#endif

#endif // ES_CORE_BRANDING_H
