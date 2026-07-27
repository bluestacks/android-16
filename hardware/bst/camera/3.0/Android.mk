# Copyright (C) 2012 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

ifeq ($(USE_CAMERA_HAL3),true)

LOCAL_PATH := $(call my-dir)


include $(CLEAR_VARS)
LOCAL_MODULE := camera.bst
LOCAL_MODULE_RELATIVE_PATH := hw
LOCAL_VENDOR_MODULE := true

LOCAL_C_INCLUDES += \
    system/core/include \
    system/media/camera/include \
    external/jpeg \
    system/core/libion/include \
    hardware/libhardware/include \
    frameworks/av/camera/include \
    frameworks/native/include \
    frameworks/native/libs/arect/include 


LOCAL_SRC_FILES := \
    CameraHAL.cpp \
    Camera.cpp \
    Metadata.cpp \
    Stream.cpp \
    VendorTags.cpp \
    CameraUtils.cpp \
    MessageQueue.cpp \
    VideoStream.cpp \
    JpegBuilder.cpp \
    BstCamera.cpp \
    NV12_resize.c \
    YuvToJpegEncoder.cpp \
    MMAPStream.cpp \
    TinyExif.cpp \
    ImageProcess.cpp \
    CameraMetadata.cpp


LOCAL_SHARED_LIBRARIES := \
    libcamera_metadata \
    libcutils \
    liblog \
    libsync \
    libutils \
    libc \
    libui \
    libgui \
    libjpeg \
    libion \
    libbinder \
    libhardware_legacy

#LOCAL_WHOLE_STATIC_LIBRARIES := libionallocator


LOCAL_CFLAGS += \
        -DANDROID_SDK_VERSION=$(PLATFORM_SDK_VERSION)

LOCAL_CFLAGS += -Wall -Wextra -fvisibility=hidden 

LOCAL_MODULE_TAGS := optional

include $(BUILD_SHARED_LIBRARY)

endif
