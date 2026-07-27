#include <stdio.h>
#include <hardware/camera3.h>
#include <hardware/gralloc.h>
#include <system/graphics.h>
#include <graphics_ext.h>
#include <utils/Mutex.h>
#include <utils/StrongPointer.h>
#include <binder/MemoryBase.h>
#include <binder/MemoryHeapBase.h>
#include <sync/sync.h>

//#define LOG_NDEBUG 0

#include <cutils/log.h>
#include "Camera.h"
#include "Stream.h"
#include "CameraUtils.h"
#include "ImageProcess.h"

#include <ui/Rect.h>
#include <ui/GraphicBufferMapper.h>

Stream::Stream(int id, camera3_stream_t *s, Camera* camera)
  : mReuse(false),
    mPreview(false),
    mJpeg(false),
    mCallback(false),
    mRecord(false),
    mId(id),
    mStream(s),
    mType(s->stream_type),
    mWidth(s->width),
    mHeight(s->height),
    mFormat(s->format),
    mUsage(0),
    mFps(30),
    mNumBuffers(0),
    mRegistered(false),
    mCustomDriver(false),
    mCamera(camera)
{
    if (s->format == HAL_PIXEL_FORMAT_BLOB) {
        ALOGI("%s create capture stream", __func__);
        mJpeg = true;
        mFormat = s->format;
        mUsage = GRALLOC_USAGE_SW_WRITE_OFTEN;//CAMERA_GRALLOC_USAGE_JPEG;
        mNumBuffers = NUM_CAPTURE_BUFFER;
    }
    else if (s->format == HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED) {
        ALOGI("%s create preview stream", __func__);
        mFormat = HAL_PIXEL_FORMAT_YCbCr_420_888;//mCamera->getPreviewPixelFormat();
        mUsage = GRALLOC_USAGE_SW_WRITE_OFTEN;//CAMERA_GRALLOC_USAGE;
        mNumBuffers = NUM_PREVIEW_BUFFER;
        mPreview = true;
        if (s->usage & GRALLOC_USAGE_HW_VIDEO_ENCODER) {
            ALOGI("%s create video recording stream", __func__);
            mFormat = HAL_PIXEL_FORMAT_YCbCr_420_888;
            mPreview = false;
            mRecord = true;
        }
        s->format = mFormat;
    }
    else { 
        ALOGI("%s create callback stream format 0x%x", __func__, s->format);
        mFormat = HAL_PIXEL_FORMAT_YCBCR_420_888;//HAL_PIXEL_FORMAT_YCbCr_420_888;//mCamera->getPreviewPixelFormat();
        mUsage = GRALLOC_USAGE_SW_WRITE_OFTEN;//CAMERA_GRALLOC_USAGE;
        mNumBuffers = NUM_PREVIEW_BUFFER;
        mPreview = true;
        if (s->usage & GRALLOC_USAGE_SW_READ_OFTEN) {
            ALOGI("%s create video recording stream", __func__);
            mUsage = GRALLOC_USAGE_HW_CAMERA_WRITE | GRALLOC_USAGE_SW_WRITE_OFTEN;//CAMERA_GRALLOC_USAGE;
            mPreview = false;
            mRecord = true;
        }
        s->format = mFormat;
    }

    s->usage |= mUsage;
    s->max_buffers = mNumBuffers;
    //mNumBuffers += 1;

    ALOGI("stream: w:%d, h:%d, format:0x%x, usage:0x%x, buffers:%d Id:%d",
          s->width, s->height, s->format, s->usage, mNumBuffers, mId);

    for (uint32_t i=0; i<MAX_STREAM_BUFFERS; i++) {
        mBuffers[i] = NULL;
    }

    mJpegBuilder = new JpegBuilder();
}

Stream::Stream(Camera* camera)
  : mReuse(false),
    mPreview(false),
    mJpeg(false),
    mId(-1),
    mStream(NULL),
    mType(-1),
    mWidth(0),
    mHeight(0),
    mFormat(0),
    mUsage(0),
    mFps(30),
    mNumBuffers(0),
    mRegistered(false),
    mCustomDriver(false),
    mCamera(camera)
{
    for (uint32_t i=0; i<MAX_STREAM_BUFFERS; i++) {
        mBuffers[i] = NULL;
    }
}

Stream::~Stream()
{
}

int32_t Stream::getJpegBufferSize(StreamBuffer &src,
                                  sp<Metadata> meta __unused)
{
    const ssize_t kMinJpegBufferSize = 256 * 1024 + sizeof(camera3_jpeg_blob);
    sp<Stream> &srcStream = src.mStream;
    int maxJpegResWidth, maxJpegResHeight;
    uint32_t v4l2Width = 0, v4l2Height = 0;
    ssize_t maxJpegBufferSize = 0;
    int ret;

    ret = mCamera->getV4l2Res(srcStream->mWidth, srcStream->mHeight, &v4l2Width, &v4l2Height);
    if (ret) {
        ALOGE("%s getV4l2Res failed, ret %d", __func__, ret);
        return BAD_VALUE;
    }

    maxJpegResWidth = mCamera->mMaxWidth;
    maxJpegResHeight = mCamera->mMaxHeight;

    // Get max jpeg buffer size
    maxJpegBufferSize = mCamera->mMaxJpegSize;
    assert(kMinJpegBufferSize < maxJpegBufferSize);

    // Calculate final jpeg buffer size for the given resolution.
    float scaleFactor = ((float) (v4l2Width * v4l2Height)) /
            (maxJpegResWidth * maxJpegResHeight);

    ssize_t jpegBufferSize = scaleFactor * (maxJpegBufferSize - kMinJpegBufferSize) +
            kMinJpegBufferSize;

    if (jpegBufferSize > maxJpegBufferSize) {
        jpegBufferSize = maxJpegBufferSize;
    }

    return jpegBufferSize;
}

int32_t Stream::processJpegBuffer(StreamBuffer& src,
                                  sp<Metadata> meta)
{
    int32_t ret = 0;
    int32_t encodeQuality = 100, thumbQuality = 100;
    int32_t thumbWidth, thumbHeight;
    JpegParams *mainJpeg = NULL, *thumbJpeg = NULL;
    void *rawBuf = NULL, *thumbBuf = NULL;
    uint32_t v4l2Width = 0, v4l2Height = 0;
    uint8_t *pDst = NULL;
    struct camera3_jpeg_blob *jpegBlob = NULL;
    uint32_t bufSize = 0;
    int32_t jpegBufferSize;

    StreamBuffer* dstBuf = mCurrent;
    sp<Stream>& srcStream = src.mStream;


    GraphicBufferMapper& grbuffer_mapper(GraphicBufferMapper::get());
    const Rect bounds(srcStream->mWidth, srcStream->mHeight);
    int res = grbuffer_mapper.lock((*(*dstBuf).mBufHandle), GRALLOC_USAGE_SW_WRITE_OFTEN, bounds, &((*dstBuf).mVirtAddr));
    //dstBuf->mVirtAddr = malloc(src.mSize);
    if (res != NO_ERROR ) {
        ALOGE("%s: grbuffer_mapper.lock failure: %d -> %s",
             __FUNCTION__, res, strerror(res));
        return -1;
    }


    dstBuf->mSize = src.mSize;
    //memcpy(dstBuf->mVirtAddr, src.mVirtAddr, src.mSize);

    ret = mCamera->getV4l2Res(srcStream->mWidth, srcStream->mHeight, &v4l2Width, &v4l2Height);
    if (ret) {
        ALOGE("%s getV4l2Res failed, ret %d", __func__, ret);
        return BAD_VALUE;
    }

    ALOGI("%s srcStream->mWidth:%d, srcStream->mHeight:%d, v4l2Width:%d, v4l2Height:%d", __func__,
        srcStream->mWidth,
        srcStream->mHeight,
        v4l2Width,
        v4l2Height);
    // just set to actual v4l2 res
    srcStream->mWidth = v4l2Width;
    srcStream->mHeight = v4l2Height;

    if (dstBuf == NULL || srcStream == NULL) {
        ALOGE("%s invalid param", __FUNCTION__);
        return BAD_VALUE;
    }

    sp<Stream>& capture = dstBuf->mStream;

    ret = meta->getJpegQuality(encodeQuality);
    if (ret != NO_ERROR) {
        ALOGE("%s getJpegQuality failed", __FUNCTION__);
        return BAD_VALUE;
    }

    if ((encodeQuality < 0) || (encodeQuality > 100)) {
        encodeQuality = 100;
    }

    ret = meta->getJpegThumbQuality(thumbQuality);
    if (ret != NO_ERROR) {
        ALOGE("%s getJpegThumbQuality failed", __FUNCTION__);
        return BAD_VALUE;
    }

    if ((thumbQuality < 0) || (thumbQuality > 100)) {
        thumbQuality = 100;
    }

    int captureSize = 0;
    int alignedw, alignedh, c_stride;
    switch (srcStream->format()) {
        case HAL_PIXEL_FORMAT_YCbCr_420_P:
            alignedw = ALIGN_PIXEL_32(capture->mWidth);
            alignedh = ALIGN_PIXEL_4(capture->mHeight);
            c_stride = (alignedw/2+15)/16*16;
            captureSize = alignedw * alignedh + c_stride * alignedh;
            break;
        case HAL_PIXEL_FORMAT_YCbCr_420_SP:
            alignedw = ALIGN_PIXEL_16(capture->mWidth);
            alignedh = ALIGN_PIXEL_16(capture->mHeight);
            captureSize = alignedw * alignedh * 3 / 2;
            break;

        case HAL_PIXEL_FORMAT_YCbCr_422_I:
            alignedw = ALIGN_PIXEL_16(capture->mWidth);
            alignedh = ALIGN_PIXEL_16(capture->mHeight);
            captureSize = alignedw * alignedh * 2;
            break;
        case HAL_PIXEL_FORMAT_YCbCr_422_SP:
            alignedw = ALIGN_PIXEL_16(capture->mWidth);
            alignedh = ALIGN_PIXEL_16(capture->mHeight);
            captureSize = alignedw * alignedh * 2;
            break;
        case HAL_PIXEL_FORMAT_YCbCr_444_888:
            alignedw = ALIGN_PIXEL_16(capture->mWidth);
            alignedh = ALIGN_PIXEL_16(capture->mHeight);
            captureSize = alignedw * alignedh * 3;
            break;

        default:
            ALOGE("Error: %s format not supported", __FUNCTION__);
    }

    sp<MemoryHeapBase> rawFrame(
        new MemoryHeapBase(captureSize, 0, "rawFrame"));
    rawBuf = rawFrame->getBase();
    if (rawBuf == MAP_FAILED) {
        ALOGE("%s new MemoryHeapBase failed", __FUNCTION__);
        return BAD_VALUE;
    }

    sp<MemoryHeapBase> thumbFrame(
        new MemoryHeapBase(captureSize, 0, "thumbFrame"));
    thumbBuf = thumbFrame->getBase();
    if (thumbBuf == MAP_FAILED) {
        ALOGE("%s new MemoryHeapBase failed", __FUNCTION__);
        return BAD_VALUE;
    }

    mainJpeg = new JpegParams((uint8_t *)src.mVirtAddr,
                              (uint8_t *)(uintptr_t)src.mPhyAddr,
                              src.mSize,
                              (uint8_t *)rawBuf,
                              captureSize,
                              encodeQuality,
                              srcStream->mWidth,
                              srcStream->mHeight,
                              capture->mWidth,
                              capture->mHeight,
                              srcStream->format());

    ret = meta->getJpegThumbSize(thumbWidth, thumbHeight);
    if (ret != NO_ERROR) {
        ALOGE("%s getJpegThumbSize failed", __FUNCTION__);
        goto err_out;
    }

    if ((thumbWidth > 0) && (thumbHeight > 0)) {
        int thumbSize = captureSize;
        thumbJpeg = new JpegParams((uint8_t *)src.mVirtAddr,
                           (uint8_t *)(uintptr_t)src.mPhyAddr,
                           src.mSize,
                           (uint8_t *)thumbBuf,
                           thumbSize,
                           thumbQuality,
                           srcStream->mWidth,
                           srcStream->mHeight,
                           thumbWidth,
                           thumbHeight,
                           srcStream->format());
    }

    mJpegBuilder->prepareImage(&src);
    ret = mJpegBuilder->encodeImage(mainJpeg, thumbJpeg);
    if (ret != NO_ERROR) {
        ALOGE("%s encodeImage failed", __FUNCTION__);
        goto err_out;
    }

    ret = mJpegBuilder->buildImage(dstBuf);
    if (ret != NO_ERROR) {
        ALOGE("%s buildImage failed", __FUNCTION__);
        goto err_out;
    }

    // write jpeg size
    pDst = (uint8_t *)dstBuf->mVirtAddr;
    jpegBufferSize = getJpegBufferSize(src, meta);
    bufSize = (mCamera->mMaxJpegSize <= jpegBufferSize) ? mCamera->mMaxJpegSize : jpegBufferSize;

    jpegBlob = (struct camera3_jpeg_blob *)(pDst + bufSize -
                                            sizeof(struct camera3_jpeg_blob));
    jpegBlob->jpeg_blob_id = CAMERA3_JPEG_BLOB_ID;
    jpegBlob->jpeg_size = mJpegBuilder->getImageSize();

    ALOGI("%s, dstbuf size %d, jpeg_size %d, max jpeg size %d",
           __func__, (int)dstBuf->mSize, jpegBlob->jpeg_size, mCamera->mMaxJpegSize);


grbuffer_mapper.unlock(*(*dstBuf).mBufHandle);
err_out:
    if (mainJpeg) {
        delete mainJpeg;
    }

    if (thumbJpeg) {
        delete thumbJpeg;
    }

    return ret;
}

int32_t Stream::processFrameBuffer(StreamBuffer& src,
                                   sp<Metadata> meta __unused)
{
    ALOGV("%s", __func__);
    sp<Stream>& device = src.mStream;
    if (device == NULL) {
        ALOGE("%s invalid device stream", __func__);
        return 0;
    }

    StreamBuffer* out = mCurrent;
    if (out == NULL || out->mBufHandle == NULL) {
        ALOGE("%s invalid buffer handle", __func__);
        return 0;
    }

    fsl::ImageProcess *imageProcess = fsl::ImageProcess::getInstance();
    //ImageProcess *imageProcess = ImageProcess::getInstance();
    return imageProcess->handleFrame(*out, src);
}

int32_t Stream::processCaptureBuffer(StreamBuffer& src,
        sp<Metadata> meta)
{
    int32_t res = 0;

    ALOGV("%s", __func__);
    StreamBuffer* out = mCurrent;
    if (out == NULL || out->mBufHandle == NULL) {
        ALOGE("%s invalid buffer handle", __func__);
        return 0;
    }

    if (out->mAcquireFence != -1) {
        res = sync_wait(out->mAcquireFence, CAMERA_SYNC_TIMEOUT);
        if (res == -ETIME) {
            ALOGE("%s: Timeout waiting on buffer acquire fence",
                    __func__);
            return res;
        } else if (res) {
            ALOGE("%s: Error waiting on buffer acquire fence: %s(%d)",
                    __func__, strerror(-res), res);
            ALOGV("fence id:%d", out->mAcquireFence);
        }
        close(out->mAcquireFence);
    }

    if (mJpeg) {
        mJpegBuilder->reset();
        mJpegBuilder->setMetadata(meta);

        res = processJpegBuffer(src, meta);
        mJpegBuilder->setMetadata(NULL);
    }
    else {
        res = processFrameBuffer(src, meta);
    }

    return res;
}

int Stream::getType()
{
    return mType;
}

bool Stream::isInputType()
{
    return mType == CAMERA3_STREAM_INPUT ||
        mType == CAMERA3_STREAM_BIDIRECTIONAL;
}

bool Stream::isOutputType()
{
    return mType == CAMERA3_STREAM_OUTPUT ||
        mType == CAMERA3_STREAM_BIDIRECTIONAL;
}

bool Stream::isRegistered()
{
    return mRegistered;
}

bool Stream::isValidReuseStream(int id, camera3_stream_t *s)
{
    if (id != mId) {
        ALOGE("%s:%d: Invalid camera id for reuse. Got %d expect %d",
                __func__, mId, id, mId);
        return false;
    }

    if (s != mStream || s->stream_type != mType) {
        ALOGE("%s:%d: Invalid stream handle for reuse. Got %p expect %p",
                __func__, mId, s, mStream);
        return false;
    }

    if (s->width != mWidth || s->height != mHeight || s->format != mFormat) {
        ALOGE("%s:%d: Mismatched reused stream."
              "Got w:%d, h:%d, f:%d expect w:%d, h:%d, f:%d",
                __func__, mId, s->width, s->height, s->format,
                mWidth, mHeight, mFormat);
        return false;
    }

    ALOGV("%s:%d: Mismatched reused stream. usage got:0x%x expect:0x%x",
            __func__, mId, s->usage, mUsage);
    s->usage |= mUsage;
    //max_buffers is mNumBuffers-1 which is set in Stream constructor.
    s->max_buffers = mNumBuffers -1;

    return true;
}

void Stream::dump(int fd)
{
    android::Mutex::Autolock al(mLock);

    dprintf(fd, "Stream ID: %d (%p)\n", mId, mStream);
    dprintf(fd, "Stream Type: (%d)\n", mType);
    dprintf(fd, "Width: %d Height: %d\n", mWidth, mHeight);
    dprintf(fd, "Stream Format: (%d)", mFormat);
    // ToDo: prettyprint usage mask flags
    dprintf(fd, "Gralloc Usage Mask: %d\n", mUsage);
    dprintf(fd, "Buffers Registered: %s\n", mRegistered ? "true" : "false");
    dprintf(fd, "Number of Buffers: %d\n", mNumBuffers);
    for (uint32_t i = 0; i < mNumBuffers; i++) {
        if(mBuffers[i] == NULL)
            continue;
        dprintf(fd, "Buffer %d %d : %p\n", i, mNumBuffers,
                mBuffers[i]->mBufHandle);
    }
}

