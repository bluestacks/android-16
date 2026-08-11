LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

#LOCAL_JAVA_LIBRARIES := bouncycastle conscrypt telephony-common ims-common android-support-v7-appcompat android-support-v4
#LOCAL_STATIC_JAVA_LIBRARIES := android-support-v4 android-support-v13 jsr305

LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res
# For oem msi5 there is a different app icon,
# so added different res folder 'res_msi5' to make its icon different.
ifeq ($(OEM), msi5)
    LOCAL_RESOURCE_DIR += $(LOCAL_PATH)/res_icon_msi5
else
    LOCAL_RESOURCE_DIR += $(LOCAL_PATH)/res_icon_default
endif

LOCAL_AAPT_FLAGS := --auto-add-overlay

LOCAL_PACKAGE_NAME := BstSettings
LOCAL_PACKAGE_NAME := com.bluestacks.settings
LOCAL_CERTIFICATE := platform
LOCAL_PRIVILEGED_MODULE := true
LOCAL_PRIVATE_PLATFORM_APIS := true

LOCAL_PROGUARD_ENABLED := disabled

#include frameworks/opt/setupwizard/navigationbar/common.mk

include $(BUILD_PACKAGE)
