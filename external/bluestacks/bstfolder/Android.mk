LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := bstfolder
LOCAL_SRC_FILES := Main.cpp
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_SHARED_LIBRARIES := libsysutils libcutils liblog
#LOCAL_MODULE_TAGS := eng
include $(BUILD_EXECUTABLE)
