//  SPDX-License-Identifier: MIT
//
//  PlayRetro
//  PlatformUtilAndroid.cpp
//
//  Bridge between the C++ application and the Kotlin layer of the Android app.
//  All Kotlin entry points are @JvmStatic functions on org.play_retro.frontend.MainActivity,
//  and the native callbacks are declared in org.play_retro.frontend.NativeBridge.
//

#include "utils/PlatformUtilAndroid.h"

#include "Log.h"
#include "utils/FileSystemUtil.h"

#include <SDL2/SDL_system.h>

#include <jni.h>

#include <cstdarg>
#include <cstdlib>

namespace
{
    constexpr char kActivityClass[] {"org/play_retro/frontend/MainActivity"};
    constexpr char kActivityClassName[] {"org.play_retro.frontend.MainActivity"};

    // RAII wrapper for JNI local references.
    template <typename T> class LocalRef
    {
    public:
        LocalRef(JNIEnv* env, T ref)
            : mEnv {env}
            , mRef {ref}
        {
        }
        ~LocalRef()
        {
            if (mRef != nullptr)
                mEnv->DeleteLocalRef(mRef);
        }
        LocalRef(const LocalRef&) = delete;
        LocalRef& operator=(const LocalRef&) = delete;

        T get() const { return mRef; }
        explicit operator bool() const { return mRef != nullptr; }

    private:
        JNIEnv* mEnv;
        T mRef;
    };

    JNIEnv* getEnv() { return static_cast<JNIEnv*>(SDL_AndroidGetJNIEnv()); }

    // The class is loaded through the activity's class loader rather than with FindClass(), as
    // FindClass() on a native thread uses the system class loader which can't see app classes.
    // The running activity may be a subclass (the home app variant), so its own class isn't used.
    jclass getActivityClass(JNIEnv* env)
    {
        static jclass activityClass {nullptr};
        if (activityClass == nullptr) {
            LocalRef<jobject> activity {env, static_cast<jobject>(SDL_AndroidGetActivity())};
            LocalRef<jclass> objectClass {env, env->GetObjectClass(activity.get())};
            jmethodID getClassLoader {
                env->GetMethodID(objectClass.get(), "getClassLoader", "()Ljava/lang/ClassLoader;")};
            LocalRef<jobject> classLoader {
                env, env->CallObjectMethod(activity.get(), getClassLoader)};
            LocalRef<jclass> loaderClass {env, env->FindClass("java/lang/ClassLoader")};
            jmethodID loadClass {env->GetMethodID(loaderClass.get(), "loadClass",
                                                  "(Ljava/lang/String;)Ljava/lang/Class;")};
            LocalRef<jstring> className {env, env->NewStringUTF(kActivityClassName)};
            LocalRef<jclass> loaded {env, static_cast<jclass>(env->CallObjectMethod(
                                              classLoader.get(), loadClass, className.get()))};
            if (env->ExceptionCheck() || !loaded) {
                env->ExceptionClear();
                LOG(LogError) << "PlatformUtilAndroid: Couldn't load " << kActivityClassName
                              << ", falling back to the activity class";
                activityClass = static_cast<jclass>(env->NewGlobalRef(objectClass.get()));
            }
            else {
                activityClass = static_cast<jclass>(env->NewGlobalRef(loaded.get()));
            }
        }
        return activityClass;
    }

    jmethodID getMethod(JNIEnv* env, const char* name, const char* signature)
    {
        jmethodID method {env->GetStaticMethodID(getActivityClass(env), name, signature)};
        if (method == nullptr) {
            env->ExceptionClear();
            LOG(LogError) << "PlatformUtilAndroid: Couldn't find " << kActivityClass << "."
                          << name << signature;
        }
        return method;
    }

    bool checkException(JNIEnv* env, const char* name)
    {
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            LOG(LogError) << "PlatformUtilAndroid: Exception thrown by " << name << "()";
            return true;
        }
        return false;
    }

    std::string toString(JNIEnv* env, jstring string)
    {
        if (string == nullptr)
            return "";
        const char* chars {env->GetStringUTFChars(string, nullptr)};
        std::string result {chars};
        env->ReleaseStringUTFChars(string, chars);
        return result;
    }

    jstring toJString(JNIEnv* env, const std::string& string)
    {
        return env->NewStringUTF(string.c_str());
    }

    jobjectArray toJStringArray(JNIEnv* env, const std::vector<std::string>& strings)
    {
        LocalRef<jclass> stringClass {env, env->FindClass("java/lang/String")};
        jobjectArray array {
            env->NewObjectArray(static_cast<jsize>(strings.size()), stringClass.get(), nullptr)};
        for (size_t i {0}; i < strings.size(); ++i) {
            LocalRef<jstring> element {env, toJString(env, strings[i])};
            env->SetObjectArrayElement(array, static_cast<jsize>(i), element.get());
        }
        return array;
    }

    // Maps are passed to Kotlin as flat key/value arrays.
    jobjectArray toJStringArray(JNIEnv* env, const std::map<std::string, std::string>& map)
    {
        std::vector<std::string> flat;
        flat.reserve(map.size() * 2);
        for (auto& [key, value] : map) {
            flat.emplace_back(key);
            flat.emplace_back(value);
        }
        return toJStringArray(env, flat);
    }

    std::vector<std::string> toStringVector(JNIEnv* env, jobjectArray array)
    {
        std::vector<std::string> result;
        if (array == nullptr)
            return result;
        const jsize length {env->GetArrayLength(array)};
        result.reserve(length);
        for (jsize i {0}; i < length; ++i) {
            LocalRef<jstring> element {
                env, static_cast<jstring>(env->GetObjectArrayElement(array, i))};
            result.emplace_back(toString(env, element.get()));
        }
        return result;
    }

    std::pair<int, int> toIntPair(JNIEnv* env, jintArray array, std::pair<int, int> fallback)
    {
        if (array == nullptr || env->GetArrayLength(array) < 2)
            return fallback;
        jint values[2];
        env->GetIntArrayRegion(array, 0, 2, values);
        return {values[0], values[1]};
    }

    // Typed helpers for calling static Kotlin methods.
    bool callBool(const char* name, const char* signature, bool fallback, ...)
    {
        JNIEnv* env {getEnv()};
        jmethodID method {getMethod(env, name, signature)};
        if (method == nullptr)
            return fallback;
        va_list args;
        va_start(args, fallback);
        const jboolean result {env->CallStaticBooleanMethodV(getActivityClass(env), method, args)};
        va_end(args);
        return checkException(env, name) ? fallback : result == JNI_TRUE;
    }

    int callInt(const char* name, const char* signature, int fallback, ...)
    {
        JNIEnv* env {getEnv()};
        jmethodID method {getMethod(env, name, signature)};
        if (method == nullptr)
            return fallback;
        va_list args;
        va_start(args, fallback);
        const jint result {env->CallStaticIntMethodV(getActivityClass(env), method, args)};
        va_end(args);
        return checkException(env, name) ? fallback : static_cast<int>(result);
    }

    void callVoid(const char* name, const char* signature, ...)
    {
        JNIEnv* env {getEnv()};
        jmethodID method {getMethod(env, name, signature)};
        if (method == nullptr)
            return;
        va_list args;
        va_start(args, signature);
        env->CallStaticVoidMethodV(getActivityClass(env), method, args);
        va_end(args);
        checkException(env, name);
    }

    std::string callString(const char* name)
    {
        JNIEnv* env {getEnv()};
        jmethodID method {getMethod(env, name, "()Ljava/lang/String;")};
        if (method == nullptr)
            return "";
        LocalRef<jstring> result {
            env, static_cast<jstring>(env->CallStaticObjectMethod(getActivityClass(env), method))};
        if (checkException(env, name))
            return "";
        return toString(env, result.get());
    }

    std::pair<int, int> callIntPair(const char* name, std::pair<int, int> fallback)
    {
        JNIEnv* env {getEnv()};
        jmethodID method {getMethod(env, name, "()[I")};
        if (method == nullptr)
            return fallback;
        LocalRef<jintArray> result {env, static_cast<jintArray>(env->CallStaticObjectMethod(
                                             getActivityClass(env), method))};
        if (checkException(env, name))
            return fallback;
        return toIntPair(env, result.get(), fallback);
    }

} // namespace

namespace Utils
{
    namespace Platform
    {
        namespace Android
        {
            bool checkConfigurationNeeded()
            {
                return callBool("checkConfigurationNeeded", "()Z", true);
            }

            void startConfigurator()
            {
                // Cleared by NativeBridge.nativeSetHold(false) when onboarding finishes.
                AndroidVariables::sHold = true;
                AndroidVariables::sResetTouchOverlay = true;
                callVoid("startConfigurator", "()V");
            }

            void setDataDirectories()
            {
                // getAppDataDirectory() resolves to the home path on Android.
                const std::string appDataDirectory {callString("getAppDataDirectory")};
                FileSystemVariables::sAppDataDirectory =
                    Utils::FileSystem::getGenericPath(appDataDirectory);
                setenv("HOME", appDataDirectory.c_str(), 1);
                AndroidVariables::sInternalDataDirectory = callString("getInternalDataDirectory");
                AndroidVariables::sExternalDataDirectory = callString("getExternalDataDirectory");
            }

            void setROMDirectory()
            {
                // Callers append system names directly, so a trailing slash is required.
                std::string romDirectory {callString("getROMDirectory")};
                if (!romDirectory.empty() && romDirectory.back() != '/')
                    romDirectory.push_back('/');
                AndroidVariables::sROMDirectory = romDirectory;
            }

            bool getCreateSystemDirectories()
            {
                return callBool("getCreateSystemDirectories", "()Z", false);
            }

            // These expand %INTERNALDATA% and %EXTERNALDATA% in launch commands, i.e. the
            // per-user roots /data/user/<userid> and /storage/emulated/<userid>, not the
            // application's own directories.
            std::string getInternalDirectory()
            {
                static const std::string directory {callString("getUserInternalDirectory")};
                return directory;
            }

            std::string getExternalDirectory()
            {
                static const std::string directory {callString("getUserExternalDirectory")};
                return directory;
            }

            bool checkNeedResourceCopy(const std::string& buildIdentifier)
            {
                JNIEnv* env {getEnv()};
                LocalRef<jstring> identifier {env, toJString(env, buildIdentifier)};
                return callBool("checkNeedResourceCopy", "(Ljava/lang/String;)Z", true,
                                identifier.get());
            }

            void setupFontFiles() { callVoid("setupFontFiles", "()V"); }

            void setupLocalizationFiles() { callVoid("setupLocalizationFiles", "()V"); }

            bool setupResources(const std::string& buildIdentifier)
            {
                JNIEnv* env {getEnv()};
                LocalRef<jstring> identifier {env, toJString(env, buildIdentifier)};
                // Kotlin returns true on success.
                return !callBool("setupResources", "(Ljava/lang/String;)Z", false,
                                 identifier.get());
            }

            void printDeviceInfo()
            {
                const std::string deviceInfo {callString("getDeviceInfo")};
                if (!deviceInfo.empty())
                    LOG(LogInfo) << deviceInfo;
            }

            void onResume()
            {
                AndroidVariables::sIsHomeApp = callBool("isHomeApp", "()Z", false);
            }

            std::pair<int, int> getWindowSize() { return callIntPair("getWindowSize", {0, 0}); }

            bool getBluetoothStatus() { return callBool("getBluetoothStatus", "()Z", false); }

            int getWifiStatus() { return callInt("getWifiStatus", "()I", -1); }

            int getCellularStatus() { return callInt("getCellularStatus", "()I", -1); }

            std::pair<int, int> getBatteryStatus()
            {
                return callIntPair("getBatteryStatus", {-1, -1});
            }

            bool checkEmulatorInstalled(const std::string& packageName,
                                        const std::string& activity)
            {
                JNIEnv* env {getEnv()};
                LocalRef<jstring> package {env, toJString(env, packageName)};
                LocalRef<jstring> activityName {env, toJString(env, activity)};
                return callBool("checkEmulatorInstalled", "(Ljava/lang/String;Ljava/lang/String;)Z",
                                false, package.get(), activityName.get());
            }

            int checkRACoreInstalled(const std::string& packageName, const std::string& coreFile)
            {
                JNIEnv* env {getEnv()};
                LocalRef<jstring> package {env, toJString(env, packageName)};
                LocalRef<jstring> core {env, toJString(env, coreFile)};
                return callInt("checkRACoreInstalled", "(Ljava/lang/String;Ljava/lang/String;)I",
                               -2, package.get(), core.get());
            }

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
                           bool launchOnOtherScreen)
            {
                JNIEnv* env {getEnv()};
                LocalRef<jstring> jPackage {env, toJString(env, packageName)};
                LocalRef<jstring> jActivity {env, toJString(env, activity)};
                LocalRef<jstring> jAction {env, toJString(env, action)};
                LocalRef<jstring> jCategory {env, toJString(env, category)};
                LocalRef<jstring> jMimeType {env, toJString(env, mimeType)};
                LocalRef<jstring> jData {env, toJString(env, data)};
                LocalRef<jstring> jStartPath {env, toJString(env, startPath)};
                LocalRef<jstring> jRomRaw {env, toJString(env, romRaw)};
                LocalRef<jobjectArray> jExtrasString {env, toJStringArray(env, extrasString)};
                LocalRef<jobjectArray> jExtrasStringArray {env,
                                                           toJStringArray(env, extrasStringArray)};
                LocalRef<jobjectArray> jExtrasInteger {env, toJStringArray(env, extrasInteger)};
                LocalRef<jobjectArray> jExtrasBool {env, toJStringArray(env, extrasBool)};
                LocalRef<jobjectArray> jFlags {env, toJStringArray(env, activityFlags)};

                return callInt("launchGame",
                               "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
                               "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
                               "Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;"
                               "[Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;"
                               "[Ljava/lang/String;Z)I",
                               -1, jPackage.get(), jActivity.get(), jAction.get(),
                               jCategory.get(), jMimeType.get(), jData.get(), jStartPath.get(),
                               jRomRaw.get(), jExtrasString.get(), jExtrasStringArray.get(),
                               jExtrasInteger.get(), jExtrasBool.get(), jFlags.get(),
                               static_cast<jboolean>(launchOnOtherScreen));
            }

            void getInstalledApps(std::vector<std::pair<std::string, std::string>>& appList,
                                  bool gamesOnly,
                                  bool importMedia)
            {
                JNIEnv* env {getEnv()};
                jmethodID method {getMethod(env, "getInstalledApps", "(ZZ)[Ljava/lang/String;")};
                if (method == nullptr)
                    return;
                LocalRef<jobjectArray> result {
                    env, static_cast<jobjectArray>(env->CallStaticObjectMethod(
                             getActivityClass(env), method, static_cast<jboolean>(gamesOnly),
                             static_cast<jboolean>(importMedia)))};
                if (checkException(env, "getInstalledApps"))
                    return;
                const std::vector<std::string> flat {toStringVector(env, result.get())};
                for (size_t i {0}; i + 1 < flat.size(); i += 2)
                    appList.emplace_back(flat[i], flat[i + 1]);
            }

        } // namespace Android
    } // namespace Platform
} // namespace Utils

// Native methods of org.play_retro.frontend.NativeBridge.
extern "C" {

JNIEXPORT void JNICALL Java_org_play_1retro_frontend_NativeBridge_nativeSetHold(JNIEnv*,
                                                                               jclass,
                                                                               jboolean hold)
{
    AndroidVariables::sHold = (hold == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_org_play_1retro_frontend_NativeBridge_nativeSetHomeApp(
    JNIEnv*, jclass, jboolean isHomeApp)
{
    AndroidVariables::sIsHomeApp = (isHomeApp == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_org_play_1retro_frontend_NativeBridge_nativeLog(JNIEnv* env,
                                                                           jclass,
                                                                           jstring message,
                                                                           jint level)
{
    const std::string text {toString(env, message)};
    switch (level) {
        case 0: {
            LOG(LogError) << text;
            break;
        }
        case 1: {
            LOG(LogWarning) << text;
            break;
        }
        case 2: {
            LOG(LogInfo) << text;
            break;
        }
        default: {
            LOG(LogDebug) << text;
            break;
        }
    }
}

} // extern "C"
