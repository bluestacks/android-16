#include <stdio.h>
#include <dlfcn.h>
#include <cutils/log.h>

#include "Stream.h"
#include "CameraUtils.h"
#include "ImageProcess.h"
#include <ui/Rect.h>
#include <ui/GraphicBufferMapper.h>

#if defined(__LP64__)
#define LIB_PATH1 "/system/lib64"
#define LIB_PATH2 "/vendor/lib64"
#else
#define LIB_PATH1 "/system/lib"
#define LIB_PATH2 "/vendor/lib"
#endif

#define GPUENGINE "libg2d.so"
#define CLENGINE "libopencl-2d.so"

namespace fsl {

ImageProcess* ImageProcess::sInstance(0);
Mutex ImageProcess::sLock(Mutex::PRIVATE);

ImageProcess* ImageProcess::getInstance()
{
    Mutex::Autolock _l(sLock);
    if (sInstance != NULL) {
        return sInstance;
    }

    sInstance = new ImageProcess();
    return sInstance;
}

ImageProcess::ImageProcess()
    : mIpuFd(-1), mPxpFd(-1), mChannel(-1), m2DEnable(0), mG2dModule(NULL), mCLModule(NULL)
{
    if(mCLHandle != NULL) {
        ALOGI(" device is used!\n");
    }
}

ImageProcess::~ImageProcess()
{
    if (mIpuFd > 0) {
        close(mIpuFd);
        mIpuFd = -1;
    }

    if (mPxpFd > 0) {
        close(mPxpFd);
        mPxpFd = -1;
    }

    if (mCLHandle != NULL) {
        (*mCLClose)(mCLHandle);
    }

    if (mG2dModule != NULL) {
        dlclose(mG2dModule);
    }

    if (mCLModule != NULL) {
        dlclose(mCLModule);
    }
}

void ImageProcess::threadDestructor(void *handle)
{
    if (handle == NULL) {
        return;
    }

    ImageProcess::getInstance()->closeEngine(handle);
    free(handle);
}

int ImageProcess::openEngine(void** handle)
{
    if (mOpenEngine == NULL) {
        return -EINVAL;
    }

    return (*mOpenEngine)((void*)handle);
}

int ImageProcess::closeEngine(void* handle)
{
    if (mCloseEngine == NULL) {
        return -EINVAL;
    }

    return (*mCloseEngine)(handle);
}

void ImageProcess::getModule(char *path, const char *name)
{
    snprintf(path, PATH_MAX, "%s/%s",
                                 LIB_PATH1, name);
    if (access(path, R_OK) == 0)
        return;
    snprintf(path, PATH_MAX, "%s/%s",
                                 LIB_PATH2, name);
    if (access(path, R_OK) == 0)
        return;
    return;
}

int ImageProcess::handleFrame(StreamBuffer& dstBuf, StreamBuffer& srcBuf)
{
    int ret = 0;

    if (srcBuf.mStream == NULL || dstBuf.mStream == NULL) {
        return -EINVAL;
    }

    do {
        ret = handleFrameByCPU(dstBuf, srcBuf);
    } while(false);

    return ret;
}
 

int ImageProcess::convertNV12toNV21(StreamBuffer& dstBuf __unused , StreamBuffer& srcBuf __unused)
{
    sp<Stream> src, dst;
    src = srcBuf.mStream;
    dst = dstBuf.mStream;

    int Ysize = 0, UVsize = 0;
    uint8_t *srcIn, *dstOut;
    uint32_t *UVout;
    int size = (srcBuf.mSize > dstBuf.mSize) ? dstBuf.mSize : srcBuf.mSize;

    Ysize  = src->width() * src->height();
    UVsize = src->width() * src->height() >> 2;
    srcIn = (uint8_t *)srcBuf.mVirtAddr;
    dstOut = (uint8_t *)dstBuf.mVirtAddr;
    UVout = (uint32_t *)(dstOut + Ysize);

    memcpy(dstOut, srcIn, size);
    return 0;
}

int ImageProcess::YUV422To420(unsigned char yuv422[], unsigned char yuv420[], int width, int height)  
{          
    int ynum = width * height;  
    int i, j, k = 0;  
    
    for (i = 0; i < ynum; i++) {  
        yuv420[i] = yuv422[i * 2];  
    }  
    
    for (i = 0; i < height; i++) {  
        if ((i % 2) != 0) continue;  
        for (j = 0; j < (width / 2); j++) {  
            if ((4 * j + 1) > (2 * width)) break;  
            yuv420[ynum + k * 2 * width / 4 + j] = yuv422[i * 2 * width + 4 * j + 1];  
        }  
        k++;  
    }  
    
    k = 0;  
    for (i = 0; i < height; i++) {  
        if ((i % 2) == 0) continue;  
        for (j = 0; j < (width / 2); j++) {  
            if ((4 * j + 3) > (2 * width)) break;  
            yuv420[ynum + ynum / 4 + k * 2 * width / 4 + j] = yuv422[i * 2 * width + 4 * j + 3];  
        }  
        k++;  
    } 

    return 1;  
}

int ImageProcess::handleFrameByCPU(StreamBuffer& dstBuf, StreamBuffer& srcBuf)
{
    sp<Stream> src, dst;
    src = srcBuf.mStream;
    dst = dstBuf.mStream;

    if ((src->width() != dst->width()) || (src->height() != dst->height())) {
        ALOGE("%s:%d, Software don't support resize", __func__, __LINE__);
        return -EINVAL;
    }

    GraphicBufferMapper& grbuffer_mapper(GraphicBufferMapper::get());
    const Rect bounds(dst->width(), dst->height());
    int res = grbuffer_mapper.lock(*(dstBuf.mBufHandle), GRALLOC_USAGE_SW_WRITE_OFTEN, bounds, (void**)&dstBuf.mVirtAddr);

    if (res != NO_ERROR ) {
        ALOGE("%s: grbuffer_mapper.lock failure: %d -> %s",
             __FUNCTION__, res, strerror(res));
        return -1;
    }

    if (((dst->format() == HAL_PIXEL_FORMAT_YCbCr_420_888) ||
         (dst->format() == HAL_PIXEL_FORMAT_YCbCr_420_SP)) &&
        (src->format() == HAL_PIXEL_FORMAT_YCbCr_422_I)) {
         YUV422To420((uint8_t *)srcBuf.mVirtAddr,
                     (uint8_t *)dstBuf.mVirtAddr, dst->width(), dst->height());

    } else if ((src->format() == HAL_PIXEL_FORMAT_YCbCr_420_SP) &&
               (dst->format() == HAL_PIXEL_FORMAT_YCrCb_420_SP)) {
        convertNV12toNV21(dstBuf, srcBuf);
    } else if (src->format() == dst->format()) {
        YUYVCopyByLine((uint8_t *)dstBuf.mVirtAddr, dst->width(), dst->height(),
                 (uint8_t *)srcBuf.mVirtAddr, src->width(), src->height());
    } else {
        ALOGE("%s:%d, Software don't support format convert from 0x%x to 0x%x",
                 __func__, __LINE__, src->format(), dst->format());
        return -EINVAL;
    }

    grbuffer_mapper.unlock(*dstBuf.mBufHandle);
    return 0;
}


void ImageProcess::YUYVCopyByLine(uint8_t *dst, uint32_t dstWidth,
     uint32_t dstHeight, uint8_t *src, uint32_t srcWidth, uint32_t srcHeight)
{
    uint32_t i;
    int BytesPerPixel = 2;
    uint8_t *pDstLine = dst;
    uint8_t *pSrcLine = src;
    uint32_t bytesPerSrcLine = BytesPerPixel * srcWidth;
    uint32_t bytesPerDstLine = BytesPerPixel * dstWidth;
    uint32_t marginWidh = dstWidth - srcWidth;
    uint16_t *pYUV;

    if ((srcWidth > dstWidth) || (srcHeight > dstHeight)) {
        ALOGW("%s, para error", __func__);
        return;
    }

    for (i = 0; i < srcHeight; i++) {
        memcpy(pDstLine, pSrcLine, bytesPerSrcLine);

        // black margin, Y:0, U:128, V:128
        for (uint32_t j = 0; j < marginWidh; j++) {
            pYUV = (uint16_t *)(pDstLine + bytesPerSrcLine + j * BytesPerPixel);
            *pYUV = 0x8000;
        }

        pSrcLine += bytesPerSrcLine;
        pDstLine += bytesPerDstLine;
    }

    return;
}

void ImageProcess::convertYUYVtoNV12SP(uint8_t *inputBuffer,
            uint8_t *outputBuffer, int width, int height)
{
#define u32 unsigned int
#define u8 unsigned char

    u32 h, w;
    u32 nHeight = height;
    u32 nWidthDiv4 = width / 4;

    u32 *pYSrcOffset = (u32 *)inputBuffer;
    u32 value = 0;
    u32 value2 = 0;

    u32 *pYDstOffset = (u32 *)outputBuffer;
    u32 *pUVDstOffset = (u32 *)(((u8 *)(outputBuffer)) + width * height);

    for (h = 0; h < nHeight; h++) {
        if (!(h & 0x1)) {
            for (w = 0; w < nWidthDiv4; w++) {
                value = (*pYSrcOffset);
                value2 = (*(pYSrcOffset + 1));
                //use bitwise operation to get data from src to improve performance.
                *pYDstOffset = ((value & 0x000000ff) >> 0) |
                               ((value & 0x00ff0000) >> 8) |
                               ((value2 & 0x000000ff) << 16) |
                               ((value2 & 0x00ff0000) << 8);
                pYDstOffset += 1;

#ifdef PLATFORM_VERSION_4
                *pUVDstOffset = ((value & 0xff000000) >> 24) |
                                ((value & 0x0000ff00) >> 0) |
                                ((value2 & 0xff000000) >> 8) |
                                ((value2 & 0x0000ff00) << 16);
#else
                *pUVDstOffset = ((value & 0x0000ff00) >> 8) |
                                ((value & 0xff000000) >> 16) |
                                ((value2 & 0x0000ff00) << 8) |
                                ((value2 & 0xff000000) << 0);
#endif
                pUVDstOffset += 1;
                pYSrcOffset += 2;
            }
        } else {
            for (w = 0; w < nWidthDiv4; w++) {
                value = (*pYSrcOffset);
                value2 = (*(pYSrcOffset + 1));
                *pYDstOffset = ((value & 0x000000ff) >> 0) |
                               ((value & 0x00ff0000) >> 8) |
                               ((value2 & 0x000000ff) << 16) |
                               ((value2 & 0x00ff0000) << 8);
                pYSrcOffset += 2;
                pYDstOffset += 1;
            }
        }
    }
}

}
