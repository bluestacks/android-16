#
# BlueStacks unified board — arm64 product (mac path).
# Same device tree (qvirt) as bst_x86_64; arch differences live in BoardConfig.mk.
# Layer2 verification is win-first; this target is for mac same-code builds only.
#
$(warning A16DBG:G1: building unified board bst_arm64 (device=qvirt) mac-same-code)

# 64-bit + generic BST overlay (device.mk has bst_etc/bst_bins/HALs)
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit.mk)
$(call inherit-product, device/generic/common/device.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/languages_full.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/generic.mk)
$(call inherit-product-if-exists, frameworks/base/data/sounds/AudioPackage6.mk)

PRODUCT_NAME := bst_arm64
PRODUCT_BRAND := bst
PRODUCT_DEVICE := qvirt
PRODUCT_MODEL := bst_arm64 on QVIRT
PRODUCT_MANUFACTURER := bst

PRODUCT_PACKAGES += hwservicemanager
# P2-TEMP-SHELL-TRANSITIONS: AIDL power example (PowerHintSession) — see bst_x86_64.mk
PRODUCT_PACKAGES += android.hardware.power-service.example
PRODUCT_ENFORCE_VINTF_MANIFEST := true
PRODUCT_PROPERTY_OVERRIDES += ro.hardware.gralloc=bst ro.hardware.egl=emulation
