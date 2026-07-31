LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)
LOCAL_FORCE_STATIC_EXECUTABLE:= true
LOCAL_SRC_FILES:= bstshutdown.c
LOCAL_MODULE := bstshutdown
#LOCAL_MODULE_TAGS := eng
LOCAL_STATIC_LIBRARIES := liblog libcutils libc libbase
include $(BUILD_EXECUTABLE)
