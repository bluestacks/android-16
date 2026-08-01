LOCAL_PATH := $(call my-dir)

# BlueStacks HAL module implemenation, not prelinked and stored in
# hw/sensors.default.so
ifeq ($(BUILD_EXTERNAL_BLUESTACKS_SENSORS),true)
include $(CLEAR_VARS)
CUR_DIR := $(LOCAL_PATH)
LOCAL_PRELINK_MODULE := false
LOCAL_MODULE_RELATIVE_PATH:= hw
LOCAL_SHARED_LIBRARIES := liblog libcutils libutils
LOCAL_STATIC_LIBRARIES := xpl hcall 
LOCAL_MODULE := sensors.default
#LOCAL_MODULE_TAGS := eng
LOCAL_C_INCLUDES += $(CUR_DIR)/../../../../hd/Source/xpl/include
LOCAL_CFLAGS += -Wno-unused-parameter \
                -Wno-unused-variable \
                -Wno-unused-function

LOCAL_SRC_FILES := sensors.cpp
# A16 enforces vendor/platform link boundaries; xpl/hcall are platform static libs.
# LOCAL_VENDOR_MODULE := true

include $(BUILD_SHARED_LIBRARY)
endif #BUILD_EXTERNAL_BLUESTACKS_SENSORS
