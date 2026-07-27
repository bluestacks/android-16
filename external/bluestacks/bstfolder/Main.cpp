#define LOG_TAG "bstfolder"

#include <cstring>
#include <errno.h>
#include <stdlib.h>

#include <cutils/log.h>

#include <sys/mount.h>
#include <sys/socket.h>
#include <linux/vm_sockets.h>

#define MOUNT_PATH_MAX		1024

#define MOUNT_UID		1000
#define MOUNT_GID		1015
#define MOUNT_FMASK		0002
#define MOUNT_DMASK		0002

#define MOUNT_TYPE_PLAN9 "9p"

#define DEBUG 0
#define PLAN9_PORT	(50000)

int mountVolume(char* name, char* absolute_path, int is_read_only)
{
    char path[MOUNT_PATH_MAX];
    char msg[256];
    int flags;
    int error;
    int res;

    if (DEBUG) SLOGD("absolute path %s is_read_only = %d", absolute_path, is_read_only);
    snprintf(path, sizeof(path), "%s", absolute_path);

    if (is_read_only)
        flags = MS_NODEV | MS_NOSUID | MS_RDONLY;
    else
        flags = MS_NODEV | MS_NOSUID;

    SLOGI("Mounting volume %s on %s", name, path);

    int fd = socket(AF_VSOCK, SOCK_STREAM, 0);
    if (fd == -1) {
        SLOGE("Error creating VSOCK socket, error %s", strerror(errno));
        return errno;
    }

    struct sockaddr_vm addr = {};

    addr.svm_family = AF_VSOCK;
    addr.svm_port = PLAN9_PORT;
    addr.svm_cid = VMADDR_CID_HOST;

    error = connect(fd, (struct sockaddr *)&addr, sizeof(addr));

    if (error == -1) {
        SLOGE("Error (%s) connecting to host over VSOCK!", strerror(errno));
        return errno;
    }

    char data[1024];
    snprintf(data, sizeof(data), "trans=fd,rfdno=%d,wfdno=%d,dfltuid=%d,dfltgid=%d,cache=none,aname=%s;uid=%d;gid=%d",
                fd, fd, MOUNT_UID, MOUNT_GID, name, MOUNT_UID, MOUNT_GID);

    res = mount(name, path, MOUNT_TYPE_PLAN9, flags, data);
    if (res == -1 && errno == EACCES) {
        SLOGE("Failed to mount %s (EACCES), retrying in read only mode", name);
        res = mount(name, path, MOUNT_TYPE_PLAN9, flags | MS_RDONLY, data);
    }

    if (res == -1) {
        snprintf(msg, sizeof(msg), "Cannot mount %s on %s: %s (%d)",
                name, path, strerror(errno), errno);
        SLOGE("MOUNT COMMAND_FAILED: %s", msg);
        return res;
    }

    SLOGI("COMMAND_SUCCESS: volume %s mounted", name);
    return 0;
}

int main(int argc, char **argv)
{
    strcpy(argv[0],"nbinary");
    if (argc < 4) {
        SLOGE("Error: Passed arguments are not correct.");
        return -1;
    }

    return mountVolume(argv[1], argv[2], atoi(argv[3]));
}
