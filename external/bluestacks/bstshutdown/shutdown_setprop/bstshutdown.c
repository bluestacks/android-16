#define LOG_TAG "BstShutdown"

#include <cutils/properties.h>
#include <cutils/log.h>
#include <sys/reboot.h>
#include <cutils/log.h>
#include <cutils/klog.h>

int main(void)
{
    klog_set_level(7);
    KLOG_INFO (LOG_TAG, "Trying to set property for performing the graceful shutdown\n");
    
    if (property_set("bst.config.start_shutdown", "1") <  0) {
        KLOG_ERROR(LOG_TAG, "Not able to set shutdown property correctly, exiting now\n");
        //return reboot(RB_POWER_OFF);
    } else {
        KLOG_INFO (LOG_TAG, "Successfully set the shutdown property.\n");
    }
    return 0;
}
