LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)
LOCAL_FORCE_STATIC_EXECUTABLE := true
LOCAL_SRC_FILES:= bstshutdown_core.c
LOCAL_MODULE := bstshutdown_core
LOCAL_MODULE_CLASS := EXECUTABLES
#LOCAL_MODULE_TAGS := eng
LOCAL_STATIC_LIBRARIES := liblog libcutils libc libbase
LOCAL_CFLAGS += -Wno-unused-parameter
include $(BUILD_EXECUTABLE)

