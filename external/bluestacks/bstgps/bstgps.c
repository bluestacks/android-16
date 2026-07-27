/******************************************************************************
 * Copyright 2011 BlueStack Systems, Inc.
 * All Rights Reserved
 *
 * THIS IS UNPUBLISHED PROPRIETARY SOURCE CODE OF BLUESTACK SYSTEMS, INC.
 * The copyright notice above does not evidence any actual or intended
 * publication of such source code.

 * Bst Gps implementation.
 *****************************************************************************/

#include <stdlib.h>
#include <pthread.h>
#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <stdio.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/stat.h>
#include <time.h>
#include <semaphore.h>

#include <limits.h>

#include <sys/socket.h>
#include <arpa/inet.h>
#include <netdb.h>

#define  LOG_TAG  "bst_gps"

#define FALSE 0
#define TRUE 1
#include <cutils/log.h>
#include <cutils/sockets.h>
#include <cutils/properties.h>
#include <hardware/gps.h>


static int                  bst_gps_detail_logs             = FALSE;
static int                  bst_gps_is_exiting              = FALSE;
static pthread_cond_t       bst_gps_cond_variable;
static pthread_mutex_t      bst_gps_thread_mutex;
volatile double             latitude                        = 40.782613;
volatile double             longitude                       = -73.965280;

#define BST_GPS_DEBUG_LOG_CHECK_FILE		"/data/gps_debug_log"

#define  DEBUG_LOG(...)   bst_gps_detail_logs? ALOGD(__VA_ARGS__) : ((void)0)
typedef void (*start_t)(void*);

/* Nmea Parser stuff */
#define  NMEA_MAX_SIZE  200

enum {
    STATE_QUIT  = 0,
    STATE_INIT  = 1,
    STATE_START = 2
};

typedef struct {
    GpsLocation  fix;
    GpsSvStatus  sv_status;
    int     sv_status_changed;
} NmeaReader;


typedef struct {
    volatile int            init;
    int                     fd;
    int                     ctrl_fd;
    GpsCallbacks            callbacks;
    AGpsCallbacks           a_callbacks;
    GpsXtraCallbacks        xtra_callbacks;
    GpsStatus               gps_status;
    char                    nmea_buf[512];
    int                     nmea_len;
    pthread_t               thread;
    sem_t                   fix_sem;
    pthread_t               tmr_thread;
    int                     control[2];
    int                     min_interval; // in ms
    NmeaReader              reader;

} GpsState;

static GpsState  _gps_state[1];
static GpsState *gps_state = _gps_state;

static void *gps_timer_thread( void*  arg );

/* Since NMEA parser requires locks */
#define GPS_STATE_LOCK_FIX(_s)           \
{                                        \
    int ret;                             \
    do {                                 \
        ret = sem_wait(&(_s)->fix_sem);  \
    } while (ret < 0 && errno == EINTR); \
}

#define GPS_STATE_UNLOCK_FIX(_s)         \
    sem_post(&(_s)->fix_sem)

void bst_gps_is_detail_logs_enabled ()
{
    int         fd              = -1;

    fd = open (BST_GPS_DEBUG_LOG_CHECK_FILE, O_RDONLY);
    if (fd != -1)
    {
        bst_gps_detail_logs = 1;
        close (fd);
    }
}

static void nmea_reader_init( NmeaReader*  r )
{
    int i;
    memset( r, 0, sizeof(*r) );

    // Initialize the sizes of all the structs we use
    r->fix.size = sizeof(GpsLocation);
    r->sv_status.size = sizeof(GpsSvStatus);
    for (i = 0; i < GPS_MAX_SVS; i++) {
        r->sv_status.sv_list[i].size = sizeof(GpsSvInfo);
    }
}


/*****************************************************************/
/*****************************************************************/
/*****                                                       *****/
/*****       C O N N E C T I O N   S T A T E                 *****/
/*****                                                       *****/
/*****************************************************************/
/*****************************************************************/

/* commands sent to the gps thread */
enum {
    CMD_QUIT  = 0,
    CMD_START = 1,
    CMD_STOP  = 2
};


static void gps_state_start( GpsState*  s )
{
    char  cmd = CMD_START;
    int   ret;

    do {
        ret=write( s->control[0], &cmd, 1 );
    } while (ret < 0 && errno == EINTR);

    if (ret != 1)
        DEBUG_LOG("%s: could not send CMD_START command: ret=%d: %s",
                __FUNCTION__, ret, strerror(errno));
}


static void gps_state_stop( GpsState*  s )
{
    char  cmd = CMD_STOP;
    int   ret;

    do {
        ret=write( s->control[0], &cmd, 1 );
    } while (ret < 0 && errno == EINTR);

    if (ret != 1)
        DEBUG_LOG("%s: could not send CMD_STOP command: ret=%d: %s",
                __FUNCTION__, ret, strerror(errno));
}


static int epoll_register( int  epoll_fd, int  fd )
{
    struct epoll_event  ev;
    int                 ret, flags;

    /* important: make the fd non-blocking */
    flags = fcntl(fd, F_GETFL);
    fcntl(fd, F_SETFL, flags | O_NONBLOCK);

    ev.events  = EPOLLIN;
    ev.data.fd = fd;
    do {
        ret = epoll_ctl( epoll_fd, EPOLL_CTL_ADD, fd, &ev );
    } while (ret < 0 && errno == EINTR);
    return ret;
}


static int epoll_deregister( int  epoll_fd, int  fd )
{
    int  ret;
    do {
        ret = epoll_ctl( epoll_fd, EPOLL_CTL_DEL, fd, NULL );
    } while (ret < 0 && errno == EINTR);
    return ret;
}

static void gps_nmea_thread_cb( GpsState* state )
{
    DEBUG_LOG("%s()", __FUNCTION__ );
    if (state->init) {
        state->callbacks.nmea_cb(state->reader.fix.timestamp,&state->nmea_buf[0],state->nmea_len);
        GPS_STATE_UNLOCK_FIX(state);
    }
}

static void gps_nmea_cb( GpsState* state , const char* buf, int len)
{
    DEBUG_LOG("%s()", __FUNCTION__ );
    // Forward NMEA sentences ....
    if (state->callbacks.nmea_cb) {
        GPS_STATE_LOCK_FIX(state);
        memcpy(&state->nmea_buf[0],buf,len);
        state->nmea_buf[len] = 0;
        state->nmea_len = len;
        state->callbacks.create_thread_cb("nmea",(start_t)gps_nmea_thread_cb,(void*)state);
    }
}

static void gps_status_thread_cb( GpsState* state )
{
    DEBUG_LOG("%s()", __FUNCTION__ );
    if (state->init) {
        state->callbacks.status_cb(&state->gps_status);
        GPS_STATE_UNLOCK_FIX(state);
    }
}

static void gps_status_cb( GpsState* state , GpsStatusValue status)
{
    DEBUG_LOG("%s()", __FUNCTION__ );
    if (state->callbacks.status_cb) {
        GPS_STATE_LOCK_FIX(state);

        state->gps_status.size = sizeof(GpsStatus);
        state->gps_status.status = status;
        state->callbacks.create_thread_cb("status",(start_t)gps_status_thread_cb,(void*)state);
        DEBUG_LOG("gps status callback: 0x%x", status);
    }
}

static void gps_set_capabilities_cb( GpsState* state , uint32_t caps)
{
    DEBUG_LOG("%s()", __FUNCTION__ );
    if (state->callbacks.set_capabilities_cb) {
        state->callbacks.create_thread_cb("caps",(start_t)state->callbacks.set_capabilities_cb,(void*)(uintptr_t)caps);
    }
}

static void gps_location_thread_cb( GpsState* state )
{
    DEBUG_LOG("%s()", __FUNCTION__ );
    if (state->init) {
        state->callbacks.location_cb( &state->reader.fix );
        state->reader.fix.flags = 0;
        GPS_STATE_UNLOCK_FIX(state);
    }
}

static void gps_location_cb( GpsState* state )
{
    DEBUG_LOG("%s()", __FUNCTION__ );
    if (state->callbacks.location_cb) {
        GPS_STATE_LOCK_FIX(state);
        state->callbacks.create_thread_cb("fix",(start_t)gps_location_thread_cb,(void*)state);
    }
}

static void gps_sv_status_thread_cb( GpsState* state )
{
    DEBUG_LOG("%s()", __FUNCTION__ );
    if (state->init) {
        state->callbacks.sv_status_cb( &state->reader.sv_status );
        state->reader.sv_status_changed = 0;
        GPS_STATE_UNLOCK_FIX(state);
    }
}

static void gps_sv_status_cb( GpsState* state )
{
    DEBUG_LOG("%s()", __FUNCTION__ );
    if (state->callbacks.sv_status_cb) {
        GPS_STATE_LOCK_FIX(state);
        state->callbacks.create_thread_cb("sv-status",(start_t)gps_sv_status_thread_cb,(void*)state);
    }
}


/* this is the main thread, it waits for commands from gps_state_start/stop and,
 * when started, messages from the QEMU GPS daemon. these are simple NMEA sentences
 * that must be parsed to be converted into GPS fixes sent to the framework
 */
static void* gps_state_thread( void* arg )
{
    GpsState*   state = (GpsState*) arg;
    NmeaReader  *reader;
    int         epoll_fd   = epoll_create(2);
    int         started    = 0;
    //int         gps_fd     = state->fd;
    int         control_fd = state->control[1];
    
    if (epoll_fd == -1)
    {
        ALOGE("epoll_create unexpected error: %s", strerror(errno));
        goto Exit;
    }
    
    reader = &state->reader;
    nmea_reader_init( reader );

    // register control file descriptors for polling
    epoll_register( epoll_fd, control_fd );
    //epoll_register( epoll_fd, gps_fd );

    DEBUG_LOG("gps thread running");

    gps_set_capabilities_cb( state , GPS_CAPABILITY_MSA | GPS_CAPABILITY_MSB );

    DEBUG_LOG("after set capabilities");

    gps_status_cb( state , GPS_STATUS_ENGINE_ON);

    DEBUG_LOG("after set status");

    // now loop
    while (1) {
        struct epoll_event   events[2];
        int                  ne, nevents;

        nevents = epoll_wait( epoll_fd, events, 2, -1 );
        if (nevents < 0) {
            if (errno != EINTR)
                ALOGE("epoll_wait() unexpected error: %s", strerror(errno));
            continue;
        }
        DEBUG_LOG("gps thread received %d events", nevents);
        for (ne = 0; ne < nevents; ne++) {
            if ((events[ne].events & (EPOLLERR|EPOLLHUP)) != 0) {
                ALOGE("EPOLLERR or EPOLLHUP after epoll_wait() !?");
                goto Exit;
            }
            if ((events[ne].events & EPOLLIN) != 0) {
                int  fd = events[ne].data.fd;

                if (fd == control_fd)
                {
                    char  cmd = -1;
                    int   ret;
                    DEBUG_LOG("gps control fd event");
                    do {
                        ret = read( fd, &cmd, 1 );
                    } while (ret < 0 && errno == EINTR);

                    if (cmd == CMD_QUIT) {
                        DEBUG_LOG("gps thread quitting on demand");
                        goto Exit;
                    }
                    else if (cmd == CMD_START) {
                        if (!started) {
                            DEBUG_LOG("gps thread starting location_cb=%p", state->callbacks.location_cb);
                            started = 1;

                            gps_status_cb( state , GPS_STATUS_SESSION_BEGIN);

                            state->init = STATE_START;


                            if ( pthread_create( &state->tmr_thread, NULL, gps_timer_thread, state ) != 0 ) {
                                ALOGE("could not create gps timer thread: %s", strerror(errno));
                                started = 0;
                                state->init = STATE_INIT;
                                goto Exit;
                            }
                        } else if (started >= 1) {
                            started++;
                        }
                    }
                    else if (cmd == CMD_STOP) {
                        // Remove the inotify handler only when last process/thread who is using it
                        // will call stop request. Otherwise just decrement the counter by one.
                        if (1 == started) {
                            void *dummy;
                            DEBUG_LOG("gps thread stopping");
                            started = 0;

                            state->init = STATE_INIT;

                            pthread_join(state->tmr_thread, &dummy);

                            gps_status_cb( state , GPS_STATUS_SESSION_END);

                        } else if (started > 1) {
                            started--;
                        }
                    }
                }
                else
                {
                    ALOGE("epoll_wait() returned unkown fd %d ?", fd);
                }
            }
        }
    }
Exit:

    gps_status_cb( state , GPS_STATUS_ENGINE_OFF);

    return NULL;
}

static void bst_gps_get_location (double *latitude_ptr, double *longitude_ptr)
{

    DEBUG_LOG("bstgps: %s called\n", __func__);

    double                  latitude_tmp    = 0;
    double                  longitude_tmp   = 0;
    char                    sysLoc[PROPERTY_VALUE_MAX];

    property_get("bst.config.sysLoc", sysLoc, "0,0");

    if(sscanf (sysLoc,"%lf,%lf", &latitude_tmp, &longitude_tmp) != 2)
    {
        ALOGE("bstgps: could not read loc data to memory in : %s", __FUNCTION__);
        return;
    }

    DEBUG_LOG("bstgps: %s: location from file is : %lf %lf \n",__FUNCTION__, latitude_tmp, longitude_tmp);
    
    if (latitude_tmp != 0 && longitude_tmp != 0) {
        latitude = latitude_tmp;
        longitude = longitude_tmp;
    }

    *latitude_ptr = latitude;
    *longitude_ptr = longitude;

    return;
}

static void update_gps_data(GpsState * state)
{
    struct 			timeval tv 		= {0,};
    int64_t			fix_time 		= 0;
    double          latitude        = 0;
    double          longitude       = 0;
    double			altitude		= 1;
    int				usedmask		= 0;
    int   			num 			= 0;

    bst_gps_get_location(&latitude, &longitude);

    if (latitude == 0 && longitude == 0) {
        // setting default location to 40.782613, -73.965280 (USA New York central park location)
        latitude = 40.782613;
        longitude = -73.965280;
    }

    state->reader.fix.latitude = latitude;
    state->reader.fix.longitude = longitude;

    state->reader.fix.flags = GPS_LOCATION_HAS_LAT_LONG | GPS_LOCATION_HAS_ACCURACY | GPS_LOCATION_HAS_SPEED;
    state->reader.fix.flags |= ( altitude == 0.0f ? 0 : GPS_LOCATION_HAS_ALTITUDE);

    gettimeofday(&tv,NULL);
    long long ms = tv.tv_sec;
    fix_time = ms * 1000 + tv.tv_usec / 1000;
    state->reader.fix.timestamp = fix_time;
    state->reader.fix.accuracy = 1;

    DEBUG_LOG("latitude=%f, longitude=%f, altitude=%f, time:%lld",
            latitude,
            longitude,
            altitude,
            (long long)fix_time); 
    state->reader.sv_status.num_svs = 8;
    for (; num < 8; num++) {
        state->reader.sv_status.sv_list[num].size = sizeof(GpsSvInfo);
        state->reader.sv_status.sv_list[num].prn = num + 1;
        state->reader.sv_status.sv_list[num].snr = rand() % (80 + 1 - 20) + 20;
        state->reader.sv_status.sv_list[num].elevation = 40;
        state->reader.sv_status.sv_list[num].azimuth = (rand() % (40 + 1 - 1) + 1) * 8;
        state->reader.sv_status_changed = 1;
        usedmask |=  1ul << num;
    }
    state->reader.sv_status.used_in_fix_mask = usedmask;
}

static void* gps_timer_thread( void*  arg )
{
    GpsState *state = (GpsState *)arg;
    char buff[] = "$GPRMC,$GNRMC,A,\r\n";

    DEBUG_LOG("gps entered timer thread");

    do {
        DEBUG_LOG("gps timer exp");
        update_gps_data(state);
        if (state->reader.fix.flags != 0) {

            DEBUG_LOG("gps fix cb: 0x%x", state->reader.fix.flags);
            gps_location_cb( state );
        }

        if (state->reader.sv_status_changed != 0) {

            DEBUG_LOG("gps sv status callback");
            gps_sv_status_cb( state );

        }

        gps_nmea_cb(state,buff,strlen(buff));

        if (state->min_interval < 1000) {
            state->min_interval = 1000;
        }

        usleep(state->min_interval*1000);

    } while(state->init == STATE_START);

    DEBUG_LOG("gps timer thread destroyed");

    bst_gps_is_exiting = TRUE;
    pthread_cond_signal (&bst_gps_cond_variable);
    return NULL;

}

static void gps_state_done( GpsState*  s )
{
    // tell the thread to quit, and wait for it
    char   cmd = CMD_QUIT;
    void*  dummy;
    int ret;

    DEBUG_LOG("gps send quit command");

    do { ret=write( s->control[0], &cmd, 1 ); }
    while (ret < 0 && errno == EINTR);

    DEBUG_LOG("gps waiting for command thread to stop");

    pthread_join(s->thread, &dummy);

    DEBUG_LOG("gps command thread stopped");
    /* Timer thread depends on this state check */
    s->init = STATE_QUIT;
    s->min_interval = 1000;

    // close the control socket pair
    close( s->control[0] ); s->control[0] = -1;
    close( s->control[1] ); s->control[1] = -1;

    // close connection to the GPS
    //close( s->fd ); s->fd = -1;
    close( s->ctrl_fd ); s->ctrl_fd = -1;

    sem_destroy(&s->fix_sem);

    memset(s, 0, sizeof(*s));

    DEBUG_LOG("gps deinit complete");
}


static void gps_state_init( GpsState*  state )
{
    state->init       = STATE_INIT;
    state->control[0] = -1;
    state->control[1] = -1;
    //state->fd         = -1;
    state->ctrl_fd    = -1;
    state->min_interval   = 1000;


    if (sem_init(&state->fix_sem, 0, 1) != 0) {
        DEBUG_LOG("gps semaphore initialization failed! errno = %d", errno);
        return;
    }

    if ( socketpair( AF_LOCAL, SOCK_STREAM, 0, state->control ) < 0 ) {
        ALOGE("could not create thread control socket pair: %s", strerror(errno));
        goto Fail;
    }

    //state->fd = 1;
    // Create a thread gps_state_thread which listens for commands like CMD_START and
    // CMD_STOP,in CMD_START we create a gps_timer_thread which calls gps_location_cb,
    // gps_nmea_cb and gps_sv_status_cb which reports values to framework via callback
    // threads every 1 sec
    if ( pthread_create( &state->thread, NULL, gps_state_thread, state ) != 0 ) {
        ALOGE("could not create gps thread: %s", strerror(errno));
        goto Fail;
    }


    DEBUG_LOG("gps state initialized");

    return;

Fail:
    gps_state_done( state );
}

/*****************************************************************/
/*****************************************************************/
/*****                                                       *****/
/*****       I N T E R F A C E                               *****/
/*****                                                       *****/
/*****************************************************************/
/*****************************************************************/

static int gps_init(GpsCallbacks* callbacks)
{
    DEBUG_LOG("%s callbacks:%p",__FUNCTION__,callbacks);
    GpsState*  s = _gps_state;

    bst_gps_is_detail_logs_enabled ();

    if (!s->init)
        gps_state_init(s);

    // if state is not initialized retrun and framework reports "Failed to enable location provider"
    if (!s->init)
        return -1;

    s->callbacks = *callbacks;
    DEBUG_LOG("%s callbacks2:%p",__FUNCTION__,callbacks);
    return 0;

}

static void gps_cleanup(void)
{
    GpsState*  s = _gps_state;

    if (s->init)
        gps_state_done(s);
}


static int gps_start(void)
{
    GpsState*  s = _gps_state;

    if (!s->init) {
        DEBUG_LOG("%s: called with uninitialized state !!", __FUNCTION__);
        return -1;
    }

    DEBUG_LOG("%s: called", __FUNCTION__);
    // Send Command CMD_START and start sending callbacks to framework
    gps_state_start(s);

    pthread_mutex_init (&bst_gps_thread_mutex, NULL);
    pthread_cond_init (&bst_gps_cond_variable, NULL);

    bst_gps_is_exiting = FALSE;

    return 0;
}

static int gps_stop(void)
{
    GpsState*  s = _gps_state;

    if (!s->init) {
        DEBUG_LOG("%s: called with uninitialized state !!", __FUNCTION__);
        return -1;
    }

    DEBUG_LOG("%s: called", __FUNCTION__);
    gps_state_stop(s);
    return 0;
}

static int gps_inject_time(GpsUtcTime time, int64_t timeReference, int uncertainty)
{
    return 0;
}

/** Injects current location from another location provider
 *  (typically cell ID).
 *  latitude and longitude are measured in degrees
 *  expected accuracy is measured in meters
 */
static int gps_inject_location(double latitude, double longitude, float accuracy)
{
    return 0;
}

static void gps_delete_aiding_data(GpsAidingData flags)
{
}

static int gps_set_position_mode(GpsPositionMode mode,  GpsPositionRecurrence recurrence,
        uint32_t min_interval, uint32_t preferred_accuracy, uint32_t preferred_time)
{
    // TODO - support fix_frequency
    DEBUG_LOG("%s is called GpsPositionRecurrence:%d ,min_interval:%d,preferred_accuracy:%d, preferred_time:%d", __FUNCTION__,recurrence,min_interval,preferred_accuracy,preferred_time);
    return 0;
}

static const void* gps_get_extension(const char* name)
{
    DEBUG_LOG("%s('%s') is called", __FUNCTION__, name);
    return NULL;
}

static const GpsInterface sGpsInterface = {
    sizeof(GpsInterface),
    gps_init,
    gps_start,
    gps_stop,
    gps_cleanup,
    gps_inject_time,
    gps_inject_location,
    gps_delete_aiding_data,
    gps_set_position_mode,
    gps_get_extension,
};

static const GpsInterface* gps_get_hardware_interface(struct gps_device_t* dev)
{
    ALOGV("get_interface was called");
    return &sGpsInterface;
}

static int open_gps(const struct hw_module_t* module, char const* name,
        struct hw_device_t** device)
{
    struct gps_device_t *dev = malloc(sizeof(struct gps_device_t));
    memset(dev, 0, sizeof(*dev));

    dev->common.tag         = HARDWARE_DEVICE_TAG;
    dev->common.version     = 0;
    dev->common.module      = (struct hw_module_t*)module;
    dev->get_gps_interface  = gps_get_hardware_interface;

    *device = (struct hw_device_t*)dev;
    return 0;
}


static struct hw_module_methods_t gps_module_methods =
{
    .open = open_gps
};

struct hw_module_t HAL_MODULE_INFO_SYM =
{
    .tag            = HARDWARE_MODULE_TAG,
    .version_major  = 1,
    .version_minor  = 0,
    .id             = GPS_HARDWARE_MODULE_ID,
    .name           = "BST GPS Module",
    .author         = "BlueStacks System Inc.",
    .methods        = &gps_module_methods,
};
