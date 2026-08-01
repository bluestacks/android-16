LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_PRELINK_MODULE := false
LOCAL_MODULE_RELATIVE_PATH := hw
LOCAL_SHARED_LIBRARIES := liblog libcutils libhardware
LOCAL_SRC_FILES := bstgps.c
LOCAL_MODULE := gps.default
LOCAL_MODULE_TAGS := optional 
LOCAL_CFLAGS := -Wno-unused-parameter -Wno-unused-variable -Wno-unused-function
include $(BUILD_SHARED_LIBRARY)
