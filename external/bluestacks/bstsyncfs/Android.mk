LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)
LOCAL_FORCE_STATIC_EXECUTABLE := true
LOCAL_SRC_FILES:= bstsyncfs.c
LOCAL_MODULE := bstsyncfs
LOCAL_MODULE_CLASS := EXECUTABLES
#LOCAL_MODULE_TAGS := eng
LOCAL_STATIC_LIBRARIES := liblog libcutils libutils libc 
include $(BUILD_EXECUTABLE)

