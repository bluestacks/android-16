#
# Copyright (C) 2026 The BlueStacks Project
#
# 统一板 device/bst/qvirt：win bst_x86_64 + mac bst_arm64（同板、arch 下沉 BoardConfig）。
#

PRODUCT_MAKEFILES := \
    $(LOCAL_DIR)/bst_x86_64.mk \
    $(LOCAL_DIR)/bst_arm64.mk

COMMON_LUNCH_CHOICES := \
    bst_x86_64-trunk_staging-eng \
    bst_x86_64-trunk_staging-user \
    bst_x86_64-trunk_staging-userdebug \
    bst_arm64-trunk_staging-eng \
    bst_arm64-trunk_staging-userdebug
