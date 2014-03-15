LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_C_INCLUDES := $(LOCAL_PATH)
LOCAL_MODULE     := mpg123
LOCAL_ARM_MODE   := arm
LOCAL_LDLIBS     := -llog
LOCAL_CFLAGS     := -DACCURATE_ROUNDING \
					-DOPT_ARM \
					-DREAL_IS_FIXED \
					-DNO_REAL \
					-DNO_32BIT \
					-DHAVE_STRERROR \
					-DASMALIGN_BYTE \
					-Wno-int-to-pointer-cast \
					-Wno-pointer-to-int-cast
LOCAL_SRC_FILES := 	libmpg123/com_axelby_jlayerplayer_MPG123.c \
					libmpg123/compat.c \
					libmpg123/frame.c \
					libmpg123/id3.c \
					libmpg123/format.c \
					libmpg123/stringbuf.c \
					libmpg123/libmpg123.c \
					libmpg123/readers.c \
					libmpg123/icy.c \
					libmpg123/icy2utf8.c \
					libmpg123/index.c \
					libmpg123/layer1.c \
					libmpg123/layer2.c \
					libmpg123/layer3.c \
					libmpg123/parse.c \
					libmpg123/optimize.c \
					libmpg123/synth.c \
					libmpg123/synth_8bit.c \
					libmpg123/synth_arm.S \
					libmpg123/ntom.c \
					libmpg123/dct64.c \
					libmpg123/equalizer.c \
					libmpg123/dither.c \
					libmpg123/tabinit.c \
					libmpg123/synth_arm_accurate.S \
					libmpg123/feature.c

include $(BUILD_SHARED_LIBRARY)
