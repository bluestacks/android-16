/***********************************************************************
# Copyright (C) 2020 BlueStack Systems, Inc.
# All Rights Reserved
#
# THIS IS UNPUBLISHED PROPRIETARY SOURCE CODE OF BLUESTACK SYSTEMS, INC.
# The copyright notice above does not evidence any actual or intended
# publication of such source code.
#************************************************************************/

#define LOG_TAG "BstGCallService-JNI"

#include <errno.h>
#include <fcntl.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <unistd.h>

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <cutils/properties.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalRef.h>
#include <utils/Log.h>
#include <utils/misc.h>

#include "Gcall.h"
#include "guest/GcallDec.h"
#include "Hcall.h"
#include "Xpl.h"
#include "XthrPool.h"
#include "InpCommon.h"

#include <linux/uinput.h>

#include <dirent.h>
#include <sys/stat.h>

XLOG_SET_MODULE (XLOG_MODULE_GCALL);

typedef struct ThreadPool {
    XthrPool threadPool;
} ThreadPool;

ThreadPool g_thr_pool;

typedef struct AccelerometerData {
    i32 	x;
    i32 	y;
    i32 	z;
} AccelerometerData;

static int g_bstsensor_pipe = -1;

static JavaVM *g_javaVM;
static JNIEnv *g_env;

static jclass g_bstCommandLoopClass;
static jobject g_bstCommandLoopObject;

static jmethodID g_setDeviceProfileMethod;
static jmethodID g_setCustomDeviceProfileMethod;
static jmethodID g_setLocaleMethod;
static jmethodID g_rootDeviceMethod;
static jmethodID g_enableInputDebuggingMethod;
static jmethodID g_setDevicePreferredOrientationMethod;
static jmethodID g_setMaxFpsMethod;
static jmethodID g_installApkMethod;
static jmethodID g_installStudioZipMethod;
static jmethodID g_uninstallAppMethod;
static jmethodID g_stopAppMethod;
static jmethodID g_takeScreenshotMethod;
static jmethodID g_launchActivityMethod;
static jmethodID g_reLaunchActivityMethod;
static jmethodID g_launchUrlMethod;
static jmethodID g_importFilesMethod;
static jmethodID g_exportFilesMethod;
static jmethodID g_enableAdbMethod;
static jmethodID g_setClipboardMethod;
static jmethodID g_setLocalTimeMethod;
static jmethodID g_clearAppDataMethod;
static jmethodID g_launchAppStoreMethod;
static jmethodID g_setVolumeMethod;
static jmethodID g_launchAppStoreSearchMethod;
static jmethodID g_setGamepadStateMethod;
static jmethodID g_affiliateTrackingForPackageMethod;
static jmethodID g_getNowggAccountsMethod;
static jmethodID g_addNowggAccountMethod;
static jmethodID g_removeNowggAccountMethod;
static jmethodID g_enableSignInPopupMethod;
static jmethodID g_enableClickSound;
static jmethodID g_showNativeMousePointer;
static jmethodID g_setDifferentImagePkgs;
static jmethodID g_setCustomAppOrientation;
static jmethodID g_setAirplaneModeMethod;
static jmethodID g_startRecording;
static jmethodID g_enableAndroidAds;
static jmethodID g_androidInterstitialAdSetting;
static jmethodID g_commonCommandMethod;
static jmethodID g_onUnzipFileCompletedMethod;
static jmethodID g_startInstallAppGameCenterMethod;
static jmethodID g_startUiDumpMethod;
static jmethodID g_inputSwipeCommandMethod;
static jmethodID g_inputTapCommandMethod;
static jmethodID g_inputPressKeyCommandMethod;
static jmethodID g_inputSetTextCommandMethod;
static jmethodID g_agentImportFilesClbk;
static jmethodID g_agentExportFilesClbk;

static bool dbg = false;
static bool isAbsoluteMouse = true;


/*
 * Gcall utility functions
 */
// Get JNIEnv interface pointer for current thread
JNIEnv *JNU_GetEnv() {
    JNIEnv* env = android::AndroidRuntime::getJNIEnv();
    if (env == NULL) {
        JavaVMAttachArgs args = {JNI_VERSION_1_4, NULL, NULL};
        int result = g_javaVM->AttachCurrentThread(&env, (void*) &args);
        if (result != JNI_OK) {
            ALOGE("%s: Thread attach failed: %#x", __func__, result);
            return NULL;
        }
    }
    return env;
}

void JNU_detachJNI() {
    int result = g_javaVM->DetachCurrentThread();
    if (result != JNI_OK) {
        ALOGE("%s: Thread detach failed: %#x", __func__, result);
    }
}

void checkAndClearExceptionFromCallback(JNIEnv* env, const char* methodName) {
    if (env->ExceptionCheck()) {
        ALOGE("An exception was thrown by callback '%s'.", methodName);
        LOGE_EX(env);
        env->ExceptionClear();
    }
}

static bool debug()
{
    char prop[PROPERTY_VALUE_MAX];
    property_get("bst.debug.bstcmdloop", prop, "0");
    if (!atoi(prop)) {
        return false;
    } else {
        return true;
    }
}

void _gcallSetDifferentImagePkgsClbk(string fileNameStr) {
    const char* fileName = fileNameStr.c_str();
    if (dbg) ALOGD("%s called", __func__);

    jstring jFileName = g_env->NewStringUTF(fileName);
    if (jFileName == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, fileName);
        goto out;
    }

    if (dbg) ALOGD("%s calling JAVA callback function", __func__);
    g_env->CallVoidMethod(g_bstCommandLoopObject, g_setDifferentImagePkgs, jFileName);
    checkAndClearExceptionFromCallback(g_env, __func__);

out:
    if (jFileName)
        g_env->DeleteLocalRef(jFileName);

    return;
}

void _gcallSetCustomAppOrientationClbk(string gameSettingJsonStr) {
    const char* gameSettingJson = gameSettingJsonStr.c_str();
    if (dbg) ALOGD("%s called", __func__);

    jstring jGameSettingJson = g_env->NewStringUTF(gameSettingJson);
    if (jGameSettingJson == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, gameSettingJson);
        goto out;
    }

    if (dbg) ALOGD("%s calling JAVA callback function", __func__);
    g_env->CallVoidMethod(g_bstCommandLoopClass, g_setCustomAppOrientation, jGameSettingJson);
    checkAndClearExceptionFromCallback(g_env, __func__);

out:
    if (jGameSettingJson)
        g_env->DeleteLocalRef(jGameSettingJson);

    return;
}

void _gcallAndroidInterstitialAdSettingClbk(string dataStr) {
    const char* data = dataStr.c_str();
    if (dbg) ALOGD("%s called", __func__);

    jstring jData = g_env->NewStringUTF(data);
    if (jData == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, data);
        goto out;
    }

    if (dbg) ALOGD("%s calling JAVA callback function", __func__);
    g_env->CallVoidMethod(g_bstCommandLoopClass, g_androidInterstitialAdSetting, jData);
    checkAndClearExceptionFromCallback(g_env, __func__);

out:
    if (jData)
        g_env->DeleteLocalRef(jData);

    return;
}

void _gcallAgentImportFilesClbk(string requestIdStr, string payloadStr)
{
    const char* requestId = requestIdStr.c_str();
    const char* payload = payloadStr.c_str();
    ALOGD("_gcallAgentImportFilesClbk called with requestId: %s, payload: %s", requestId, payload);

    jstring jpayload = g_env->NewStringUTF(payload);
    if (jpayload == NULL) {
        ALOGE("Failed to create jstring from payload in _gcallAgentImportFilesClbk");
        hcallAgentFileTransferCompletedRpc(requestId, "{\"status\":\"failure\", \"error\":\"JNI could not create string\"}");
        return;
    }

    jstring jResultJson = (jstring)g_env->CallObjectMethod(g_bstCommandLoopObject, g_agentImportFilesClbk, jpayload);
    g_env->DeleteLocalRef(jpayload);

    if (g_env->ExceptionCheck()) {
        ALOGE("Exception calling agentImportFilesClbk");
        g_env->ExceptionDescribe();
        g_env->ExceptionClear();
        hcallAgentFileTransferCompletedRpc(requestId, "{\"status\":\"failure\", \"error\":\"Java exception occurred during agentImportFilesClbk\"}");
        return;
    }

    if (jResultJson != NULL) {
        const char* resultJsonCStr = g_env->GetStringUTFChars(jResultJson, NULL);
        if (resultJsonCStr) {
            ALOGD("hcallAgentFileTransferCompletedRpc with requestId: %s, result: %s", requestId, resultJsonCStr);
            hcallAgentFileTransferCompletedRpc(requestId, resultJsonCStr);
            g_env->ReleaseStringUTFChars(jResultJson, resultJsonCStr);
        } else {
            hcallAgentFileTransferCompletedRpc(requestId, "{\"status\":\"failure\", \"error\":\"Failed to get result JSON from Java\"}");
        }
        g_env->DeleteLocalRef(jResultJson);
    } else {
        ALOGE("NULL result from agentImportFilesClbk");
        hcallAgentFileTransferCompletedRpc(requestId, "{\"status\":\"failure\", \"error\":\"NULL result from Java method\"}");
    }
}

void _gcallAgentExportFilesClbk(string requestIdStr, string payloadStr)
{
    const char* requestId = requestIdStr.c_str();
    const char* payload = payloadStr.c_str();
    ALOGD("_gcallAgentExportFilesClbk called with requestId: %s, payload: %s", requestId, payload);

    jstring jpayload = g_env->NewStringUTF(payload);
    if (jpayload == NULL) {
        ALOGE("Failed to create jstring from payload in _gcallAgentExportFilesClbk");
        hcallAgentFileTransferCompletedRpc(requestId, "{\"status\":\"failure\", \"error\":\"JNI could not create string\"}");
        return;
    }

    jstring jResultJson = (jstring)g_env->CallObjectMethod(g_bstCommandLoopObject, g_agentExportFilesClbk, jpayload);
    g_env->DeleteLocalRef(jpayload);

    if (g_env->ExceptionCheck()) {
        ALOGE("Exception calling agentExportFilesClbk");
        g_env->ExceptionDescribe();
        g_env->ExceptionClear();
        hcallAgentFileTransferCompletedRpc(requestId, "{\"status\":\"failure\", \"error\":\"Java exception occurred during agentExportFilesClbk\"}");
        return;
    }

    if (jResultJson != NULL) {
        const char* resultJsonCStr = g_env->GetStringUTFChars(jResultJson, NULL);
        if (resultJsonCStr) {
            ALOGD("hcallAgentFileTransferCompletedRpc with requestId: %s, result: %s", requestId, resultJsonCStr);
            hcallAgentFileTransferCompletedRpc(requestId, resultJsonCStr);
            g_env->ReleaseStringUTFChars(jResultJson, resultJsonCStr);
        } else {
            hcallAgentFileTransferCompletedRpc(requestId, "{\"status\":\"failure\", \"error\":\"Failed to get result JSON from Java\"}");
        }
        g_env->DeleteLocalRef(jResultJson);
    } else {
        ALOGE("NULL result from agentExportFilesClbk");
        hcallAgentFileTransferCompletedRpc(requestId, "{\"status\":\"failure\", \"error\":\"NULL result from Java method\"}");
    }
}

 /*
 * Gcall handler functions.
 */
void _gcallSetDeviceProfileClbk(string deviceProfileCodeStr, string deviceCarrierCodeStr) {
    const char* deviceProfileCode = deviceProfileCodeStr.c_str();
    const char* deviceCarrierCode = deviceCarrierCodeStr.c_str();
    if (dbg) ALOGD("%s called: deviceProfileCode %s, deviceCarrierCode %s", __func__, deviceProfileCode, deviceCarrierCode);
    jint status = -1;

    jstring jDeviceCarrierCode = NULL;
    jstring jDeviceProfileCode = g_env->NewStringUTF(deviceProfileCode);
    if (jDeviceProfileCode == NULL) {
        ALOGE("%s OOM error for NewStringUTF %s", __func__, deviceProfileCode);
        goto out;
    }
    jDeviceCarrierCode = g_env->NewStringUTF(deviceCarrierCode);
    if (jDeviceCarrierCode == NULL) {
        ALOGE("%s OOM error for NewStringUTF %s", __func__, deviceCarrierCode);
        goto out;
    }

    if (dbg) ALOGD("%s calling JAVA callback function with args: deviceProfileCode= %s and deviceCarrierCode = %s", __func__, deviceProfileCode, deviceCarrierCode);
    status = g_env->CallIntMethod(g_bstCommandLoopObject, g_setDeviceProfileMethod, jDeviceProfileCode, jDeviceCarrierCode);
    checkAndClearExceptionFromCallback(g_env, __func__);

out:
    if (dbg) ALOGD("%s returning %d using hcall function", __func__, status);
    xerr_t rval = hcallOnSetDeviceProfileCompletedRpc(status);
    if (rval != XERR_SUCCESS) {
        ALOGE("RPC failed for %s, error %d", __func__, rval);
    } else {
        ALOGI("%s returning , hcall rval = %d", __func__, rval);
    }

    if (jDeviceProfileCode)
        g_env->DeleteLocalRef(jDeviceProfileCode);
    if (jDeviceCarrierCode)
        g_env->DeleteLocalRef(jDeviceCarrierCode);

    return;
}

void _gcallSetCustomDeviceProfileClbk(string deviceManufacturerStr, string deviceBrandStr, string deviceModelStr, string deviceCarrierCodeStr) {
    const char* deviceManufacturer = deviceManufacturerStr.c_str();
    const char* deviceBrand = deviceBrandStr.c_str();
    const char* deviceModel = deviceModelStr.c_str();
    const char* deviceCarrierCode = deviceCarrierCodeStr.c_str();
    if (dbg) ALOGD("%s called: deviceManufacturer %s, deviceBrand %s, deviceModel %s, deviceCarrierCode %s", __func__, deviceManufacturer, deviceBrand, deviceModel, deviceCarrierCode);
    jint status = -1;

    jstring jDeviceManufacturer = g_env->NewStringUTF(deviceManufacturer);
    jstring jDeviceBrand = g_env->NewStringUTF(deviceBrand);
    jstring jDeviceModel = g_env->NewStringUTF(deviceModel);
    jstring jDeviceCarrierCode = g_env->NewStringUTF(deviceCarrierCode);
    if (jDeviceManufacturer == NULL) {
        ALOGE("%s OOM error for NewStringUTF %s", __func__, deviceManufacturer);
        goto out;
    }
    if (jDeviceBrand == NULL) {
        ALOGE("%s OOM error for NewStringUTF %s", __func__, deviceBrand);
        goto out;
    }
    if (jDeviceModel == NULL) {
        ALOGE("%s OOM error for NewStringUTF %s", __func__, deviceModel);
        goto out;
    }
    if (jDeviceCarrierCode == NULL) {
        ALOGE("%s OOM error for NewStringUTF %s", __func__, deviceCarrierCode);
        goto out;
    }

    if (dbg) ALOGD("%s calling JAVA callback function with args: deviceManufacturer = %s, deviceBrand = %s, deviceModel = %s, deviceCarrierCode = %s", __func__, deviceManufacturer, deviceBrand, deviceModel, deviceCarrierCode);
    status = g_env->CallIntMethod(g_bstCommandLoopObject, g_setCustomDeviceProfileMethod, jDeviceManufacturer, jDeviceBrand, jDeviceModel, jDeviceCarrierCode);
    checkAndClearExceptionFromCallback(g_env, __func__);

out:
    if (dbg) ALOGD("%s returning %d using hcall function", __func__, status);
    xerr_t rval = hcallOnSetDeviceProfileCompletedRpc(status);
    if (rval != XERR_SUCCESS) {
        ALOGE("RPC failed for %s, error %d", __func__, rval);
    } else {
        ALOGI("%s returning , hcall rval = %d", __func__, rval);
    }

    if (jDeviceManufacturer)
        g_env->DeleteLocalRef(jDeviceManufacturer);
    if (jDeviceBrand)
        g_env->DeleteLocalRef(jDeviceBrand);
    if (jDeviceModel)
        g_env->DeleteLocalRef(jDeviceModel);
    if (jDeviceCarrierCode)
        g_env->DeleteLocalRef(jDeviceCarrierCode);

    return;
}

void _gcallSetLocaleClbk(string localeStr) {
    const char* locale = localeStr.c_str();
    if (dbg) ALOGD("%s called: locale = %s", __func__, locale);
    jint status = -1;

    jstring jLocale = g_env->NewStringUTF(locale);
    if (jLocale == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, locale);
        goto out;
    }

    if (dbg) ALOGD("%s calling JAVA callback function with args: locale = %s", __func__, locale);
    status = g_env->CallIntMethod(g_bstCommandLoopObject, g_setLocaleMethod, jLocale);
    checkAndClearExceptionFromCallback(g_env, __func__);

out:
    if (dbg) ALOGD("%s returning %d using hcall function", __func__, status);

    xerr_t rval = hcallOnSetLocaleCompletedRpc(status);
    if (rval != XERR_SUCCESS) {
        ALOGE("RPC failed for %s, error %d", __func__, rval);
    } else {
        ALOGI("%s returning , hcall rval = %d", __func__, rval);
    }
    if (jLocale)
        g_env->DeleteLocalRef(jLocale);

    return;
}

void _gcallRootDeviceClbk(bool root) {
    if (dbg) ALOGD("%s called: root = %d", __func__, root);
    jint status = -1;

    if (dbg) ALOGD("%s calling JAVA callback function with args: root = %d", __func__, root);
    status = g_env->CallIntMethod(g_bstCommandLoopObject, g_rootDeviceMethod, root);
    checkAndClearExceptionFromCallback(g_env, __func__);

    if (dbg) ALOGD("%s returning %d using hcall function", __func__, status);
    xerr_t rval = hcallOnRootDeviceCompletedRpc(status);
    if (rval != XERR_SUCCESS) {
        ALOGE("RPC failed for %s, error %d", __func__, rval);
    } else {
        ALOGI("%s returning , hcall rval = %d", __func__, rval);
    }
    return;
}

void _gcallEnableInputDebuggingClbk(bool showTouches, bool showPointerLocation) {
    if (dbg) ALOGD("%s called, showTouches = %d, showPointerLocation = %d", __func__, showTouches, showPointerLocation);
    jint status = -1;

    if (dbg) ALOGD("%s calling JAVA callback function with args: showTouches = %d and showPointerLocation = %d", __func__, showTouches, showPointerLocation);
    status = g_env->CallIntMethod(g_bstCommandLoopObject, g_enableInputDebuggingMethod, showTouches, showPointerLocation);
    checkAndClearExceptionFromCallback(g_env, __func__);

    if (dbg) ALOGD("%s returning %d using hcall function", __func__, status);
    xerr_t rval = hcallOnEnableInputDebuggingCompletedRpc(status);
    if (rval != XERR_SUCCESS) {
        ALOGE("RPC failed for %s, error %d", __func__, rval);
    } else {
        ALOGI("%s returning , hcall rval = %d", __func__, rval);
    }
    return;
}

void _gcallSetPreferredOrientationClbk(i32 orientation) {
    if (dbg) ALOGD("%s called, orientation %d", __func__, orientation);

    if (dbg) ALOGD("%s calling JAVA callback function with args: orientation = %d", __func__, orientation);
    g_env->CallVoidMethod(g_bstCommandLoopObject, g_setDevicePreferredOrientationMethod, orientation);
    checkAndClearExceptionFromCallback(g_env, __func__);

    return;
}

void _gcallSetMaxFpsClbk(i32 maxFpsSupported) {
    if (dbg) ALOGD("%s called: maxFpsSupported %d", __func__, maxFpsSupported);

    if (dbg) ALOGD("%s calling JAVA callback function with args: maxFpsSupported = %d", __func__, maxFpsSupported);
    g_env->CallVoidMethod(g_bstCommandLoopObject, g_setMaxFpsMethod, maxFpsSupported);
    checkAndClearExceptionFromCallback(g_env, __func__);

    return;
}

void _gcallInstallApkClbk(string apkFileNameStr, string attemptIdStr, string sourceStr) {
    const char* apkFileName = apkFileNameStr.c_str();
    const char* attemptId = attemptIdStr.c_str();
    const char* source = sourceStr.c_str();
    if (dbg) ALOGD("%s called: apkFileName %s, attemptId %s, source %s", __func__, apkFileName, attemptId, source);

    jstring jAttemptId = NULL;
    jstring jSource = NULL;
    jstring jApkFileName = g_env->NewStringUTF(apkFileName);
    if (jApkFileName == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, apkFileName);
        goto out;
    }

    jAttemptId = g_env->NewStringUTF(attemptId);
    if (jAttemptId == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, attemptId);
        goto out;
    }

    jSource = g_env->NewStringUTF(source);
    if (jSource == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, source);
        goto out;
    }

    if (dbg) ALOGD("%s calling JAVA callback function with args: apkFileName = %s", __func__, apkFileName);
    g_env->CallVoidMethod(g_bstCommandLoopObject, g_installApkMethod, jApkFileName, jAttemptId, jSource);
    checkAndClearExceptionFromCallback(g_env, __func__);

out:
    if (jApkFileName)
        g_env->DeleteLocalRef(jApkFileName);
    if (jAttemptId)
        g_env->DeleteLocalRef(jAttemptId);
    if (jSource)
        g_env->DeleteLocalRef(jSource);

    return;
}

void _gcallInstallStudioApkZipClbk(string zipFolderNameStr, string attemptIdStr, string sourceStr, string pkgStr) {
    const char* zipFolderName = zipFolderNameStr.c_str();
    const char* attemptId = attemptIdStr.c_str();
    const char* source = sourceStr.c_str();
    const char* pkg = pkgStr.c_str();
    if (dbg) ALOGD("%s called: zipFolderName %s, attemptId %s, source %s, pkg %s", __func__, zipFolderName, attemptId, source, pkg);

    jstring jAttemptId = NULL;
    jstring jSource = NULL;
    jstring jPkg = NULL;

    jstring jZipFolderName = g_env->NewStringUTF(zipFolderName);
    if (jZipFolderName == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, zipFolderName);
        goto out;
    }

    jAttemptId = g_env->NewStringUTF(attemptId);
    if (jAttemptId == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, attemptId);
        goto out;
    }

    jSource = g_env->NewStringUTF(source);
    if (jSource == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, source);
        goto out;
    }

    jPkg = g_env->NewStringUTF(pkg);
    if (jPkg == NULL) {
        ALOGE("%s: error for NewStringUTF %s", __func__, pkg);
        goto out;
    }
    if (dbg) ALOGD("%s calling JAVA callback function with args: zipFolderName = %s", __func__, zipFolderName);
    g_env->CallIntMethod(g_bstCommandLoopClass, g_installStudioZipMethod, jZipFolderName, jAttemptId, jSource, jPkg);
    checkAndClearExceptionFromCallback(g_env, __func__);

out:
    if (jZipFolderName)
        g_env->DeleteLocalRef(jZipFolderName);
    if (jAttemptId)
        g_env->DeleteLocalRef(jAttemptId);
    if (jSource)
        g_env->DeleteLocalRef(jSource);
    if (jPkg)
        g_env->DeleteLocalRef(jPkg);

    return;
}

void _gcallUninstallAppClbk(string packageStr) {
    const char* package = packageStr.c_str();
    if (dbg) ALOGD("%s called: package %s", __func__, package);
    jint status = -1;

    jstring jPackage = g_env->NewStringUTF(package);
    if (jPackage == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, package);
        goto out;
    }

    if (dbg) ALOGD("%s calling JAVA callback function with args: package = %s", __func__, package);
    status = g_env->CallIntMethod(g_bstCommandLoopObject, g_uninstallAppMethod, jPackage);
    checkAndClearExceptionFromCallback(g_env, __func__);

out:
    if (dbg) ALOGD("%s returning %d using hcall function", __func__, status);
    xerr_t rval = hcallOnUninstallAppCompletedRpc(status);
    if (rval != XERR_SUCCESS) {
        ALOGE("RPC failed for %s, error %d", __func__, rval);
    } else {
        ALOGI("%s returning , hcall rval = %d", __func__, rval);
    }
    if (jPackage)
        g_env->DeleteLocalRef(jPackage);

    return;
}

void _gcallStopAppClbk(string packageStr) {
    const char* package = packageStr.c_str();
    if (dbg) ALOGD("%s called: package %s", __func__, package);
    jint status = -1;

    jstring jPackage = g_env->NewStringUTF(package);
    if (jPackage == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, package);
        goto out;
    }

    if (dbg) ALOGD("%s calling JAVA callback function with args: package = %s", __func__, package);
    status = g_env->CallIntMethod(g_bstCommandLoopObject, g_stopAppMethod, jPackage);
    checkAndClearExceptionFromCallback(g_env, __func__);

out:
    if (dbg) ALOGD("%s returning %d using hcall function", __func__, status);
    xerr_t rval = hcallOnStopAppCompletedRpc(status);
    if (rval != XERR_SUCCESS) {
        ALOGE("RPC failed for %s, error %d", __func__, rval);
    } else {
        ALOGI("%s returning , hcall rval = %d", __func__, rval);
    }
    if (jPackage)
        g_env->DeleteLocalRef(jPackage);

    return;
}

void _gcallTakeScreenshotClbk() {
    if (dbg) ALOGD("%s called", __func__);
    if (dbg) ALOGD("%s calling JAVA callback function", __func__);
    g_env->CallVoidMethod(g_bstCommandLoopObject, g_takeScreenshotMethod);
    checkAndClearExceptionFromCallback(g_env, __func__);
    return;
}

void _gcallLaunchActivityClbk(string packageStr, string activityStr, string extrasStr) {
    const char* package = packageStr.c_str();
    const char* activity = activityStr.c_str();
    const char* extras = extrasStr.c_str();
    if (dbg) ALOGD("%s called: package %s, activity %s, extras %s", __func__, package, activity, extras);
    jint status = -1;

    jstring jActivity = NULL;
    jstring jExtras = NULL;
    jstring jPackage = g_env->NewStringUTF(package);
    if (jPackage == NULL) {
        ALOGE("%s OOM error for NewStringUTF %s", __func__, package);
        goto out;
    }
    jActivity = g_env->NewStringUTF(activity);
    if (jActivity == NULL) {
        ALOGE("%s OOM error for NewStringUTF %s", __func__, activity);
        goto out;
    }
    jExtras = g_env->NewStringUTF(extras);
    if (jExtras == NULL) {
        ALOGE("%s OOM error for NewStringUTF %s", __func__, extras);
        goto out;
    }

    if (dbg) ALOGD("%s calling JAVA callback function with args: package= %s and activity = %s and extras %s", __func__, package, activity, extras);
    status = g_env->CallIntMethod(g_bstCommandLoopObject, g_launchActivityMethod, jPackage, jActivity, jExtras);
    checkAndClearExceptionFromCallback(g_env, __func__);

out:
    if (dbg) ALOGD("%s returning %d using hcall function", __func__, status);
    xerr_t rval = hcallOnLaunchActivityCompletedRpc(status);
    if (rval != XERR_SUCCESS) {
        ALOGE("RPC failed for %s, error %d", __func__, rval);
    } else {
        ALOGI("%s returning , hcall rval = %d", __func__, rval);
    }
    if (jPackage)
        g_env->DeleteLocalRef(jPackage);
    if (jActivity)
        g_env->DeleteLocalRef(jActivity);
    if (jExtras)
        g_env->DeleteLocalRef(jExtras);

    return;
}


void _gcallReLaunchActivityClbk(string packageStr, string activityStr, string extrasStr) {
    const char* package = packageStr.c_str();
    const char* activity = activityStr.c_str();
    const char* extras = extrasStr.c_str();
    if (dbg) ALOGD("%s called: package %s, activity %s, extras %s", __func__, package, activity, extras);
    jint status = -1;

    jstring jActivity = NULL;
    jstring jExtras = NULL;
    jstring jPackage = g_env->NewStringUTF(package);
    if (jPackage == NULL) {
        ALOGE("%s OOM error for NewStringUTF %s", __func__, package);
        goto out;
    }
    jActivity = g_env->NewStringUTF(activity);
    if (jActivity == NULL) {
        ALOGE("%s OOM error for NewStringUTF %s", __func__, activity);
        goto out;
    }
    jExtras = g_env->NewStringUTF(extras);
    if (jExtras == NULL) {
        ALOGE("%s OOM error for NewStringUTF %s", __func__, extras);
        goto out;
    }

    if (dbg) ALOGD("%s calling JAVA callback function with args: package= %s and activity = %s and extras %s", __func__, package, activity, extras);
    status = g_env->CallIntMethod(g_bstCommandLoopObject, g_reLaunchActivityMethod, jPackage, jActivity, jExtras);
    checkAndClearExceptionFromCallback(g_env, __func__);

out:
    if (dbg) ALOGD("%s returning %d using hcall function", __func__, status);
    xerr_t rval = hcallOnReLaunchActivityCompletedRpc(status);
    if (rval != XERR_SUCCESS) {
        ALOGE("RPC failed for %s, error %d", __func__, rval);
    } else {
        ALOGI("%s returning , hcall rval = %d", __func__, rval);
    }
    if (jPackage)
        g_env->DeleteLocalRef(jPackage);
    if (jActivity)
        g_env->DeleteLocalRef(jActivity);
    if (jExtras)
        g_env->DeleteLocalRef(jExtras);

    return;
}

void _gcallLaunchUrlClbk(string urlStr) {
    const char* url = urlStr.c_str();
    if (dbg) ALOGD("%s called: url %s", __func__, url);

    jstring jUrl = g_env->NewStringUTF(url);
    if (jUrl == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, url);
        goto out;
    }

    if (dbg) ALOGD("%s calling JAVA callback function with args: folder = %s", __func__, url);
    g_env->CallVoidMethod(g_bstCommandLoopObject, g_launchUrlMethod, jUrl);
    checkAndClearExceptionFromCallback(g_env, __func__);

out:
    if (jUrl)
        g_env->DeleteLocalRef(jUrl);

    return;
}

void _gcallImportFilesClbk(string folderStr) {
    const char* folder = folderStr.c_str();
    if (dbg) ALOGD("%s called: folder %s", __func__, folder);

    jstring jFolder = g_env->NewStringUTF(folder);
    if (jFolder == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, folder);
        goto out;
    }

    if (dbg) ALOGD("%s calling JAVA callback function with args: folder = %s", __func__, folder);
    g_env->CallVoidMethod(g_bstCommandLoopObject, g_importFilesMethod, jFolder);
    checkAndClearExceptionFromCallback(g_env, __func__);

out:
    if (jFolder)
        g_env->DeleteLocalRef(jFolder);

    return;
}

void _gcallExportFilesClbk(string folderStr) {
    const char* folder = folderStr.c_str();
    if (dbg) ALOGD("%s called: folder %s", __func__, folder);

    jstring jFolder = g_env->NewStringUTF(folder);
    if (jFolder == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, folder);
        goto out;
    }

    if (dbg) ALOGD("%s calling JAVA callback function with args: folder = %s", __func__, folder);
    g_env->CallVoidMethod(g_bstCommandLoopObject, g_exportFilesMethod, jFolder);
    checkAndClearExceptionFromCallback(g_env, __func__);

out:
    if (jFolder)
        g_env->DeleteLocalRef(jFolder);

    return;
}

void _gcallEnableAdbClbk(bool enable) {
    if (dbg) ALOGD("%s called: enable %d", __func__, enable);
    jint status = -1;

    if (dbg) ALOGD("%s calling JAVA callback function with args: enable = %d", __func__, enable);
    status = g_env->CallIntMethod(g_bstCommandLoopObject, g_enableAdbMethod, enable);
    checkAndClearExceptionFromCallback(g_env, __func__);

    if (dbg) ALOGD("%s returning %d using hcall function", __func__, status);
    xerr_t rval = hcallOnEnableAdbCompletedRpc(status);
    if (rval != XERR_SUCCESS) {
        ALOGE("RPC failed for %s, error %d", __func__, rval);
    } else {
        ALOGI("%s returning , hcall rval = %d", __func__, rval);
    }
    return;
}

void _gcallSetClipboardTextClbk(string textStr) {
    const char* text = textStr.c_str();
    if (dbg) ALOGD("%s called", __func__);

    jstring jText = g_env->NewStringUTF(text);
    if (jText == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, text);
        goto out;
    }

    if (dbg) ALOGD("%s calling JAVA callback function", __func__);
    g_env->CallVoidMethod(g_bstCommandLoopObject, g_setClipboardMethod, jText);
    checkAndClearExceptionFromCallback(g_env, __func__);

out:
    if (jText)
        g_env->DeleteLocalRef(jText);

    return;
}

void _gcallClearAppDataClbk(string packageListStr) {
    const char* packageList = packageListStr.c_str();
    if (dbg) ALOGD("%s called: packageList %s", __func__, packageList);
    jint status = -1;

    jstring jPackageList = g_env->NewStringUTF(packageList);
    if (jPackageList == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, packageList);
        goto out;
    }

    if (dbg) ALOGD("%s calling JAVA callback function with args: packageList = %s", __func__, packageList);
    status = g_env->CallIntMethod(g_bstCommandLoopObject, g_clearAppDataMethod, jPackageList);
    checkAndClearExceptionFromCallback(g_env, __func__);

out:
    if (dbg) ALOGD("%s returning %d using hcall function", __func__, status);
    xerr_t rval = hcallOnClearAppDataCompletedRpc(status);
    if (rval != XERR_SUCCESS) {
        ALOGE("RPC failed for %s, error %d", __func__, rval);
    } else {
        ALOGI("%s returning , hcall rval = %d", __func__, rval);
    }
    if (jPackageList)
        g_env->DeleteLocalRef(jPackageList);

    return;
}

void _gcallLaunchAppStoreClbk(string storeStr, string packageStr, string extraDataStr, string sourceStr) {
    const char* store = storeStr.c_str();
    const char* package = packageStr.c_str();
    const char* extraData = extraDataStr.c_str();
    const char* source = sourceStr.c_str();

    if (dbg) ALOGD("%s called: store %s, package %s, extraData %s, source %s", __func__, store, package, extraData, source);
    jint status = -1;

    jstring jExtraData = NULL;
    jstring jPackage = NULL;
    jstring jSource = NULL;
    jstring jStore = g_env->NewStringUTF(store);
    if (jStore == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, store);
        goto out;
    }
    jPackage = g_env->NewStringUTF(package);
    if (jPackage == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, package);
        goto out;
    }
    jExtraData = g_env->NewStringUTF(extraData);
    if (jExtraData == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, extraData);
        goto out;
    }
    jSource = g_env->NewStringUTF(source);
    if (jSource == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, source);
        goto out;
    }

    if (dbg) ALOGD("%s calling JAVA callback function with args: store = %s, package = %s, extraData = %s, source = %s", __func__, store, package, extraData, source);
    g_env->CallVoidMethod(g_bstCommandLoopClass, g_launchAppStoreMethod, jStore, jPackage, jExtraData, jSource);
    checkAndClearExceptionFromCallback(g_env, __func__);

out:
    if (dbg) ALOGD("%s returning %d using hcall function", __func__, status);
    if (jStore)
        g_env->DeleteLocalRef(jStore);
    if (jPackage)
        g_env->DeleteLocalRef(jPackage);
    if (jExtraData)
        g_env->DeleteLocalRef(jExtraData);
    if (jSource)
        g_env->DeleteLocalRef(jSource);

    return;
}

void _gcallSetVolumeClbk(bool mute, i32 volume) {
    if (dbg) ALOGD("%s called: mute %d, volume %d", __func__, mute, volume);
    if (dbg) ALOGD("%s calling JAVA callback function with args: mute = %d, volume = %d", __func__, mute, volume);
    g_env->CallVoidMethod(g_bstCommandLoopObject, g_setVolumeMethod, mute, volume);
    checkAndClearExceptionFromCallback(g_env, __func__);
    return;
}

void _gcallSynchronizeTimeClbk(u64 msecUTCFromEpoch, string tzbufStr) {
    const char* tzbuf = tzbufStr.c_str();
    if (dbg) ALOGD("%s called: msecUTCFromEpoch %llu, tzbuf = %s", __func__, msecUTCFromEpoch, tzbuf);

    jstring jTimeZone = g_env->NewStringUTF(tzbuf);
    if (jTimeZone == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, tzbuf);
        goto out;
    }

    if (dbg) ALOGD("%s calling JAVA callback function with args: msecUTCFromEpoch = %llu, tzbuf = %s", __func__, msecUTCFromEpoch, tzbuf);
    g_env->CallVoidMethod(g_bstCommandLoopObject, g_setLocalTimeMethod, msecUTCFromEpoch, jTimeZone);
    checkAndClearExceptionFromCallback(g_env, __func__);

out:
    if (jTimeZone)
        g_env->DeleteLocalRef(jTimeZone);

    return;
}

void _gcallLaunchAppStoreSearchClbk(string storeStr, string queryStr) {
    const char* store = storeStr.c_str();
    const char* query = queryStr.c_str();
    if (dbg) ALOGD("%s called: store %s, query %s", __func__, store, query);
    jint status = -1;

    jstring jQuery = NULL;
    jstring jStore = g_env->NewStringUTF(store);
    if (jStore == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, store);
        goto out;
    }
    jQuery = g_env->NewStringUTF(query);
    if (jQuery == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, query);
        goto out;
    }

    if (dbg) ALOGD("%s calling JAVA callback function with args: store = %s, query = %s", __func__, store, query);
    g_env->CallVoidMethod(g_bstCommandLoopObject, g_launchAppStoreSearchMethod, jStore, jQuery);
    checkAndClearExceptionFromCallback(g_env, __func__);

out:
    if (dbg) ALOGD("%s returning %d using hcall function", __func__, status);
    if (jStore)
        g_env->DeleteLocalRef(jStore);
    if (jQuery)
        g_env->DeleteLocalRef(jQuery);

    return;
}

void _gcallSetGamepadStateClbk(bool state) {
    if (dbg) ALOGD("%s called: state = %d", __func__, state);

    if (dbg) ALOGD("%s calling JAVA callback function with args: state = %d", __func__, state);
    g_env->CallVoidMethod(g_bstCommandLoopObject, g_setGamepadStateMethod, state);
    checkAndClearExceptionFromCallback(g_env, __func__);

    return;
}

void _gcallAffiliateTrackingForPackageClbk(string packageStr, string sourceStr, string attemptIdStr) {
    const char* package = packageStr.c_str();
    const char* source = sourceStr.c_str();
    const char* attemptId = attemptIdStr.c_str();
    if (dbg) ALOGD("%s called: package %s, source %s, attemptId %s", __func__, package, source, attemptId);
    jint status = -1;

    jstring jSource = NULL;
    jstring jAttemptId = NULL;
    jstring jPackage = g_env->NewStringUTF(package);
    if (jPackage == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, package);
        goto out;
    }
    jSource = g_env->NewStringUTF(source);
    if (jSource == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, source);
        goto out;
    }
    jAttemptId = g_env->NewStringUTF(attemptId);
    if (jAttemptId == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, attemptId);
        goto out;
    }

    if (dbg) ALOGD("%s calling JAVA callback function with args: package = %s, source = %s, attemptId = %s", __func__, package, source, attemptId);
    g_env->CallVoidMethod(g_bstCommandLoopClass, g_affiliateTrackingForPackageMethod, jPackage, jSource, jAttemptId);
    checkAndClearExceptionFromCallback(g_env, __func__);

out:
    if (dbg) ALOGD("%s returning %d using hcall function", __func__, status);
    if (jPackage)
        g_env->DeleteLocalRef(jPackage);
    if (jSource)
        g_env->DeleteLocalRef(jSource);
    if (jAttemptId)
        g_env->DeleteLocalRef(jAttemptId);

    return;
}

void _gcallGetNowggAccountsClbk(i32 flag) {
    if (dbg) ALOGD("%s called : flag = %d", __func__, flag);
    if (dbg) ALOGD("%s calling JAVA callback function", __func__);
    g_env->CallVoidMethod(g_bstCommandLoopClass, g_getNowggAccountsMethod);
    checkAndClearExceptionFromCallback(g_env, __func__);
    return;

    /*jstring jAccountsListJsonString = static_cast<jstring> (g_env->CallObjectMethod(g_bstCommandLoopClass, g_getNowggAccountsMethod));
    checkAndClearExceptionFromCallback(g_env, __func__);

    if (jAccountsListJsonString != NULL) {
        const char *accountsListJsonString = g_env->GetStringUTFChars(jAccountsListJsonString, nullptr);

        if (dbg) ALOGD("%s returning %s using hcall function", __func__, accountsListJsonString);
        xerr_t rval = hcallAvailableNowggAccountsRpc(flag, accountsListJsonString);
        if (rval != XERR_SUCCESS) {
            ALOGE("RPC failed for %s, error %d", __func__, rval);
        } else {
            ALOGI("%s returning , hcall rval = %d", __func__, rval);
        }

        g_env->ReleaseStringUTFChars(jAccountsListJsonString, accountsListJsonString);
        g_env->DeleteLocalRef(jAccountsListJsonString);
    }

    return;*/
}

void _gcallAddNowggAccountClbk(string accountInfoJsonStr) {
    const char* accountInfoJson = accountInfoJsonStr.c_str();
    if (dbg) ALOGD("%s called", __func__);

    jstring jAccountInfoJson = g_env->NewStringUTF(accountInfoJson);
    if (jAccountInfoJson == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, accountInfoJson);
        goto out;
    }

    if (dbg) ALOGD("%s calling JAVA callback function", __func__);
    g_env->CallVoidMethod(g_bstCommandLoopObject, g_addNowggAccountMethod, jAccountInfoJson);
    checkAndClearExceptionFromCallback(g_env, __func__);

out:
    if (jAccountInfoJson)
        g_env->DeleteLocalRef(jAccountInfoJson);

    return;
}

void _gcallRemoveNowggAccountClbk(string accountNameStr) {
    const char* accountName = accountNameStr.c_str();
    if (dbg) ALOGD("%s called", __func__);

    jstring jAccountName = g_env->NewStringUTF(accountName);
    if (jAccountName == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, accountName);
        goto out;
    }

    if (dbg) ALOGD("%s calling JAVA callback function", __func__);
    g_env->CallVoidMethod(g_bstCommandLoopObject, g_removeNowggAccountMethod, jAccountName);
    checkAndClearExceptionFromCallback(g_env, __func__);

out:
    if (jAccountName)
        g_env->DeleteLocalRef(jAccountName);

    return;
}

void _gcallEnableSignInPopupClbk(bool enable) {

    if (dbg) ALOGD("%s calling JAVA callback function with args: enable = %d", __func__, enable);
    g_env->CallVoidMethod(g_bstCommandLoopObject, g_enableSignInPopupMethod, enable);
    checkAndClearExceptionFromCallback(g_env, __func__);

    return;
}

void _gcallEnableClickSoundClbk(bool enable) {

    if (dbg) ALOGD("%s calling JAVA callback function with args: enable = %d", __func__, enable);
    g_env->CallVoidMethod(g_bstCommandLoopObject, g_enableClickSound, enable);
    checkAndClearExceptionFromCallback(g_env, __func__);

    return;
}

void _gcallshowNativeMousePointerClbk(bool show) {
    if (dbg) ALOGD("%s called: show = %d", __func__, show);

    if (dbg) ALOGD("%s calling JAVA callback function with args: show = %d", __func__, show);
    g_env->CallVoidMethod(g_bstCommandLoopObject, g_showNativeMousePointer, show);
    checkAndClearExceptionFromCallback(g_env, __func__);

    return;
}

void _gcallSetAirplaneModeClbk(bool enable) {
    if (dbg) ALOGD("%s called: enable = %d", __func__, enable);

    if (dbg) ALOGD("%s calling JAVA callback function with args: enable = %d", __func__, enable);
    g_env->CallVoidMethod(g_bstCommandLoopObject, g_setAirplaneModeMethod, enable);
    checkAndClearExceptionFromCallback(g_env, __func__);

    return;
}

void _gcallStartRecordingClbk(bool start) {
    if (dbg) ALOGD("%s called: start = %d", __func__, start);

    if (dbg) ALOGD("%s calling JAVA callback function with args: start = %d", __func__, start);
    g_env->CallVoidMethod(g_bstCommandLoopObject, g_startRecording, start);
    checkAndClearExceptionFromCallback(g_env, __func__);

    return;
}

void _gcallEnableAndroidAdsClbk(bool enable, string extraDataStr) {
    const char* extraData = extraDataStr.c_str();
    if (dbg) ALOGD("%s called: enable = %d", __func__, enable);

    jstring jExtraData = g_env->NewStringUTF(extraData);
    if (jExtraData == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, extraData);
        goto out;
    }

    if (dbg) ALOGD("%s calling JAVA callback function with args: enable = %d", __func__, enable);
    g_env->CallVoidMethod(g_bstCommandLoopObject, g_enableAndroidAds, enable, jExtraData);
    checkAndClearExceptionFromCallback(g_env, __func__);

out:
    if (jExtraData)
        g_env->DeleteLocalRef(jExtraData);

    return;
}

void _gcallCommonCommandClbk(i32 code, string nameStr, string dataStr) {
    const char* name = nameStr.c_str();
    const char* data = dataStr.c_str();
    jstring jName = NULL;
    jstring jData = NULL;

    if (dbg) ALOGD("%s called: code=%d, name=%s, data=%s", __func__, code, name, data);

    jName = g_env->NewStringUTF(name);
    if (jName == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, name);
        goto out;
    }
    jData = g_env->NewStringUTF(data);
    if (jData == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, data);
        goto out;
    }

    g_env->CallVoidMethod(g_bstCommandLoopClass, g_commonCommandMethod, code, jName, jData);
    checkAndClearExceptionFromCallback(g_env, __func__);

out:
    if (jName)
        g_env->DeleteLocalRef(jName);
    if (jData)
        g_env->DeleteLocalRef(jData);

    return;
}

void _gcallOnUnzipFileCompletedClbk(i32 code, string unzipFolderNameStr, string attemptIdStr, string sourceStr, string pkgNameStr) {
    const char* unzipFolderName = unzipFolderNameStr.c_str();
    const char* attemptId = attemptIdStr.c_str();
    const char* source = sourceStr.c_str();
    const char* pkgName = pkgNameStr.c_str();

    jstring jUnzipFolderName = NULL;
    jstring jAttemptId = NULL;
    jstring jSource = NULL;
    jstring jPkgName = NULL;

    if (dbg) ALOGD("%s called: code=%d unzipFolderName=%s, attemptId=%s, source=%s, pkgName=%s", __func__,code, unzipFolderName, attemptId, source, pkgName);

    jUnzipFolderName = g_env->NewStringUTF(unzipFolderName);
    if (jUnzipFolderName == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, unzipFolderName);
        goto out;
    }
    jAttemptId = g_env->NewStringUTF(attemptId);
    if (jAttemptId == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, attemptId);
        goto out;
    }
    jSource = g_env->NewStringUTF(source);
    if (jSource == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, source);
        goto out;
    }
    jPkgName = g_env->NewStringUTF(pkgName);
    if (jPkgName == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, pkgName);
        goto out;
    }
    g_env->CallVoidMethod(g_bstCommandLoopClass, g_onUnzipFileCompletedMethod, code, jUnzipFolderName, jAttemptId, jSource, jPkgName);
    checkAndClearExceptionFromCallback(g_env, __func__);

out:
    if (jUnzipFolderName)
        g_env->DeleteLocalRef(jUnzipFolderName);
    if (jAttemptId)
        g_env->DeleteLocalRef(jAttemptId);
    if (jSource)
        g_env->DeleteLocalRef(jSource);
    if (jPkgName)
        g_env->DeleteLocalRef(jPkgName);


    return;
}

void _gcallStartInstallAppGameCenterClbk(string pkgNameStr) {
    const char* pkgName = pkgNameStr.c_str();
    if (dbg) ALOGD("%s called", __func__);

    jstring jPkgName = g_env->NewStringUTF(pkgName);
    if (jPkgName == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, pkgName);
        goto out;
    }

    if (dbg) ALOGD("%s calling JAVA callback function", __func__);
    g_env->CallVoidMethod(g_bstCommandLoopClass, g_startInstallAppGameCenterMethod, jPkgName);
    checkAndClearExceptionFromCallback(g_env, __func__);

out:
    if (jPkgName)
        g_env->DeleteLocalRef(jPkgName);

    return;
}

void _gcallStartUiDumpClbk() {
    if (dbg) ALOGD("%s calling JAVA callback function", __func__);
    g_env->CallVoidMethod(g_bstCommandLoopClass, g_startUiDumpMethod);
    checkAndClearExceptionFromCallback(g_env, __func__);
    return;
}

void _gcallInputSwipeCommandClbk(i32 mStartX, i32 mStartY, i32 mEndX, i32 mEndY, i32 mDurationMsecs) {
    if (dbg) ALOGD("%s calling JAVA callback function", __func__);

    g_env->CallVoidMethod(g_bstCommandLoopClass, g_inputSwipeCommandMethod, mStartX, mStartY, mEndX, mEndY, mDurationMsecs);
    checkAndClearExceptionFromCallback(g_env, __func__);

    return;
}

void _gcallInputTapCommandClbk(i32 mX, i32 mY) {
    if (dbg) ALOGD("%s calling JAVA callback function", __func__);
    g_env->CallVoidMethod(g_bstCommandLoopClass, g_inputTapCommandMethod, mX, mY);
    checkAndClearExceptionFromCallback(g_env, __func__);

    return;
}

void _gcallInputPressKeyCommandClbk(i32 keyCode) {
    if (dbg) ALOGD("%s calling JAVA callback function", __func__);
    g_env->CallVoidMethod(g_bstCommandLoopClass, g_inputPressKeyCommandMethod, keyCode);
    checkAndClearExceptionFromCallback(g_env, __func__);

    return;
}

void _gcallInputSetTextCommandClbk(string textStr) {
    const char* text = textStr.c_str();
    if (dbg) ALOGD("%s called: text: %s", __func__, text);

    jstring jText = g_env->NewStringUTF(text);
    if (jText == NULL) {
        ALOGE("%s: OOM error for NewStringUTF %s", __func__, text);
        goto out;
    }

    if (dbg) ALOGD("%s calling JAVA callback function with args: text = %s", __func__, text);
    g_env->CallVoidMethod(g_bstCommandLoopObject, g_inputSetTextCommandMethod, jText);
    checkAndClearExceptionFromCallback(g_env, __func__);

out:
    if (jText)
        g_env->DeleteLocalRef(jText);

    return;
}

/* native mouse related code - begin */
int g_bstUinputFd = -1;

#define RET_IF_FAIL(ret, errMsg)        \
    if (ret) { ALOGE(errMsg); close(g_bstUinputFd); g_bstUinputFd = -1; return; }

static void emitBstUinput(int type, int code, int val)
{

    if (g_bstUinputFd < 0)
        return; // /dev/uinput closed - nothing to be done.

    struct input_event ie;

    ie.type = type;
    ie.code = code;
    ie.value = val;
    /* timestamp values below are ignored */
    ie.time.tv_sec = 0;
    ie.time.tv_usec = 0;

    write(g_bstUinputFd, &ie, sizeof(ie));
}

static void initBstUinput(bool isAbsolute)
{
    struct uinput_setup usetup;

    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);

    if (fd < 0) {
        ALOGE("Failed opening /dev/uinput, error %d\n", errno);
        return;
    }

    g_bstUinputFd = fd;

    int ret  = 0;

    /* enable mouse button left and relative events */
    ret = ioctl(fd, UI_SET_EVBIT, EV_KEY);
    RET_IF_FAIL(ret, "ioctl UI_SET_EVIT for EV_KEY failed!\n");

    ret = ioctl(fd, UI_SET_KEYBIT, BTN_LEFT);
    RET_IF_FAIL(ret, "ioctl UI_SET_KEYBIT for BTN_LEFT failed!\n");

    ret = ioctl(fd, UI_SET_KEYBIT, BTN_RIGHT);
    RET_IF_FAIL(ret, "ioctl UI_SET_KEYBIT for BTN_RIGHT failed!\n");

    ret = ioctl(fd, UI_SET_KEYBIT, BTN_MIDDLE);
    RET_IF_FAIL(ret, "ioctl UI_SET_KEYBIT for BTN_MIDDLE failed!\n");

    ret = ioctl(fd, UI_SET_KEYBIT, BTN_SIDE);
    RET_IF_FAIL(ret, "ioctl UI_SET_KEYBIT for BTN_SIDE failed!\n");

    ret = ioctl(fd, UI_SET_KEYBIT, BTN_EXTRA);
    RET_IF_FAIL(ret, "ioctl UI_SET_KEYBIT for BTN_EXTRA failed!\n");

    ret = ioctl(fd, UI_SET_KEYBIT, BTN_MOUSE);
    RET_IF_FAIL(ret, "ioctl UI_SET_KEYBIT for BTN_MOUSE failed!\n");

    ret =  ioctl(fd, UI_SET_EVBIT, EV_REL);
    RET_IF_FAIL(ret, "ioctl UI_SET_EVIT for EV_REL failed!\n");

    ret = ioctl(fd, UI_SET_RELBIT, REL_WHEEL);
    RET_IF_FAIL(ret, "ioctl UI_SET_RELBIT for REL_WHEEL failed!\n");

    if (isAbsolute) {
        ret = ioctl(fd, UI_SET_EVBIT, EV_ABS);
        RET_IF_FAIL(ret, "ioctl UI_SET_EVBIT for EV_ABS failed!\n");

    ret =    ioctl(fd, UI_SET_ABSBIT, ABS_X);
    RET_IF_FAIL(ret, "ioctl UI_SET_ABSBIT for ABS_X failed!\n");

    ret =    ioctl(fd, UI_SET_ABSBIT, ABS_Y);
    RET_IF_FAIL(ret, "ioctl UI_SET_ABSBIT for ABS_Y failed!\n");

    struct uinput_abs_setup xAbs;
    memset(&xAbs, 0, sizeof(xAbs));
    xAbs.code = ABS_X;
    xAbs.absinfo.minimum = 0;
    xAbs.absinfo.maximum = INP_MOUSE_X_MAX;

    ret = ioctl(fd, UI_ABS_SETUP, &xAbs);
    RET_IF_FAIL(ret, "ioctl UI_ABS_SETUP for ABS_X failed!\n");

    struct uinput_abs_setup yAbs;
    memset(&yAbs, 0, sizeof(yAbs));

    yAbs.code = ABS_Y;
    yAbs.absinfo.minimum = 0;
    yAbs.absinfo.maximum = INP_MOUSE_Y_MAX;

    ret = ioctl(fd, UI_ABS_SETUP, &yAbs);
    RET_IF_FAIL(ret, "ioctl UI_ABS_SETUP for ABS_Y failed!\n");
    } else {
        ret = ioctl(fd, UI_SET_RELBIT, REL_X);
        RET_IF_FAIL(ret, "ioctl UI_SET_RELBIT for REL_X failed!\n");

        ret = ioctl(fd, UI_SET_RELBIT, REL_Y);
        RET_IF_FAIL(ret, "ioctl UI_SET_RELBIT for REL_Y failed!\n");
    }

/*
    ret =    ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_POINTER);
    RET_IF_FAIL(ret, "ioctl UI_SET_PROPBIT for INPUT_PROP_POINTER failed!\n");
*/
    memset(&usetup, 0, sizeof(usetup));
    usetup.id.bustype = BUS_USB;
    usetup.id.vendor = 0x1234; /* sample vendor */
    usetup.id.product = 0x5678; /* sample product */
    strcpy(usetup.name, "Bluestacks Mouse Device");

    ret =   ioctl(fd, UI_DEV_SETUP, &usetup);
    RET_IF_FAIL(ret, "ioctl UI_DEV_SETUP failed!\n");

    ret =   ioctl(fd, UI_DEV_CREATE);
    RET_IF_FAIL(ret, "ioctl UI_DEV_CREATE failed!\n");

    ALOGI("Successfully initialized BstUinput\n");

    return;
}

static void finiBstUinput()
{
    if (g_bstUinputFd < 0) // Already closed
        return;

    ioctl(g_bstUinputFd, UI_DEV_DESTROY);
    close(g_bstUinputFd);
}

/* native mouse related code - end */

/*
 * Gcall handler Threads.
 */
void gcallSetDeviceProfileClbk(const char *deviceProfileCode, const char *deviceCarrierCode) {
    ALOGI("%s called: deviceProfileCode %s, deviceCarrierCode %s", __func__, deviceProfileCode, deviceCarrierCode);
    xthrPoolAddTask(&g_thr_pool.threadPool, [deviceProfileCodeStr = string(deviceProfileCode), deviceCarrierCodeStr = string(deviceCarrierCode)]
        {
            _gcallSetDeviceProfileClbk(deviceProfileCodeStr, deviceCarrierCodeStr);
        });
    return;
}

void gcallSetCustomDeviceProfileClbk(const char *deviceManufacturer, const char *deviceBrand, const char *deviceModel, const char *deviceCarrierCode) {
    ALOGI("%s called: deviceManufacturer %s, deviceBrand %s, deviceModel %s, deviceCarrierCode %s", __func__, deviceManufacturer, deviceBrand, deviceModel, deviceCarrierCode);
    xthrPoolAddTask(&g_thr_pool.threadPool, [deviceManufacturerStr = string(deviceManufacturer), deviceBrandStr = string(deviceBrand), deviceModelStr = string(deviceModel), deviceCarrierCodeStr = string(deviceCarrierCode)]
        {
            _gcallSetCustomDeviceProfileClbk(deviceManufacturerStr, deviceBrandStr, deviceModelStr, deviceCarrierCodeStr);
        });
    return;
}

void gcallSetLocaleClbk(const char* locale) {
    ALOGI("%s called: locale = %s", __func__, locale);
    xthrPoolAddTask(&g_thr_pool.threadPool, [localeStr = string(locale)]
        {
            _gcallSetLocaleClbk(localeStr);
        });
}

void gcallRootDeviceClbk(bool root) {
    ALOGI("%s called: root = %d", __func__, root);
    xthrPoolAddTask(&g_thr_pool.threadPool, [root]
        {
            _gcallRootDeviceClbk(root);
        });
}

void gcallSendAccelerometerDataClbk(i32 x, i32 y, i32 z) {

    ALOGD("%s called: x %d y %d, z %d", __func__, x, y, z);

    xthrPoolAddTask(&g_thr_pool.threadPool, [d = AccelerometerData{x, y, z}]
        {
            if (g_bstsensor_pipe != -1)
            {

                int n = write(g_bstsensor_pipe, (void *)&d, sizeof(d));
                if (n == -1)
                    ALOGE("failed writing to fifo with error %d", errno);
                else
                {
            //    ALOGI("successfully written %d bytes to the fifo\n", n);
                }
            }
         });

}

void gcallEnableNativeMouseClbk(bool enable, bool isAbsolute)
{
    if (enable) {
        isAbsoluteMouse = isAbsolute;
        initBstUinput(isAbsolute);
    }
    else {
        finiBstUinput();
    }
}

void gcallSendMouseDataClbk(i32 event, i32 mouseX, i32 mouseY, i32 delta)
{
    //ALOGD("%s called: x %d y %d, event %d", __func__, mouseX, mouseY, event);

    static bool leftButtonPressed = false, rightButtonPressed = false, middleButtonPressed = false;
    static bool x1ButtonPressed = false, x2ButtonPressed = false;

    static bool firstLDown = false;

    if (event == InpMouseLDown) {
        leftButtonPressed = true;
        if (!firstLDown) {
            firstLDown = true;
        }
    }

    else if (event == InpMouseLUp) leftButtonPressed = false;
    else if (event == InpMouseRDown) rightButtonPressed = true;
    else if (event == InpMouseRUp) rightButtonPressed = false;
    else if (event == InpMouseMDown) middleButtonPressed = true;
    else if (event == InpMouseMUp) middleButtonPressed = false;
    else if (event == InpMouseX1Down) x1ButtonPressed = true;
    else if (event == InpMouseX1Up) x1ButtonPressed = false;
    else if (event == InpMouseX2Down) x2ButtonPressed = true;
    else if (event == InpMouseX2Up) x2ButtonPressed = false;

    if (!firstLDown)
        return; //Start giving mouse events after firstLButtonDown event.


    if(isAbsoluteMouse) {
        emitBstUinput(EV_ABS, ABS_X, mouseX);
        emitBstUinput(EV_ABS, ABS_Y, mouseY);
    } else {
        emitBstUinput(EV_REL, REL_X, mouseX);
        emitBstUinput(EV_REL, REL_Y, mouseY);
    }
    emitBstUinput(EV_KEY, BTN_LEFT, leftButtonPressed ? 0x20: 0x0);
    emitBstUinput(EV_KEY, BTN_RIGHT, rightButtonPressed ? 0x40: 0x0);
    emitBstUinput(EV_KEY, BTN_MIDDLE, middleButtonPressed ? 0x80: 0x0);
    emitBstUinput(EV_KEY, BTN_SIDE, x1ButtonPressed ? 0x100: 0x0);
    emitBstUinput(EV_KEY, BTN_EXTRA, x2ButtonPressed ? 0x200: 0x0);

    emitBstUinput(EV_REL, REL_WHEEL, delta);

    emitBstUinput(EV_SYN, SYN_REPORT, 0);
}

void gcallEnableInputDebuggingClbk(bool showTouches, bool showPointerLocation) {
    ALOGI("%s called, showTouches = %d, showPointerLocation = %d", __func__, showTouches, showPointerLocation);
    xthrPoolAddTask(&g_thr_pool.threadPool, [showTouches, showPointerLocation]
        {
            _gcallEnableInputDebuggingClbk(showTouches, showPointerLocation);
        });
}

static void setAccelerometerDataByOrientation(i32 orientation) {
    i32 x = 0, y = 0, z = 0;
    if (0 == orientation) { // Landscape
        y = -1000000;
    } else { // Portrait
        x = 1000000;
    }
    AccelerometerData d = AccelerometerData{x, y, z};
    if (g_bstsensor_pipe != -1) {
        int n = write(g_bstsensor_pipe, (void *)&d, sizeof(d));
        if (n == -1)
            ALOGE("failed writing to fifo with error %d", errno);
    }
}

void gcallSetPreferredOrientationClbk(i32 orientation) {
    ALOGI("%s called, orientation %d", __func__, orientation);
    xthrPoolAddTask(&g_thr_pool.threadPool, [orientation]
        {
            _gcallSetPreferredOrientationClbk(orientation);

            // ROB-7213: Modify both the x-axis and y-axis data of the acceleration sensor when
            //  turning the screen in order to simulate relatively realistic acceleration sensor data.
            setAccelerometerDataByOrientation(orientation);
        });
}

void gcallSetMaxFpsClbk(i32 maxFpsSupported) {
    ALOGI("%s called: maxFpsSupported %d", __func__, maxFpsSupported);
    xthrPoolAddTask(&g_thr_pool.threadPool, [maxFpsSupported]
        {
            _gcallSetMaxFpsClbk(maxFpsSupported);
        });
}

void gcallInstallApkClbk(const char* apkFileName, const char* attemptId, const char* source) {
    ALOGI("%s called: apkFileName %s, attemptId %s, source %s", __func__, apkFileName, attemptId, source);
    xthrPoolAddTask(&g_thr_pool.threadPool, [apkFileNameStr = string(apkFileName), attemptIdStr = string(attemptId), sourceStr = string(source)]
        {
            _gcallInstallApkClbk(apkFileNameStr, attemptIdStr, sourceStr);
        });
}

void gcallInstallStudioApkZipClbk(const char* zipFolderName, const char* attemptId, const char* source, const char* pkg) {
    ALOGI("%s called: zipFolderName %s, attemptId %s, source %s, pkg %s", __func__, zipFolderName, attemptId, source, pkg);
    xthrPoolAddTask(&g_thr_pool.threadPool, [zipFolderNameStr = string(zipFolderName), attemptIdStr = string(attemptId), sourceStr = string(source), pkgStr = string(pkg)]
        {
            _gcallInstallStudioApkZipClbk(zipFolderNameStr, attemptIdStr, sourceStr, pkgStr);
        });
}

void gcallUninstallAppClbk(const char* package) {
    ALOGI("%s called: package %s", __func__, package);
    xthrPoolAddTask(&g_thr_pool.threadPool, [packageStr = string(package)]
        {
            _gcallUninstallAppClbk(packageStr);
        });
}

void gcallStopAppClbk(const char* package) {
    ALOGI("%s called: package %s", __func__, package);
    xthrPoolAddTask(&g_thr_pool.threadPool, [packageStr = string(package)]
        {
            _gcallStopAppClbk(packageStr);
        });
}

void gcallTakeScreenshotClbk() {
    ALOGI("%s called", __func__);
    xthrPoolAddTask(&g_thr_pool.threadPool, []
        {
            _gcallTakeScreenshotClbk();
        });
}

void gcallLaunchActivityClbk(const char* package, const char* activity, const char* extras) {
    ALOGI("%s called: package %s, activity %s, extras %s", __func__, package, activity, extras);
    xthrPoolAddTask(&g_thr_pool.threadPool, [packageStr = string(package), activityStr = string(activity), extrasStr = string(extras)]
        {
            _gcallLaunchActivityClbk(packageStr, activityStr, extrasStr);
        });
}

void gcallReLaunchActivityClbk(const char* package, const char* activity, const char* extras) {
    ALOGI("%s called: package %s, activity %s, extras %s", __func__, package, activity, extras);
    xthrPoolAddTask(&g_thr_pool.threadPool, [packageStr = string(package), activityStr = string(activity), extrasStr = string(extras)]
        {
            _gcallReLaunchActivityClbk(packageStr, activityStr, extrasStr);
        });
}

void gcallLaunchUrlClbk(const char* url) {
    ALOGI("%s called: url %s", __func__, url);
    xthrPoolAddTask(&g_thr_pool.threadPool, [urlStr = string(url)]
        {
            _gcallLaunchUrlClbk(urlStr);
        });
}

void gcallImportFilesClbk(const char* folder) {
    ALOGI("%s called: folder %s", __func__, folder);
    xthrPoolAddTask(&g_thr_pool.threadPool, [folderStr = string(folder)]
        {
            _gcallImportFilesClbk(folderStr);
        });
}

void gcallExportFilesClbk(const char* folder) {
    ALOGI("%s called: folder %s", __func__, folder);
    xthrPoolAddTask(&g_thr_pool.threadPool, [folderStr = string(folder)]
        {
            _gcallExportFilesClbk(folderStr);
        });
}

void gcallEnableAdbClbk(bool enable) {
    ALOGI("%s called: enable %d", __func__, enable);
    xthrPoolAddTask(&g_thr_pool.threadPool, [enable]
        {
            _gcallEnableAdbClbk(enable);
        });
}

void gcallSetClipboardTextClbk(const char* text) {
    ALOGI("%s called", __func__);
    xthrPoolAddTask(&g_thr_pool.threadPool, [textStr = string(text)]
        {
            _gcallSetClipboardTextClbk(textStr);
        });
}

void gcallClearAppDataClbk(const char* packageList) {
    ALOGI("%s called: packageList %s", __func__, packageList);
    xthrPoolAddTask(&g_thr_pool.threadPool, [packageListStr = string(packageList)]
        {
            _gcallClearAppDataClbk(packageListStr);
        });
}

void gcallLaunchAppStoreClbk(const char* store, const char* package, const char* extraData, const char* source) {
    ALOGI("%s called: store %s, package %s, extraData %s, source %s", __func__, store, package, extraData, source);
    xthrPoolAddTask(&g_thr_pool.threadPool, [storeStr = string(store), packageStr = string(package), extraDataStr = string(extraData), sourceStr = string(source)]
        {
            _gcallLaunchAppStoreClbk(storeStr, packageStr, extraDataStr, sourceStr);
        });
}

void gcallSynchronizeTimeClbk(u64 msecsUTCFromEpoch, const char* tzbuf) {
    ALOGI("%s called: msecsUTCFromEpoch %lld, tzbuf %s", __func__, msecsUTCFromEpoch, tzbuf);

    if (msecsUTCFromEpoch == 0) {
        ALOGD("Not setting time as its 0, in case when windows doesn't have NTP time");
        return;
    }

    xthrPoolAddTask(&g_thr_pool.threadPool, [msecsUTCFromEpoch, tzbufStr = string(tzbuf)]
        {
            _gcallSynchronizeTimeClbk(msecsUTCFromEpoch, tzbufStr);
        });
}

void gcallSetVolumeClbk(bool mute, i32 volume) {
    ALOGI("%s called: mute %d volume %d", __func__, mute, volume);
    xthrPoolAddTask(&g_thr_pool.threadPool, [mute, volume]
        {
            _gcallSetVolumeClbk(mute, volume);
        });
}

void gcallLaunchAppStoreSearchClbk(const char* store, const char* query) {
    ALOGI("%s called: store %s, query %s", __func__, store, query);
    xthrPoolAddTask(&g_thr_pool.threadPool, [storeStr = string(store), queryStr = string(query)]
        {
            _gcallLaunchAppStoreSearchClbk(storeStr, queryStr);
        });
}

void gcallSetGamepadStateClbk(bool state) {
    ALOGI("%s called: state = %d", __func__, state);
    xthrPoolAddTask(&g_thr_pool.threadPool, [state]
        {
            _gcallSetGamepadStateClbk(state);
        });
}

void gcallAffiliateTrackingForPackageClbk(const char* package, const char* source, const char* attemptId) {
    ALOGI("%s called: package = %s, source = %s, attemptId = %s", __func__, package, source, attemptId);
    xthrPoolAddTask(&g_thr_pool.threadPool, [packageStr = string(package), sourceStr = string(source), attemptIdStr = string(attemptId)]
        {
            _gcallAffiliateTrackingForPackageClbk(packageStr, sourceStr, attemptIdStr);
        });
}

void gcallGetNowggAccountsClbk(i32 flag) {
    ALOGI("%s called: flag = %d", __func__, flag);
    xthrPoolAddTask(&g_thr_pool.threadPool, [flag]
        {
            _gcallGetNowggAccountsClbk(flag);
        });
}

void gcallAddNowggAccountClbk(const char* accountInfoJson) {
    ALOGI("%s called", __func__);
    xthrPoolAddTask(&g_thr_pool.threadPool, [accountInfoJsonStr = string(accountInfoJson)]
        {
            _gcallAddNowggAccountClbk(accountInfoJsonStr);
        });
}

void gcallRemoveNowggAccountClbk(const char* accountName) {
    ALOGI("%s called: accountName %s", __func__, accountName);
    xthrPoolAddTask(&g_thr_pool.threadPool, [accountNameStr = string(accountName)]
        {
            _gcallRemoveNowggAccountClbk(accountNameStr);
        });
}

void gcallEnableSignInPopupClbk(bool enable) {
    ALOGI("%s called: enable %d", __func__, enable);
    xthrPoolAddTask(&g_thr_pool.threadPool, [enable]
        {
            _gcallEnableSignInPopupClbk(enable);
        });
}

void gcallEnableClickSoundClbk(bool enable) {
    ALOGE("%s called: enable = %d", __func__, enable);
    xthrPoolAddTask(&g_thr_pool.threadPool, [enable]
        {
            _gcallEnableClickSoundClbk(enable);
        });
}

void gcallshowNativeMousePointerClbk(bool show) {
    ALOGE("%s called: show = %d", __func__, show);
    xthrPoolAddTask(&g_thr_pool.threadPool, [show]
        {
            _gcallshowNativeMousePointerClbk(show);
        });
}

void gcallSetDifferentImagesPkgClbk(const char* file)
{
    ALOGI("%s called: file %s", __func__, file);
    xthrPoolAddTask(&g_thr_pool.threadPool, [fileStr = string(file)]
            {
                _gcallSetDifferentImagePkgsClbk(fileStr);
            });
}

void gcallSetCustomAppOrientationClbk(const char* gameSettingJson)
{
     ALOGI("%s called: gameSettingJsone %s", __func__, gameSettingJson);
    xthrPoolAddTask(&g_thr_pool.threadPool, [gameSettingJsonStr = string(gameSettingJson)]
        {
            _gcallSetCustomAppOrientationClbk(gameSettingJsonStr);
        });

}

void gcallSetAirplaneModeClbk(bool enable) {
    ALOGE("%s called: enable = %d", __func__, enable);
    xthrPoolAddTask(&g_thr_pool.threadPool, [enable]
            {
                _gcallSetAirplaneModeClbk(enable);
            });
}

void gcallStartRecordingClbk(bool start) {
    ALOGE("%s called: start = %d", __func__, start);
    xthrPoolAddTask(&g_thr_pool.threadPool, [start]
            {
                _gcallStartRecordingClbk(start);
            });
}

void gcallEnableAndroidAdsClbk(bool enable, const char* extraData) {
    ALOGE("%s called: enable = %d, exatraData = %s", __func__, enable, extraData);
    xthrPoolAddTask(&g_thr_pool.threadPool, [enable, extraData]
            {
            _gcallEnableAndroidAdsClbk(enable, extraData);
            });
}

void gcallAndroidInterstitialAdSettingClbk(const char* data)
{
    ALOGI("%s called: data %s", __func__, data);
    xthrPoolAddTask(&g_thr_pool.threadPool, [dataStr = string(data)]
            {
                _gcallAndroidInterstitialAdSettingClbk(dataStr);
            });
}

void gcallCommonCommandClbk(i32 code, const char *name, const char *data) {
    ALOGE("%s called: code=%d, name=%s, data=%s", __func__, code, name, data);
    xthrPoolAddTask(&g_thr_pool.threadPool, [code, name, data]
            {
                _gcallCommonCommandClbk(code, name, data);
            });
}

void gcallOnUnzipFileCompletedClbk(i32 code, const char *unzipFolderName, const char *attemptId, const char *source, const char *pkgName) {
    ALOGE("%s called: code=%d, unzipFolderName=%s, attemptId=%s, source=%s, pkgName=%s", __func__, code, unzipFolderName, attemptId, source, pkgName);
    xthrPoolAddTask(&g_thr_pool.threadPool, [code, unzipFolderName, attemptId, source, pkgName]
            {
                _gcallOnUnzipFileCompletedClbk(code, unzipFolderName, attemptId, source, pkgName);
            });
}

void gcallStartInstallAppGameCenterClbk(const char *pkgName) {
    ALOGE("%s called: pkgName=%s", __func__, pkgName);
    xthrPoolAddTask(&g_thr_pool.threadPool, [pkgName]
            {
                _gcallStartInstallAppGameCenterClbk(pkgName);
            });
}

void gcallStartUiDumpClbk() {
    ALOGI("%s called", __func__);
    xthrPoolAddTask(&g_thr_pool.threadPool, []
        {
            _gcallStartUiDumpClbk();
        });
}

void gcallInputSwipeCommandClbk(i32 mStartX, i32 mStartY, i32 mEndX, i32 mEndY, i32 mDurationMsecs) {
    ALOGI("%s called, mStartX=%d, mStartY=%d, mX=%d, mY=%d, mDurationMsecs=%d", __func__, mStartX, mStartY, mEndX, mEndY, mDurationMsecs);
    xthrPoolAddTask(&g_thr_pool.threadPool, [mStartX, mStartY, mEndX, mEndY, mDurationMsecs]
        {
            _gcallInputSwipeCommandClbk(mStartX, mStartY, mEndX, mEndY, mDurationMsecs);
        });
}

void gcallInputTapCommandClbk(i32 mX, i32 mY) {
    ALOGI("%s called, mX=%d, mY=%d", __func__, mX, mY);
    xthrPoolAddTask(&g_thr_pool.threadPool, [mX, mY]
        {
            _gcallInputTapCommandClbk(mX, mY);
        });
}

void gcallInputPressKeyCommandClbk(i32 keyCode) {
    ALOGI("%s called, keyCode=%d", __func__, keyCode);
    xthrPoolAddTask(&g_thr_pool.threadPool, [keyCode]
        {
            _gcallInputPressKeyCommandClbk(keyCode);
        });
}

void gcallInputSetTextCommandClbk(const char* text) {
    ALOGI("%s called: text: %s", __func__, text);
    xthrPoolAddTask(&g_thr_pool.threadPool, [textStr = string(text)]
        {
            _gcallInputSetTextCommandClbk(textStr);
        });
}

void gcallAgentImportFilesClbk(const char* requestId, const char* payload) {
    ALOGI("%s called", __func__);
    xthrPoolAddTask(&g_thr_pool.threadPool, [requestIdStr = string(requestId), payloadStr = string(payload)]
        {
            _gcallAgentImportFilesClbk(requestIdStr, payloadStr);
        });
}

void gcallAgentExportFilesClbk(const char* requestId, const char* payload) {
    ALOGI("%s called", __func__);
    xthrPoolAddTask(&g_thr_pool.threadPool, [requestIdStr = string(requestId), payloadStr = string(payload)]
        {
            _gcallAgentExportFilesClbk(requestIdStr, payloadStr);
        });
}

/*
 * HCALL handlers and utility functions
 */
static jint getLocalTime(JNIEnv* env, jobject clazz) {
    ALOGI("%s called", __func__);
    xerr_t rval = hcallGetTimeInfoRpc();
    if (rval != XERR_SUCCESS) {
        ALOGE("RPC failed for %s, error %d", __func__, rval);
    }
    if (dbg) ALOGD("returning from %s", __func__);
    return rval;
}

static jint native_init(JNIEnv *env, jobject clazz)
{
    int rval = 0;
    ALOGV("%s called", __func__);

    g_bstCommandLoopObject = env->NewGlobalRef(clazz);
    if (g_bstCommandLoopObject == NULL) {
        rval = -1;
        ALOGE("Error in creating GlobalRef for g_bstCommandLoopObject");
        return rval;
    }

    xerr_t err = xplLibInit();
    if (err != XERR_SUCCESS) {
        fprintf(stderr,"%s: xplLibInit failed. err = %d\n", __func__, err);
        return err;
    }

    rval = hcallLibInitGuest();
    if (rval != XERR_SUCCESS) {
        XLOGE("hcallLibInitGuest failed, error %d", rval);
        return rval;
    }

   rval = gcallLibInitGuest();
    if (rval != XERR_SUCCESS) {
        XLOGE("gcallLibInitGuest failed, error %d", rval);
        return rval;
    }

    // NOTE: If we ever decide to increase the number of threads in the threadPool
    // then we will need to move this to another function and ensure that we attach
    // all threads to the VM using AttachCurrentThread function.
    // It’s vital that we have a thread specific JNIEnv* pointer
    // and use it on the right thread as it maintains a per-thread local reference table.
    xthrPoolInit(&g_thr_pool.threadPool, 1);
    xthrPoolAddTask(&g_thr_pool.threadPool, []
        {
        g_env = JNU_GetEnv();
        if (g_env == NULL) {
            ALOGE("%s: failed to get JAVA env", __func__);
            throw std::runtime_error("Failed to get JAVA Env");
        }
        });


    ALOGI("%s: trying to open bstfifo in block mode", __func__);

    g_bstsensor_pipe = open("/data/bstfifo", O_WRONLY);
    if (g_bstsensor_pipe != -1)
        ALOGI("%s: successfully able to open bstfifo in block mode", __func__);
    else
        ALOGE("%s: failed opening bstfifo in non block mode with error %d", __func__, errno);


    ALOGV("%s returning , rval = %d", __func__, rval);
    dbg |= debug();
    return rval;
}

static jint native_fini(JNIEnv *env, jobject clazz)
{
    int rval = 0;
    ALOGV("%s called", __func__);
    JNU_detachJNI();
    xthrPoolFini(&g_thr_pool.threadPool);
    ALOGV("%s returning , rval = %d", __func__, rval);

    finiBstUinput();

    if (g_bstCommandLoopObject)
        env->DeleteGlobalRef(g_bstCommandLoopObject);

    return rval;
}

/*
 * JNI registration.
 */
static const JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "native_init", "()I", (void *)native_init },
    { "native_fini", "()I", (void *)native_fini },
    { "native_getLocalTime", "()I", (void *)getLocalTime },
};

// This function registers the native methods and we also store the reference for
// JAVA VM pointer and cache BstCommandLoop Class and some function pointers for quick
// reference later on.
int register_com_bluestacks_BstCommandProcessor_BstCommandLoop(JavaVM *jvm, JNIEnv* env) {
    // Cache the JavaVM pointer
    g_javaVM = jvm;

    ScopedLocalRef<jclass> localClass(env, env->FindClass("com/bluestacks/BstCommandProcessor/BstCommandLoop"));
    if (localClass.get() == NULL) {
        ALOGE("Error in getting class identifier");
        return JNI_ERR;
    }

    g_bstCommandLoopClass= reinterpret_cast<jclass>(env->NewGlobalRef(localClass.get()));
    if (g_bstCommandLoopClass == NULL) {
        ALOGE("Error in creating GlobalRef for g_bstCommandLoopClass");
        return JNI_ERR;
    }

    g_setDeviceProfileMethod = env->GetMethodID(g_bstCommandLoopClass, "setDeviceProfileClbk", "(Ljava/lang/String;Ljava/lang/String;)I");
    if (g_setDeviceProfileMethod == NULL) {
        ALOGE("Error in getting method identifier for setDeviceProfileClbk");
        goto err;
    }

    g_setCustomDeviceProfileMethod = env->GetMethodID(g_bstCommandLoopClass, "setCustomDeviceProfileClbk", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I");
    if (g_setCustomDeviceProfileMethod == NULL) {
        ALOGE("Error in getting method identifier for setCustomDeviceProfileClbk");
        goto err;
    }

    g_setLocaleMethod = env->GetMethodID(g_bstCommandLoopClass, "setLocaleClbk", "(Ljava/lang/String;)I");
    if (g_setLocaleMethod == NULL) {
        ALOGE("Error in getting method identifier for setLocaleClbk");
        goto err;
    }

    g_rootDeviceMethod = env->GetMethodID(g_bstCommandLoopClass, "rootDeviceClbk", "(I)I");
    if (g_rootDeviceMethod == NULL) {
        ALOGE("Error in getting method identifier for rootDeviceClbk");
        goto err;
    }

    g_enableInputDebuggingMethod = env->GetMethodID(g_bstCommandLoopClass, "enableInputDebuggingClbk", "(ZZ)I");
    if (g_enableInputDebuggingMethod == NULL) {
        ALOGE("Error in getting method identifier for enableInputDebuggingClbk");
        goto err;
    }

    g_setDevicePreferredOrientationMethod = env->GetMethodID(g_bstCommandLoopClass, "setPreferredDeviceOrientationClbk", "(I)V");
    if (g_setDevicePreferredOrientationMethod == NULL) {
        ALOGE("Error in getting method identifier for setPreferredDeviceOrientationClbk");
        goto err;
    }

    g_setMaxFpsMethod = env->GetMethodID(g_bstCommandLoopClass, "setMaxFpsClbk", "(I)V");
    if (g_setMaxFpsMethod == NULL) {
        ALOGE("Error in getting method identifier for setMaxFpsClbk");
        goto err;
    }

    g_installApkMethod = env->GetMethodID(g_bstCommandLoopClass, "installApkClbk", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    if (g_installApkMethod == NULL) {
        ALOGE("Error in getting method identifier for installApkClbk");
        goto err;
    }

    g_installStudioZipMethod = env->GetMethodID(g_bstCommandLoopClass, "installStudioApkZipClbk", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    if (g_installApkMethod == NULL) {
        ALOGE("Error in getting method identifier for installStudioApkZipClbk");
        goto err;
    }

    g_uninstallAppMethod = env->GetMethodID(g_bstCommandLoopClass, "uninstallAppClbk", "(Ljava/lang/String;)I");
    if (g_uninstallAppMethod == NULL) {
        ALOGE("Error in getting method identifier for uninstallAppClbk");
        goto err;
    }

    g_stopAppMethod = env->GetMethodID(g_bstCommandLoopClass, "stopAppClbk", "(Ljava/lang/String;)I");
    if (g_stopAppMethod == NULL) {
        ALOGE("Error in getting method identifier for stopAppClbk");
        goto err;
    }

    g_takeScreenshotMethod = env->GetMethodID(g_bstCommandLoopClass, "takeScreenshotClbk", "()V");
    if (g_takeScreenshotMethod == NULL) {
        ALOGE("Error in getting method identifier for takeScreenshotClbk");
        goto err;
    }

    g_launchActivityMethod = env->GetMethodID(g_bstCommandLoopClass, "launchActivityClbk", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I");
    if (g_launchActivityMethod == NULL) {
        ALOGE("Error in getting method identifier for launchActivityClbk");
        goto err;
    }

    g_reLaunchActivityMethod = env->GetMethodID(g_bstCommandLoopClass, "reLaunchActivityClbk", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I");
    if (g_reLaunchActivityMethod == NULL) {
        ALOGE("Error in getting method identifier for reLaunchActivityClbk");
        goto err;
    }

    g_launchUrlMethod  = env->GetMethodID(g_bstCommandLoopClass, "launchUrlClbk", "(Ljava/lang/String;)V");
    if (g_launchUrlMethod == NULL) {
        ALOGE("Error in getting method identifier for launchUrlClbk");
        goto err;
    }

    g_importFilesMethod = env->GetMethodID(g_bstCommandLoopClass, "importFilesClbk", "(Ljava/lang/String;)V");
    if (g_importFilesMethod == NULL) {
        ALOGE("Error in getting method identifier for importFilesClbk");
        goto err;
    }

    g_exportFilesMethod = env->GetMethodID(g_bstCommandLoopClass, "exportFilesClbk", "(Ljava/lang/String;)V");
    if (g_exportFilesMethod == NULL) {
        ALOGE("Error in getting method identifier for exportFilesClbk");
        goto err;
    }

    g_enableAdbMethod = env->GetMethodID(g_bstCommandLoopClass, "enableAdbClbk", "(Z)I");
    if (g_enableAdbMethod == NULL) {
        ALOGE("Error in getting method identifier for enableAdbClbk");
        goto err;
    }

    g_setClipboardMethod = env->GetMethodID(g_bstCommandLoopClass, "setClipboardClbk", "(Ljava/lang/String;)V");
    if (g_setClipboardMethod == NULL) {
        ALOGE("Error in getting method identifier for setClipboardClbk");
        goto err;
    }

    g_setLocalTimeMethod = env->GetMethodID(g_bstCommandLoopClass, "setLocalTimeClbk", "(JLjava/lang/String;)V");
    if (g_setLocalTimeMethod == NULL) {
        ALOGE("Error in getting method identifier for setLocalTimeClbk");
        goto err;
    }

    g_clearAppDataMethod = env->GetMethodID(g_bstCommandLoopClass, "clearAppDataClbk", "(Ljava/lang/String;)I");
    if (g_clearAppDataMethod == NULL) {
        ALOGE("Error in getting method identifier for clearAppDataClbk");
        goto err;
    }

    g_launchAppStoreMethod = env->GetMethodID(g_bstCommandLoopClass, "launchAppStoreClbk", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    if (g_launchAppStoreMethod == NULL) {
        ALOGE("Error in getting method identifier for launchAppStoreClbk");
        goto err;
    }

    g_setVolumeMethod = env->GetMethodID(g_bstCommandLoopClass, "setVolumeClbk", "(ZI)V");
    if (g_setVolumeMethod == NULL) {
        ALOGE("Error in getting method identifier for setVolumeClbk");
        goto err;
    }

    g_launchAppStoreSearchMethod = env->GetMethodID(g_bstCommandLoopClass, "launchAppStoreSearchClbk", "(Ljava/lang/String;Ljava/lang/String;)V");
    if (g_launchAppStoreSearchMethod == NULL) {
        ALOGE("Error in getting method identifier for launchAppStoreSearchClbk");
        goto err;
    }

    g_setGamepadStateMethod = env->GetMethodID(g_bstCommandLoopClass, "setGamepadStateClbk", "(Z)V");
    if (g_setGamepadStateMethod == NULL) {
        ALOGE("Error in getting method identifier for setGamepadStateClbk");
        goto err;
    }

    g_affiliateTrackingForPackageMethod = env->GetMethodID(g_bstCommandLoopClass, "affiliateTrackingForPackageClbk", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    if (g_affiliateTrackingForPackageMethod == NULL) {
        ALOGE("Error in getting method identifier for affiliateTrackingForPackageClbk");
        goto err;
    }

    g_getNowggAccountsMethod = env->GetMethodID(g_bstCommandLoopClass, "getNowggAccountsClbk", "()V");
    if (g_getNowggAccountsMethod == NULL) {
        ALOGE("Error in getting method identifier for getNowggAccountsClbk");
        goto err;
    }

    g_addNowggAccountMethod = env->GetMethodID(g_bstCommandLoopClass, "addNowggAccountClbk", "(Ljava/lang/String;)V");
    if (g_addNowggAccountMethod == NULL) {
        ALOGE("Error in getting method identifier for addNowggAccountClbk");
        goto err;
    }

    g_removeNowggAccountMethod = env->GetMethodID(g_bstCommandLoopClass, "removeNowggAccountClbk", "(Ljava/lang/String;)V");
    if (g_removeNowggAccountMethod == NULL) {
        ALOGE("Error in getting method identifier for removeNowggAccountClbk");
        goto err;
    }

    g_enableSignInPopupMethod = env->GetMethodID(g_bstCommandLoopClass, "enableSignInPopupClbk", "(Z)V");
    if (g_enableSignInPopupMethod == NULL) {
        ALOGE("Error in getting method identifier for enableSignInPopupClbk");
        goto err;
    }

    g_enableClickSound = env->GetMethodID(g_bstCommandLoopClass, "enableClickSoundClbk", "(Z)V");
    if (g_enableClickSound == NULL) {
        ALOGE("Error in getting method identifier for enableClickSoundClbk");
        goto err;
    }

    g_showNativeMousePointer = env->GetMethodID(g_bstCommandLoopClass, "showNativeMousePointerClbk", "(Z)V");
    if (g_showNativeMousePointer == NULL) {
        ALOGE("Error in getting method identifier for showNativeMousePointerClbk");
        goto err;
    }

    g_setDifferentImagePkgs = env->GetMethodID(g_bstCommandLoopClass, "setDifferentImagePkgsClbk", "(Ljava/lang/String;)V");
    if (g_setDifferentImagePkgs == NULL) {
        ALOGE("Error in getting method identifier for setDifferentImagePkgsClbk");
        goto err;
    }

	g_setCustomAppOrientation = env->GetMethodID(g_bstCommandLoopClass, "setCustomAppOrientationClbk", "(Ljava/lang/String;)V");
    if (g_setCustomAppOrientation == NULL) {
        ALOGE("Error in getting method identifier for setCustomAppOrientation");
        goto err;
    }

    g_setAirplaneModeMethod = env->GetMethodID(g_bstCommandLoopClass, "setAirplaneModeClbk", "(Z)V");
    if (g_setAirplaneModeMethod == NULL) {
        ALOGE("Error in getting method identifier for setAirplaneModeClbk");
        goto err;
    }

    g_startRecording = env->GetMethodID(g_bstCommandLoopClass, "startRecordingClbk", "(Z)V");
    if (g_startRecording == NULL) {
        ALOGE("Error in getting method identifier for startRecordingClbk");
        goto err;
    }

    g_enableAndroidAds = env->GetMethodID(g_bstCommandLoopClass, "enableAndroidAdsClbk", "(ZLjava/lang/String;)V");
    if (g_enableAndroidAds == NULL) {
        ALOGE("Error in getting method identifier for enableAndroidAdsClbk");
        goto err;
    }

    g_androidInterstitialAdSetting = env->GetMethodID(g_bstCommandLoopClass, "androidInterstitialAdSettingClbk", "(Ljava/lang/String;)V");
    if (g_androidInterstitialAdSetting == NULL) {
        ALOGE("Error in getting method identifier for androidInterstitialAdSettingClbk");
        goto err;
    }

    g_commonCommandMethod = env->GetMethodID(g_bstCommandLoopClass, "commonCommandClbk", "(ILjava/lang/String;Ljava/lang/String;)V");
    if (g_commonCommandMethod == NULL) {
        ALOGE("Error in getting method identifier for commonCommandClbk");
        goto err;
    }

    g_onUnzipFileCompletedMethod = env->GetMethodID(g_bstCommandLoopClass, "onUnzipFileCompletedClbk", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    if (g_onUnzipFileCompletedMethod == NULL) {
        ALOGE("Error in getting method identifier for onUnzipFileCompleted");
        goto err;
    }

    g_startInstallAppGameCenterMethod = env->GetMethodID(g_bstCommandLoopClass, "startInstallAppGameCenterClbk", "(Ljava/lang/String;)V");
    if (g_startInstallAppGameCenterMethod == NULL) {
        ALOGE("Error in getting method identifier for startInstallAppGameCenterClbk");
        goto err;
    }

    g_startUiDumpMethod = env->GetMethodID(g_bstCommandLoopClass, "startUiDumpClbk", "()V");
    if (g_startUiDumpMethod == NULL) {
        ALOGE("Error in getting method identifier for startUiDumpClbk");
        goto err;
    }

    g_inputSwipeCommandMethod = env->GetMethodID(g_bstCommandLoopClass, "inputSwipeCommandClbk", "(IIIII)V");
    if (g_inputSwipeCommandMethod == NULL) {
        ALOGE("Error in getting method identifier for inputSwipeCommandClbk");
        goto err;
    }

    g_inputTapCommandMethod = env->GetMethodID(g_bstCommandLoopClass, "inputTapCommandClbk", "(II)V");
    if (g_inputTapCommandMethod == NULL) {
        ALOGE("Error in getting method identifier for inputTapCommandClbk");
        goto err;
    }

    g_inputPressKeyCommandMethod = env->GetMethodID(g_bstCommandLoopClass, "inputPressKeyCommandClbk", "(I)V");
    if (g_inputPressKeyCommandMethod == NULL) {
        ALOGE("Error in getting method identifier for inputPressKeyCommandClbk");
        goto err;
    }

    g_inputSetTextCommandMethod = env->GetMethodID(g_bstCommandLoopClass, "inputSetTextCommandClbk", "(Ljava/lang/String;)V");
    if (g_inputSetTextCommandMethod == NULL) {
        ALOGE("Error in getting method identifier for inputSetTextCommandClbk");
        goto err;
    }

    g_agentImportFilesClbk = env->GetMethodID(g_bstCommandLoopClass, "agentImportFilesClbk", "(Ljava/lang/String;)Ljava/lang/String;");
    if (g_agentImportFilesClbk == NULL) {
        ALOGE("Unable to find method agentImportFilesClbk with signature");
        goto err;
    }

    g_agentExportFilesClbk = env->GetMethodID(g_bstCommandLoopClass, "agentExportFilesClbk", "(Ljava/lang/String;)Ljava/lang/String;");
    if (g_agentExportFilesClbk == NULL) {
        ALOGE("Unable to find method agentExportFilesClbk with signature");
        goto err;
    }

    return android::AndroidRuntime::registerNativeMethods(env,
                "com/bluestacks/BstCommandProcessor/BstCommandLoop", gMethods, NELEM(gMethods));

err:
    if (g_bstCommandLoopClass)
        env->DeleteGlobalRef(g_bstCommandLoopClass);

    return JNI_ERR;
}

//} /* namespace android */
