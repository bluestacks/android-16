#
# Copyright (C) 2026 The BlueStacks Project
#
# G1（Phase 1）：统一板 bst_x86_64 产品。
# 内容来源 = 已 boot 到 launcher 的 a16 generic overlay（device/generic/common）。
# 本文件只做「身份改名 + 结构统一」，不改内容，确保与 android_x86_64 镜像等价（安全网 step1/2）。
# mac bst_arm64 后续复用同板；a13 mac qvirt HAL/包清单不在此引入（见 phase1-port-plan G1）。
#

# build-time 埋点：确认统一板被选中（可 grep A16DBG:G1）
$(warning A16DBG:G1: building unified board bst_x86_64 (device=qvirt) from a16 generic overlay)

# 复用已验证的 a16 generic BST 内容（core_64_bit + x86.mk + device.mk + generic.mk + BST overlay）
$(call inherit-product, device/generic/common/x86_64.mk)

# 身份 override → 统一板命名（与 mac bst_arm64 对齐）
PRODUCT_NAME := bst_x86_64
PRODUCT_BRAND := bst
PRODUCT_DEVICE := qvirt
PRODUCT_MODEL := bst_x86_64 on QVIRT
PRODUCT_MANUFACTURER := bst

# G9 fix (2026-07-17): hwservicemanager must be BUILT+installed on /system/bin (Android.bp
# has system_ext_specific commented -> /system). build_make system_image_defaults dep does not
# trigger a build for the Make systemimage path, so it never compiled -> all HIDL vendor HALs
# (keymaster/configstore/health/drm/hwcomposer) SIGABRT on register. Formal product-config install.
PRODUCT_PACKAGES += hwservicemanager
# P2-TEMP-SHELL-TRANSITIONS (2026-07-21): ADPF / PerformanceHintManager needs AIDL IPower
# with PowerHintSession. Stock treble.mk only ships HIDL power@1.0 → PerfHintController.onInit
# can block wmshell.main (createHintSession waits on missing HAL). Example stub provides sessions.
# A16DBG:G1: performance_hint via android.hardware.power-service.example
PRODUCT_PACKAGES += android.hardware.power-service.example
# Phase2: ENFORCE=true makes assemble_vintf keep source target-level=8 (else forced legacy).
# 2026-07-20 readback: target-level=8 ALONE insufficient to clear getTransport EMPTY / DIAG.
# Next formal candidate: device-side android.hidl.manager in DEVICE manifest.
PRODUCT_ENFORCE_VINTF_MANIFEST := true
PRODUCT_PROPERTY_OVERRIDES += ro.hardware.gralloc=bst ro.hardware.egl=emulation  # Phase2 formal: bake into build.prop (replaces g1_copy_bst_apks append temp_debt)

# A16DBG:P2:DEF BST app removal (a13; device layer avoids build/make release-config)
# These apps removed from product per a13 BST customization
PRODUCT_PACKAGES -= BasicDreams
PRODUCT_PACKAGES -= BluetoothMidiService
PRODUCT_PACKAGES -= ManagedProvisioning
PRODUCT_PACKAGES -= MmsService
