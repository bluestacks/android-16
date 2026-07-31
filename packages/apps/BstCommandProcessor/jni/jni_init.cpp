/***********************************************************************
# Copyright (C) 2020 BlueStack Systems, Inc.
# All Rights Reserved
#
# THIS IS UNPUBLISHED PROPRIETARY SOURCE CODE OF BLUESTACK SYSTEMS, INC.
# The copyright notice above does not evidence any actual or intended
# publication of such source code.
#************************************************************************/

#define LOG_TAG "GRV-BstGCallService-JNI"

#include <utils/Log.h>
#include "jni.h"

extern int register_com_bluestacks_BstCommandProcessor_BstCommandLoop(JavaVM *jvm, JNIEnv *env);

extern "C" jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env = NULL;
    jint result = -1;

    ALOGI("%s called", __func__);
    if (vm->GetEnv((void**) &env, JNI_VERSION_1_4) != JNI_OK) {
        ALOGE("ERROR: GetEnv failed");
        return result;
    }
    ALOG_ASSERT(env, "Could not retrieve the env");

    if (register_com_bluestacks_BstCommandProcessor_BstCommandLoop(vm, env) != JNI_OK) {
        ALOGE("ERROR: BstGCallService native registration failed");
        return result;
    }

    /* success -- return valid version number */
    return JNI_VERSION_1_4;
}
