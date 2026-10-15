//  SPDX-License-Identifier: MIT
//
//  PlayRetro
//  PlatformUtilAndroid.h
//
//  Bridge between the C++ application and the Kotlin layer of the Android app
//  (org.play_retro.frontend.MainActivity). Calls into Kotlin go through JNI using the
//  environment and activity provided by SDL; calls from Kotlin arrive via the native
//  methods implemented in PlatformUtilAndroid.cpp.
//

#ifndef ES_CORE_UTILS_PLATFORM_UTIL_ANDROID_H
#define ES_CORE_UTILS_PLATFORM_UTIL_ANDROID_H

#include <atomic>
#include <map>
#include <string>
#include <utility>
#include <vector>

namespace AndroidVariables
{
    // Set while the onboarding activity is shown; the main thread waits for it to clear.
    inline std::atomic<bool> sHold {false};
    inline std::atomic<bool> sIsHomeApp {false};
    // Set when onboarding was run, so the touch input is re-enabled in case the user had
    // locked themselves out by disabling it.
    inline std::atomic<bool> sResetTouchOverlay {false};
    inline std::string sInternalDataDirectory;
    inline std::string sExternalDataDirectory;
    inline std::string sROMDirectory;
} // namespace AndroidVariables

namespace Utils
{
    namespace Platform
    {
        namespace Android
        {
            // Onboarding and directories.
            bool checkConfigurationNeeded();
            void startConfigurator();
            void setDataDirectories();
            void setROMDirectory();
            bool getCreateSystemDirectories();
            std::string getInternalDirectory();
            std::string getExternalDirectory();

            // Bundled assets. setupResources() returns true on failure.
            bool checkNeedResourceCopy(const std::string& buildIdentifier);
            void setupFontFiles();
            void setupLocalizationFiles();
            bool setupResources(const std::string& buildIdentifier);

            // Device state.
            void printDeviceInfo();
            void onResume();
            std::pair<int, int> getWindowSize();
            bool getBluetoothStatus();
            // 1 if connected, 0 if not, -1 if unknown.
            int getWifiStatus();
            int getCellularStatus();
            // First: 1 charging, 0 discharging, -1 no battery. Second: capacity in percent or -1.
            std::pair<int, int> getBatteryStatus();

            // Emulators and native apps.
            bool checkEmulatorInstalled(const std::string& packageName,
                                        const std::string& activity);
            // 1 installed, 0 not installed, -1 timed out, -2 unknown error.
            int checkRACoreInstalled(const std::string& packageName, const std::string& coreFile);
            // Returns 0 on success.
            int launchGame(const std::string& packageName,
                           const std::string& activity,
                           const std::string& action,
                           const std::string& category,
                           const std::string& mimeType,
                           const std::string& data,
                           const std::string& startPath,
                           const std::string& romRaw,
                           const std::map<std::string, std::string>& extrasString,
                           const std::map<std::string, std::string>& extrasStringArray,
                           const std::map<std::string, std::string>& extrasInteger,
                           const std::map<std::string, std::string>& extrasBool,
                           const std::vector<std::string>& activityFlags,
                           bool launchOnOtherScreen);
            // Fills appList with (display name, package name) pairs and writes icons, and
            // optionally banners, to <app data>/importer_temp/{icons,media}.
            void getInstalledApps(std::vector<std::pair<std::string, std::string>>& appList,
                                  bool gamesOnly,
                                  bool importMedia);

        } // namespace Android
    } // namespace Platform
} // namespace Utils

#endif // ES_CORE_UTILS_PLATFORM_UTIL_ANDROID_H
