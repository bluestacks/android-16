#define LOG_TAG "BstSyncFS"

#include <fcntl.h>
#include <linux/ioctl.h>
#include <sys/types.h>
#include <errno.h>
#include <cutils/log.h>
#include <cutils/properties.h>

// Make sure that this value is in sync with the value in bstblock.c file
#define BSTBLOCK_IOCTL_SYNC 0


int main(int argc, char **argv)
{
    int stat = 0;
    char *blockfile = NULL;

    if (argc != 2) {
        ALOGE("Invalid number of arguments %d", argc);
        ALOGE("Usage: %s <block_device_path>", argv[0]);
        return -1;
    }

    blockfile = argv[1];

    int fd = open(blockfile, O_WRONLY);

    if (fd < 0) {
        ALOGE("Error in opening block file %s", blockfile);
        return -1;
    }

    ALOGI("Sending SYNC IOCTL command for block device %s", blockfile);

    if (ioctl(fd, BSTBLOCK_IOCTL_SYNC, &stat) == 0)
        ALOGI("SYNC IOCTL command sent successfully for block device %s", blockfile);
    else
        ALOGE("ERROR in sending SYNC IOCTL command for block device %s, error = %d", blockfile, errno);

    return 0;
}
