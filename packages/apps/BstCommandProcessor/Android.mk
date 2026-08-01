LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_STATIC_JAVA_AAR_LIBRARIES := googleads
LOCAL_STATIC_JAVA_LIBRARIES := bst_gson

LOCAL_AAPT_FLAGS := --auto-add-overlay \
	--extra-packages com.google.android.gms.ads.identifier

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_PACKAGE_NAME := com.bluestacks.BstCommandProcessor
LOCAL_CERTIFICATE := platform
LOCAL_PRIVILEGED_MODULE := true
LOCAL_PRIVATE_PLATFORM_APIS := true

LOCAL_PROGUARD_ENABLED := disabled

include $(BUILD_PACKAGE)

# Use the folloing include to make our test apk.
include $(call all-makefiles-under,$(LOCAL_PATH))
##################################################
include $(CLEAR_VARS)

LOCAL_PREBUILT_STATIC_JAVA_LIBRARIES := googleads:lib/play-services-basement-11.0.4.aar bst_gson:lib/gson-2.10.1.jar
include $(BUILD_MULTI_PREBUILT)
