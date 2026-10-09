LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE:= libtermux
LOCAL_SRC_FILES:= termux.c
# 16 KB page alignment is mandatory on Android 13+ (CLAUDE.md).
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,--gc-sections
include $(BUILD_SHARED_LIBRARY)
