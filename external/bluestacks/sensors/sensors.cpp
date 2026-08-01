#define LOG_TAG "Sensor"

#include <condition_variable>
#include <fcntl.h>
#include <mutex>
#include <sys/stat.h>
#include <sys/types.h>
#include <thread>
#include <utils/Log.h>

#include "Xpl.h"
#include "sensors.h"

#define BSTSENSOR_NS_TO_MS(x)	(x/1000000)
#define BSTSENSOR_MS_TO_NS(x)	(x*1000000)
#define BSTSENSOR_MINDELAY_US	(200000)

typedef struct AccelerometerData {
    int32_t 	x;
    int32_t 	y;
    int32_t	z;
} bstsensor_accel_data_t;

static int bstsensor_open(const struct hw_module_t* module, const char* id, struct hw_device_t** device);

static void bstsensor_init_sensor(int id);
static int bstsensor_get_accel_data();

static struct sensor_t bstsensor_list[] = {
        {
            .name = "Accelerometer Sensor",
            .vendor =  "BlueStacks Inc.",
            .version =  0,
            .handle =  ID_ACCELEROMETER,
            .type =  SENSOR_TYPE_ACCELEROMETER,
            .maxRange =  GRAVITY_EARTH,
            .resolution =  1.0f/1000.0f,
            .power =  2.0f,
            .minDelay =  BSTSENSOR_MINDELAY_US,
            .fifoReservedEventCount =  0,
            .fifoMaxEventCount =  0,
            .stringType = SENSOR_STRING_TYPE_ACCELEROMETER,
            .requiredPermission = "",
            .maxDelay = 0,
            .flags = SENSOR_FLAG_CONTINUOUS_MODE,
            .reserved = { }
        },
        {
            .name =  "Gyroscope Sensor",
            .vendor =  "BlueStacks Inc.",
            .version =  0,
            .handle =  ID_GYROSCOPE,
            .type =  SENSOR_TYPE_GYROSCOPE,
            .maxRange =  7,
            .resolution =  1.0f/1000.0f,
            .power =  1.0f,
            .minDelay =  BSTSENSOR_MINDELAY_US,
            .fifoReservedEventCount =  0,
            .fifoMaxEventCount =  0,
            .stringType = SENSOR_STRING_TYPE_GYROSCOPE,
            .requiredPermission = "",
            .maxDelay = 0,
            .flags = SENSOR_FLAG_CONTINUOUS_MODE,
            .reserved = { }
        },
        {
            .name =  "Megnetic Field Sensor",
            .vendor =  "BlueStacks Inc.",
            .version =  0,
            .handle =  ID_MEGNETOFIELD,
            .type =  SENSOR_TYPE_MAGNETIC_FIELD,
            .maxRange =  1000,
            .resolution =  1.0f/100.0f,
            .power =  1.0f,
            .minDelay =  BSTSENSOR_MINDELAY_US,
            .fifoReservedEventCount =  0,
            .fifoMaxEventCount =  0,
            .stringType = SENSOR_STRING_TYPE_MAGNETIC_FIELD,
            .requiredPermission = "",
            .maxDelay = 0,
            .flags = SENSOR_FLAG_CONTINUOUS_MODE,
            .reserved = { }
        },
        {
            .name = "Orientation Sensor",
            .vendor = "BlueStacks Inc.",
            .version = 0,
            .handle = ID_ORIENTATION,
            .type = SENSOR_TYPE_ORIENTATION,
            .maxRange = 360,
            .resolution = 1.0f/100.0f,
            .power = 2.0f,
            .minDelay = BSTSENSOR_MINDELAY_US,
            .fifoReservedEventCount = 0,
            .fifoMaxEventCount = 0,
            .stringType = SENSOR_STRING_TYPE_ORIENTATION,
            .requiredPermission = "",
            .maxDelay = 0,
            .flags = SENSOR_FLAG_CONTINUOUS_MODE,
            .reserved =  { }
        },
        {
            .name = "Light sensor",
            .vendor =  "BlueStacks Inc.",
            .version =  0,
            .handle = ID_LIGHT,
            .type = SENSOR_TYPE_LIGHT,
            .maxRange = 10000.0f,
            .resolution =  1.0f,
            .power = 0.15f,
            .minDelay = BSTSENSOR_MINDELAY_US,
            .fifoReservedEventCount = 0,
            .fifoMaxEventCount  = 0,
            .stringType  = SENSOR_STRING_TYPE_LIGHT,
            .requiredPermission = "",
            .maxDelay = 0,
            .flags = SENSOR_FLAG_ON_CHANGE_MODE,
            .reserved = {}
       }
};

#define BSTSENSOR_NUM_SENSORS	(sizeof(bstsensor_list)/sizeof(bstsensor_list[0]))

struct bstsensor_globals {
    std::condition_variable     cond;
    std::mutex                  mutex;
    bool                        b_data_avail;

    std::mutex                  data_mutex;

    sensors_event_t             sensor_data [BSTSENSOR_NUM_SENSORS];
    bool                        b_sensor_enabled [BSTSENSOR_NUM_SENSORS];
    int64_t                     delay_ms [BSTSENSOR_NUM_SENSORS];
    int                         fifo_q;
} g_bstsensor;

struct sensors_poll_device_1 g_bstsensor_device;

static int bstsensor_get_sensors_list(struct sensors_module_t *module, struct sensor_t const **l)
{
    ALOGD("%s",__FUNCTION__);
    *l = bstsensor_list;
    return SIZEOFARRAY(bstsensor_list);
}

static struct hw_module_methods_t sensors_funcs = {
        .open = bstsensor_open
};

struct sensors_module_t HAL_MODULE_INFO_SYM = {
        .common =  {
                .tag =  HARDWARE_MODULE_TAG,
                .version_major =  1,
                .version_minor =  3,
                .id = SENSORS_HARDWARE_MODULE_ID,
                .name = "BlueStacks virtual Sensors",
                .author = "BlueStacks Inc.",
                .methods = &sensors_funcs,
                .dso = 0,
                .reserved = { }
        },
        .get_sensors_list =  bstsensor_get_sensors_list
};

static int bstsensor_close(struct hw_device_t *dev)
{
    ALOGD("%s",__FUNCTION__);

    return 0;
}

static void bstsensor_wakeup_poll()
{
    std::lock_guard<std::mutex> lock(g_bstsensor.mutex);

    g_bstsensor.cond.notify_one();
    g_bstsensor.b_data_avail = true;
}

static int bstsensor_activate(struct sensors_poll_device_t *dev, int handle, int enabled)
{
    ALOGD("%s, handle %d enabled %d", __FUNCTION__, handle, enabled);

    g_bstsensor.b_sensor_enabled [BSTSENSOR_IDX(handle)] = enabled;

    bstsensor_init_sensor(handle);

    bstsensor_wakeup_poll();

    return 0;
}

static int bstsensor_setDelay(struct sensors_poll_device_t *dev, int handle, int64_t ns)
{
    ALOGD("%s, handle %d, delay ms %ld",__FUNCTION__, handle, (long)BSTSENSOR_NS_TO_MS(ns));

    g_bstsensor.delay_ms[BSTSENSOR_IDX(handle)] = BSTSENSOR_NS_TO_MS(ns);

    bstsensor_init_sensor(handle);

    bstsensor_wakeup_poll();

    return 0;
}

static int bstsensor_poll(struct sensors_poll_device_t *dev, sensors_event_t* data, int count)
{
   // ALOGD("%s",__FUNCTION__);
    int done_count = 0;

    Xtime nextDatatime = 0;
    u64 next_data_time_ms = 0;
    Xtime cur_time;
    bool async_data_avail = false;
   
loop_and_get_data:

    cur_time = xtimeNow();

    std::unique_lock<std::mutex> data_lock(g_bstsensor.data_mutex);

    for (unsigned int i = 0; i < BSTSENSOR_NUM_SENSORS; i++)
    {
        if (done_count >= count)
            break;

        if (!g_bstsensor.b_sensor_enabled[i])
            continue;


        u64  next_wake_time = BSTSENSOR_NS_TO_MS(g_bstsensor.sensor_data[i].timestamp) + g_bstsensor.delay_ms[i];

        next_data_time_ms = (next_data_time_ms == 0 ? next_wake_time :
                std::min(next_data_time_ms, next_wake_time));


        if (!async_data_avail && (next_wake_time > xtimeToMsecs(cur_time)))
            continue;

        g_bstsensor.sensor_data[i].timestamp = BSTSENSOR_MS_TO_NS(xtimeToMsecs(cur_time));

#if 0
        //VT: To debug
        if (async_data_avail && (i == BSTSENSOR_IDX(ID_ACCELEROMETER))) {
            ALOGD("%s: x %f y %f z %f", __FUNCTION__,
                    g_bstsensor.sensor_data[i].acceleration.x,
                    g_bstsensor.sensor_data[i].acceleration.y,
                    g_bstsensor.sensor_data[i].acceleration.z);
        }
#endif

        *data++ = g_bstsensor.sensor_data[i];
        done_count++;

    }

    data_lock.unlock();

    if (!done_count) {
        /* Wait */
        xerr_t err = 0;
        std::unique_lock<std::mutex> lock(g_bstsensor.mutex);
        while (!g_bstsensor.b_data_avail) {

            if (next_data_time_ms == 0) {
                //block till we get the data
                g_bstsensor.cond.wait(lock);
                continue;
            }

            // timed wait
            u64 duration = next_data_time_ms - xtimeToMsecs(cur_time);

            auto status = g_bstsensor.cond.wait_for(lock, std::chrono::milliseconds{ duration });
            if (status == std::cv_status::timeout) {
                err = XERR_TIMEDOUT;
                break;
            }
            err = XERR_SUCCESS;
        }

        if (err == XERR_SUCCESS)
            async_data_avail = true;

        g_bstsensor.b_data_avail = false;
        lock.unlock();
        next_data_time_ms = 0;
        goto loop_and_get_data;
    }

    // ALOGD("%s: returning %d events", __FUNCTION__, done_count);
    return done_count;
}

static int bstsensor_batch(__attribute__((unused)) struct sensors_poll_device_1* dev,
        int sensor_handle, __attribute__((unused)) int flags, int64_t sampling_period_ns,
        __attribute__((unused)) int64_t max_report_latency_ns)
{
    ALOGD("%s",__FUNCTION__);
    return bstsensor_setDelay((struct sensors_poll_device_t*)dev,
            sensor_handle, sampling_period_ns);
}

static int bstsensor_flush(__attribute__((unused)) struct sensors_poll_device_1* dev,
        int handle)
{
    ALOGD("%s",__FUNCTION__);
    // returning error as we do not support batching,
    // if no error is returned flush will be marked pending
    // and sensor events are discarded
    return EINVAL;
}

static void bstsensor_init_sensor(int id)
{
    sensors_event_t *evt = &g_bstsensor.sensor_data[BSTSENSOR_IDX(id)];

    std::lock_guard<std::mutex> data_lock(g_bstsensor.data_mutex);

    if (id == ID_ACCELEROMETER)
    {
        evt->version = sizeof(*evt);
        evt->sensor = ID_ACCELEROMETER;
        evt->type = SENSOR_TYPE_ACCELEROMETER;
        evt->acceleration.status = SENSOR_STATUS_ACCURACY_HIGH;
        /*
         * x ranges -9.8 < x < 9.8, -ve toward lift tilt and +ve on right tilt.
         * y not in use as of now.
         * Z will always have acc equal to GRAVITY_EARTH downward, not in use.
         */
        evt->acceleration.x = 0.0f;
        evt->acceleration.y = 0.0f;
        evt->acceleration.z = GRAVITY_EARTH;
    }

    else if (id == ID_GYROSCOPE)
    {
        evt->version = sizeof(*evt);
        evt->sensor = ID_GYROSCOPE;
        evt->type = SENSOR_TYPE_GYROSCOPE;
        evt->gyro.status = SENSOR_STATUS_ACCURACY_HIGH;
        evt->gyro.x = 0.0f;
        evt->gyro.y = 0.0f;
        evt->gyro.z = 0.0f;
    }

    else if (id == ID_MEGNETOFIELD)
    {
        evt->version = sizeof(*evt);
        evt->sensor = ID_MEGNETOFIELD;
        evt->type = SENSOR_TYPE_MAGNETIC_FIELD;
        evt->magnetic.status = SENSOR_STATUS_ACCURACY_MEDIUM;
        evt->magnetic.v[0] = 0.001f;
        evt->magnetic.v[1] = 0.001f;
        evt->magnetic.v[2] = 0.001f;
    }

    else if (id == ID_ORIENTATION)
    {
        evt->version = sizeof(*evt);
        evt->sensor = ID_ORIENTATION;
        evt->type = SENSOR_TYPE_ORIENTATION;
        evt->orientation.status = SENSOR_STATUS_ACCURACY_MEDIUM;
        evt->orientation.azimuth = 0.0f;
        evt->orientation.pitch = 0.0f;
        evt->orientation.roll = 0.0f;
    }	

    evt->timestamp = BSTSENSOR_MS_TO_NS(xtimeToMsecs(xtimeNow()));
}

static void bstsensor_init_sensors()
{

    for (unsigned int i = 0; i < BSTSENSOR_NUM_SENSORS; i++)
    {
        bstsensor_init_sensor(bstsensor_list[i].handle);
        g_bstsensor.delay_ms[i] = 200; //200 ms initial delay
        g_bstsensor.b_sensor_enabled[i] = false;
    }

}

static void bstsensor_accel_data_reader()
{

    const char *bstsensor_fifo = "/data/bstfifo";
    unlink(bstsensor_fifo);

    if (mkfifo(bstsensor_fifo, 0660) == -1) {
        ALOGE("%s: Couldn't create a fifo with error %s", __FUNCTION__,
                strerror(errno));
        return;
    }

    std::thread([=]
        {

            ALOGD("%s: trying to open bstfifo..", __FUNCTION__);

            g_bstsensor.fifo_q  = open(bstsensor_fifo, O_RDONLY);

            if (g_bstsensor.fifo_q == -1) {
                ALOGE("%s: Unable to open the bstfifo pipe", __FUNCTION__);
                return;
            }

            while (1) {
                int nBytes;
                bstsensor_accel_data_t d;

                nBytes = read(g_bstsensor.fifo_q, &d, sizeof(d));

                if (nBytes == -1) {
                    ALOGE("BUG: read on bstfifo returned error %s", strerror(errno));
                    break;
                }

                if (nBytes != sizeof(d)) {
                    ALOGE("BUG: bstfifo incomplete data bytes read %d, expected %zu", nBytes, sizeof(d));
                    continue; 
                }

                /* save the data */
                std::unique_lock<std::mutex> data_lock(g_bstsensor.data_mutex);

                sensors_event_t *evt = &g_bstsensor.sensor_data[BSTSENSOR_IDX(ID_ACCELEROMETER)];

                evt->acceleration.x = -BST_DNORM_TO_FLOAT(d.x)*GRAVITY_EARTH;
                evt->acceleration.y = -BST_DNORM_TO_FLOAT(d.y)*GRAVITY_EARTH;
                evt->acceleration.z = -BST_DNORM_TO_FLOAT(d.z)*GRAVITY_EARTH;
                evt->timestamp = BSTSENSOR_MS_TO_NS(xtimeToMsecs(xtimeNow())); //in ns

                data_lock.unlock();

                /*
                   ALOGD("%s: Waking up poll with data x= %f y= %f z= %f", __FUNCTION__,
                   evt->acceleration.x, evt->acceleration.y, evt->acceleration.z);
                 */

                // Wake up the waiter in poll()
                bstsensor_wakeup_poll();
            }
        }
    ).detach();
}

static int bstsensor_open(const struct hw_module_t* module, const char* id, struct hw_device_t** device)
{
    int status = -EINVAL;

    ALOGD("%s: id %s", __FUNCTION__, id);

    //xpl_lib_init(NULL);

    memset(&g_bstsensor_device, 0, sizeof(sensors_poll_device_1));

    g_bstsensor_device.common.tag = HARDWARE_DEVICE_TAG;
    g_bstsensor_device.common.version  = SENSORS_DEVICE_API_VERSION_1_3;
    g_bstsensor_device.common.module   = const_cast<hw_module_t*>(module);
    g_bstsensor_device.common.close    = bstsensor_close;
    g_bstsensor_device.activate        = bstsensor_activate;
    g_bstsensor_device.setDelay        = bstsensor_setDelay;
    g_bstsensor_device.poll            = bstsensor_poll;
    g_bstsensor_device.batch           = bstsensor_batch;
    g_bstsensor_device.flush           = bstsensor_flush;

    g_bstsensor.fifo_q = -1;

    *device = &g_bstsensor_device.common;

    bstsensor_init_sensors();

    /* run gcall based get_accel_data_thread() */
    bstsensor_accel_data_reader();

    status = 0;

    return status;
}

