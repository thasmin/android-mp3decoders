#include <string.h>
#include <assert.h>
#include <stdbool.h>
#include <sys/atomics.h>
 
#include <jni.h>
#include <android/log.h>

#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>

static SLObjectItf engineObject = NULL;
static SLEngineItf engineEngine = NULL;

static SLObjectItf outputMixObject = NULL;
static SLObjectItf bqPlayerObject = NULL;
static SLPlayItf bqPlayerPlay = NULL;
static SLSeekItf bqPlayerSeek = NULL;
static SLVolumeItf bqPlayerVolume = NULL;
static SLPlaybackRateItf bqPlayerPlaybackRate = NULL;
static SLAndroidSimpleBufferQueueItf bqPlayerBufferQueue = NULL;

bool is_done_buffering = false;

void playEventCallback(SLPlayItf caller, void *pContext, SLuint32 event)
{
    assert(caller == bqPlayerPlay);
    assert(NULL == pContext);
	if (event == SL_PLAYEVENT_HEADATNEWPOS) {
		// 1s passed
	} else if (event == SL_PLAYEVENT_HEADATEND) {
		is_done_buffering = true;
		// playing done
	}
}

// this callback handler is called every time a buffer finishes playing
void bqPlayerCallback(SLAndroidSimpleBufferQueueItf bq, void *context)
{
	__android_log_print(ANDROID_LOG_INFO, "mp3decoders", "buffer finished playing");
	/*
    // for streaming playback, replace this test by logic to find and fill the next buffer
    if (--nextCount > 0 && NULL != nextBuffer && 0 != nextSize) {
        SLresult result;
        // enqueue another buffer
        result = (*bqPlayerBufferQueue)->Enqueue(bqPlayerBufferQueue, nextBuffer, nextSize);
        // the most likely other result is SL_RESULT_BUFFER_INSUFFICIENT,
        // which for this code example would indicate a programming error
        assert(SL_RESULT_SUCCESS == result);
        (void)result;
    }
	*/
}

JNIEXPORT jint JNICALL Java_com_axelby_mp3decoders_Native_init(JNIEnv *env, jclass c)
{
    SLresult result;

    // create engine
    result = slCreateEngine(&engineObject, 0, NULL, 0, NULL, NULL);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    // realize the engine
    result = (*engineObject)->Realize(engineObject, SL_BOOLEAN_FALSE);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    // get the engine interface, which is needed in order to create other objects
    result = (*engineObject)->GetInterface(engineObject, SL_IID_ENGINE, &engineEngine);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    // create output mix
    const SLInterfaceID ids0[0] = {};
    const SLboolean req0[0] = {};
    result = (*engineEngine)->CreateOutputMix(engineEngine, &outputMixObject, 0, ids0, req0);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    // realize the output mix
    result = (*outputMixObject)->Realize(outputMixObject, SL_BOOLEAN_FALSE);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    // configure audio source
	/*
    SLDataLocator_AndroidSimpleBufferQueue loc_bufq = {SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, 2};
    SLDataFormat_PCM format_pcm = {SL_DATAFORMAT_PCM, 1, SL_SAMPLINGRATE_8,
        SL_PCMSAMPLEFORMAT_FIXED_16, SL_PCMSAMPLEFORMAT_FIXED_16,
        SL_SPEAKER_FRONT_CENTER, SL_BYTEORDER_LITTLEENDIAN};
	*/
	SLDataLocator_URI loc_uri = {SL_DATALOCATOR_URI, "file:///data/data/com.axelby.mp3decoders/files/loop1.mp3"};
	SLDataFormat_MIME format_mime = {SL_DATAFORMAT_MIME, NULL, SL_CONTAINERTYPE_UNSPECIFIED};
    SLDataSource audioSrc = {&loc_uri, &format_mime};

    // configure audio sink
    SLDataLocator_OutputMix loc_outmix = {SL_DATALOCATOR_OUTPUTMIX, outputMixObject};
    SLDataSink audioSnk = {&loc_outmix, NULL};

    // create audio player
    const SLInterfaceID ids[4] = {SL_IID_PLAY, SL_IID_SEEK, SL_IID_VOLUME, SL_IID_PLAYBACKRATE};
    const SLboolean req[4] = {SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE};
    result = (*engineEngine)->CreateAudioPlayer(engineEngine, &bqPlayerObject, &audioSrc, &audioSnk, 4, ids, req);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    // realize the player
    result = (*bqPlayerObject)->Realize(bqPlayerObject, SL_BOOLEAN_FALSE);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    // get the play interface
    result = (*bqPlayerObject)->GetInterface(bqPlayerObject, SL_IID_PLAY, &bqPlayerPlay);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

	result = (*bqPlayerPlay)->SetCallbackEventsMask(bqPlayerPlay, SL_PLAYEVENT_HEADATNEWPOS | SL_PLAYEVENT_HEADATEND);
	result = (*bqPlayerPlay)->RegisterCallback(bqPlayerPlay, playEventCallback, NULL);
	result = (*bqPlayerPlay)->SetPositionUpdatePeriod(bqPlayerPlay, 1000);

    // get the seek interface
    result = (*bqPlayerObject)->GetInterface(bqPlayerObject, SL_IID_SEEK, &bqPlayerSeek);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    // get the seek interface
    result = (*bqPlayerObject)->GetInterface(bqPlayerObject, SL_IID_VOLUME, &bqPlayerVolume);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

	/*
	SLmillibel minVolume = SL_MILLIBEL_MIN;
	SLmillibel maxVolume;
	SLmillibel volume = -0;
	result = (*bqPlayerVolume)->GetMaxVolumeLevel(bqPlayerVolume, &maxVolume);
	__android_log_print(ANDROID_LOG_INFO, "mp3decoders", "min volume: %d, max volume: %d, volume set to %d", minVolume, maxVolume, volume);
	result = (*bqPlayerVolume)->SetVolumeLevel(bqPlayerVolume, volume);
	*/
	result = (*bqPlayerVolume)->EnableStereoPosition(bqPlayerVolume, SL_BOOLEAN_TRUE);

    // get the seek interface
    result = (*bqPlayerObject)->GetInterface(bqPlayerObject, SL_IID_PLAYBACKRATE, &bqPlayerPlaybackRate);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

	SLpermille minRate, maxRate, stepSize;
	SLuint32 capabilities;
	result = (*bqPlayerPlaybackRate)->GetRateRange(bqPlayerPlaybackRate, 0, &minRate, &maxRate, &stepSize, &capabilities);
	__android_log_print(ANDROID_LOG_INFO, "mp3decoders", "minRate: %d, maxRate: %d, stepSize: %d" , minRate, maxRate, stepSize);
	result = (*bqPlayerPlaybackRate)->SetRate(bqPlayerPlaybackRate, 1000 * 2);

	/*
    // get the buffer queue interface
    result = (*bqPlayerObject)->GetInterface(bqPlayerObject, SL_IID_ANDROIDSIMPLEBUFFERQUEUE, &bqPlayerBufferQueue);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    // register callback on the buffer queue
    result = (*bqPlayerBufferQueue)->RegisterCallback(bqPlayerBufferQueue, bqPlayerCallback, NULL);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;
	*/

	is_done_buffering = false;

    // set the player's state to playing
    result = (*bqPlayerPlay)->SetPlayState(bqPlayerPlay, SL_PLAYSTATE_PLAYING);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;
}

jint Java_com_axelby_mp3decoders_Native_getPosition(JNIEnv* env, jclass clazz)
{
	SLmillisecond ms;
	SLresult result = (*bqPlayerPlay)->GetPosition(bqPlayerPlay, &ms);
    assert(SL_RESULT_SUCCESS == result);
	return ms;
}

void Java_com_axelby_mp3decoders_Native_shutdown(JNIEnv* env, jclass clazz)
{
    // destroy buffer queue audio player object, and invalidate all associated interfaces
    if (bqPlayerObject != NULL) {
        (*bqPlayerObject)->Destroy(bqPlayerObject);
        bqPlayerObject = NULL;
        bqPlayerPlay = NULL;
        bqPlayerSeek = NULL;
        bqPlayerVolume = NULL;
        bqPlayerPlaybackRate = NULL;
    }

    // destroy output mix object, and invalidate all associated interfaces
    if (outputMixObject != NULL) {
        (*outputMixObject)->Destroy(outputMixObject);
        outputMixObject = NULL;
    }

    // destroy engine object, and invalidate all associated interfaces
    if (engineObject != NULL) {
        (*engineObject)->Destroy(engineObject);
        engineObject = NULL;
        engineEngine = NULL;
    }

}
