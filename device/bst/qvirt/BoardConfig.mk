#
# Copyright (C) 2026 The BlueStacks Project
#
# G1：qvirt 板配置。arch 按 TARGET_PRODUCT 分派（win bst_x86_64 / mac bst_arm64）。
#

ifneq ($(filter bst_arm64,$(TARGET_PRODUCT)),)
include device/generic/arm64/BoardConfig.mk
else
include device/generic/x86_64/BoardConfig.mk
endif

# qvirt bypasses device/generic/goldfish/product/generic.mk, so attach the
# legacy goldfish graphics HAL declarations after common BoardConfig sets the
# base device manifest.
DEVICE_MANIFEST_FILE += device/generic/common/manifest/android.hardware.graphics.allocator@2.0.xml
DEVICE_MANIFEST_FILE += device/generic/common/manifest/android.hardware.graphics.composer@2.1.xml
DEVICE_MANIFEST_FILE += device/generic/common/manifest/android.hardware.graphics.mapper@2.1.xml
