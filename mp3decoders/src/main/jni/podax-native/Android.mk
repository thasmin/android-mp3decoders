LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_LDLIBS    := -llog -lOpenSLES
LOCAL_MODULE    := podax-native
LOCAL_ARM_MODE  := arm
LOCAL_SRC_FILES := podax-native.c

include $(BUILD_SHARED_LIBRARY)

