#include <jni.h>
#include <stdint.h>
#include <stddef.h>
#include <string.h>

typedef struct {
    uint32_t magic, version, sequence, width, height, presentation, flags, capture_us;
    uint32_t reserved[8];
    uint32_t pixels[];
} FrameHeader;

_Static_assert(offsetof(FrameHeader, pixels) == 64, "frame data must start on a cache line");

JNIEXPORT jboolean JNICALL Java_com_winlator_ThorDualScreen_copyFrame(JNIEnv *env, jclass type,
        jobject mapped, jintArray output, jintArray metadata, jint old_sequence, jint width, jint height) {
    (void)type;
    const FrameHeader *data = (*env)->GetDirectBufferAddress(env, mapped);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, mapped);
    if (!data || width <= 0 || height <= 0 || width > 1048 || height > 790
            || capacity < (jlong)(64 + (size_t)width * height * 4)
            || (*env)->GetArrayLength(env, output) < width * height
            || (*env)->GetArrayLength(env, metadata) < 4) return JNI_FALSE;

    uint32_t sequence = __atomic_load_n(&data->sequence, __ATOMIC_ACQUIRE);
    if (!sequence || (sequence & 1) || sequence == (uint32_t)old_sequence
            || data->magic != UINT32_C(0x33465254) || data->version != 1
            || data->width != (uint32_t)width || data->height != (uint32_t)height) return JNI_FALSE;

    jint fields[] = {(jint)sequence, (jint)data->presentation, (jint)data->flags, (jint)data->capture_us};
    jint *pixels = (*env)->GetPrimitiveArrayCritical(env, output, NULL);
    if (!pixels) return JNI_FALSE;
    memcpy(pixels, data->pixels, (size_t)width * height * sizeof(uint32_t));
    __atomic_thread_fence(__ATOMIC_ACQUIRE);
    jboolean stable = __atomic_load_n(&data->sequence, __ATOMIC_RELAXED) == sequence;
    (*env)->ReleasePrimitiveArrayCritical(env, output, pixels, stable ? 0 : JNI_ABORT);
    if (stable) (*env)->SetIntArrayRegion(env, metadata, 0, 4, fields);
    return stable;
}