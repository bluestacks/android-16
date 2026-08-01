#ifndef __BLUESTACKS_SENSORS_H__
#define __BLUESTACKS_SENSORS_H__

#include <linux/input.h>
#include <poll.h>
#include <time.h>

#include <hardware/sensors.h>
#include <hardware/hardware.h>
#include "Xpl.h"

#define SIZEOFARRAY(arg) (sizeof(arg) / sizeof(arg[0]))
#define ID_ACCELEROMETER SENSORS_HANDLE_BASE + 0
#define ID_GYROSCOPE     SENSORS_HANDLE_BASE + 1
#define ID_MEGNETOFIELD  SENSORS_HANDLE_BASE + 2
#define ID_ORIENTATION   SENSORS_HANDLE_BASE + 3
#define ID_LIGHT         SENSORS_HANDLE_BASE + 4

#define NSEC_PER_SEC    1000000000L
#define NSEC_PER_USEC   1000L

#define BSTSENSOR_IDX(id)	(id - SENSORS_HANDLE_BASE)
#define BST_DNORM_TO_FLOAT(v)   ((double)v/(1000000))

extern bool		g_bSensorsDataAvail;

static inline uint64_t time_in_ns()
{
   timespec ts;
   clock_gettime(CLOCK_MONOTONIC, &ts);
   return ((int64_t) ts.tv_sec * NSEC_PER_SEC) +
                 ts.tv_nsec * NSEC_PER_SEC;
}

static inline uint64_t timeval_to_ns(const struct timeval& tv)
{
    return ((int64_t) tv.tv_sec * NSEC_PER_SEC) +
                 tv.tv_usec * NSEC_PER_USEC;
}


class SensorB
{

public:
    virtual int activate(int handle, int enabled) = 0;
    virtual int setDelay(int handle, int64_t ns) = 0;
    virtual int readData(sensors_event_t* data, int count, bool timeout) = 0;
    virtual int openDev(const char* dev) = 0;
    virtual int getDevice() = 0;
    virtual bool isPending() = 0;
    virtual void setWakeDesc(int fd, char msg) = 0;
    virtual ~SensorB() { }
};

class SensorsMgr 
{
public:
    /**
     * Every device data structure must begin with hw_device_t
     * followed by module specific public methods and attributes.
     */
    struct sensors_poll_device_1 device;
    int activate(int handle, int enabled);
    int setDelay(int handle, int64_t ns);
    int poll(sensors_event_t* data, int count);
    int sensorIndex(int handle);
    SensorsMgr();
    enum 
    {
        ACCL_ID,
        GYRO_ID,
        MEGN_ID,
        ORIE_ID,
        WAKE_ME,
	SENSOR_COUNT
    };
    ~SensorsMgr();

private:
    SensorB *m_Sensors[SENSOR_COUNT];
    struct pollfd m_pfd[SENSOR_COUNT];
    int m_Wfd[2];
};

#endif  // __BLUESTACKS_SENSORS_H__
