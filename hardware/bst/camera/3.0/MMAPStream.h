#ifndef _MMAP_STREAM_H
#define _MMAP_STREAM_H

#include "VideoStream.h"

// stream uses memory map buffers which allcated in kernel space.
class MMAPStream : public VideoStream
{
public:
    MMAPStream(Camera* device);
    MMAPStream(Camera *device, bool mplane);
    virtual ~MMAPStream();

    // configure device.
    virtual int32_t onDeviceConfigureLocked(int32_t id);
    // start device.
    virtual int32_t onDeviceStartLocked();
    // stop device.
    virtual int32_t onDeviceStopLocked();

    // get buffer from V4L2.
    virtual int32_t onFrameAcquireLocked();
    // put buffer back to V4L2.
    virtual int32_t onFrameReturnLocked(int32_t index, StreamBuffer& buf);

    // allocate buffers.
    virtual int32_t allocateBuffersLocked() {return 0;}
    // free buffers.
    virtual int32_t freeBuffersLocked() {return 0;}

    int setHostCamVFlip(int isFlip);

private:
    bool mPlane;

};

#endif
