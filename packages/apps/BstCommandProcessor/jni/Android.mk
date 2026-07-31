#/***********************************************************************
# Copyright (C) 2020 BlueStack Systems, Inc.
# All Rights Reserved
#
# THIS IS UNPUBLISHED PROPRIETARY SOURCE CODE OF BLUESTACK SYSTEMS, INC.
# The copyright notice above does not evidence any actual or intended
# publication of such source code.
#************************************************************************/

LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

BUILD_TOP := $(shell pwd)

LOCAL_SRC_FILES:= \
    jni_init.cpp \
	com_bluestacks_BstCommandProcessor_BstCommandLoop.cpp

LOCAL_SHARED_LIBRARIES := \
    libandroid_runtime \
    libutils \
    liblog \
    libnativehelper \
    libcutils

LOCAL_STATIC_LIBRARIES := \
	gcall \
	hcall \
	vmsg \
	xpl

LOCAL_C_INCLUDES += \
    frameworks/base/core/jni \
    $(JNI_H_INCLUDE)

ifdef $(HD_SOURCE_TOP)
 LOCAL_C_INCLUDES += \
		$(HD_SOURCE_TOP)/Source/gcall \
		$(HD_SOURCE_TOP)/Source/gcall/include \
		$(HD_SOURCE_TOP)/Source/hcall/include \
		$(HD_SOURCE_TOP)/Source/inp/include \
		$(HD_SOURCE_TOP)/Source/vmsg/include \
		$(HD_SOURCE_TOP)/Source/xpl/include
else
 LOCAL_C_INCLUDES += \
		$(BUILD_TOP)/../hd/Source/gcall \
		$(BUILD_TOP)/../hd/Source/gcall/include \
		$(BUILD_TOP)/../hd/Source/hcall/include \
		$(BUILD_TOP)/../hd/Source/inp/include \
		$(BUILD_TOP)/../hd/Source/vmsg/include \
		$(BUILD_TOP)/../hd/Source/xpl/include
endif

#LOCAL_CFLAGS += -Wall -Werror -Wno-unused-parameter -Wunused -Wunreachable-code
LOCAL_CFLAGS += -Wall -Wno-unused-parameter -Wno-unused-variable -Wunused -Wunreachable-code -fexceptions

LOCAL_MODULE:= libgcall_jni
LOCAL_MODULE_TAGS := optional

include $(BUILD_SHARED_LIBRARY)
