#define LOG_TAG "bstime"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <linux/vt.h>

#include <arpa/inet.h>
#include <cutils/properties.h>
#include <utils/Log.h>

#define DEBUG 0

int sock_write, dev_read;

//This function establishes the connection with LatinIme.
int connect_to_server() {
    struct sockaddr_in serv_addr;
    char portno[256];
    int conn = 0;
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0)
    {
        ALOGE("%s: Failed creating socket error %d(%s)\n", __func__, errno, strerror(errno));
        return -errno;
    }

    property_get("bst.config.ime_listenerport", portno, "0");
    memset((char *) &serv_addr, 0, sizeof(serv_addr));
    serv_addr.sin_family = AF_INET;
    serv_addr.sin_port = htons(atoi(portno)); //Port to listen on
    serv_addr.sin_addr.s_addr = inet_addr("127.0.0.1");
    conn = connect(fd, (struct sockaddr *) &serv_addr, sizeof(serv_addr));
    if (conn < 0) {
        ALOGE("ERROR in connecting to socket, errno %d(%s)\n", errno, strerror(errno));
        return -errno;
    }
    return fd;
}

void sig_pipe(int signum) {
    ALOGW("sig_pipe handler called for signal (%d)", signum);
    //Try establishing the connection again with LatinIme.
    sock_write = connect_to_server();
    return;
}

void disable_vt_switch() {
    char const * const ttydev = "/dev/tty0";
    int fd = open(ttydev, O_RDWR | O_SYNC);
    if (fd < 0) {
        ALOGE("Can't open %s, errno=%d (%s)", ttydev, errno,strerror(errno));
        return ;
    }
    int res = ioctl(fd, VT_LOCKSWITCH, 0);
    if (res < 0) {
        ALOGE("ioctl(%d, VT_LOCKSWITCH, ...) failed, %d %d (%s) for vt ",
                fd, res, errno,strerror(errno));
    }
    close(fd);
}

int main()
{
    //signal handler for SIGPIPE
    signal(SIGPIPE, sig_pipe);
    disable_vt_switch();
    //Establish a connection with socket in LatinIme.
    //Data will be written to this socket.
    sock_write = connect_to_server();
    if (DEBUG) ALOGD("sock_write value=%d", sock_write);

    if (sock_write == -ECONNREFUSED) {
        usleep(5 * 1000000); // Wait for 5 secs
        sock_write = connect_to_server();
    }

    if (sock_write < 0)
        return -1;

    //Establish connection with bst_ime char input device.
    //Data will be read from this connection.
    dev_read  = open("/dev/bst_ime",0);
    if (dev_read < 0) {
        ALOGE("Failed creating socket error %d (%s)\n", errno, strerror(errno));
        close(sock_write);
        return -1;
    }

    //Keep on reading the data from the input device until ime is changed other than latinIme
    //or the connection is broken due to some reason.
    while (1) {
        char buf[256];
        int result, wbytes, numofretry;
        memset(buf, 0, sizeof(buf));
        result = read(dev_read, buf, sizeof(buf));
        numofretry = 3; //retry sending data for numofretry number of times for error codes which are temporary.
        if (DEBUG) ALOGD("read Result=%d", result);
        if (result > 0) {
            if (DEBUG) ALOGD("DATA received %s\n", buf);
            int len = strlen(buf);
            buf[len] = '\n';

            do {
                wbytes = write(sock_write, buf, strlen(buf));
            } while (wbytes < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK) && numofretry > 0);

            if (wbytes < 0) {
                ALOGW("Cannot write to socket, err %d (%s)\n", errno, strerror(errno));
                //Retry writing data for one last time for all types of errors.
                //Try to establish the connection again
                //and send the data over this new connection.
                sock_write = connect_to_server();
                wbytes = write(sock_write, buf, strlen(buf));
                if (wbytes < 0) {
                    ALOGW("Still unable to write to the socket, err %d (%s)\n", errno, strerror(errno));
                    break;
                }
            }
        } else if (result == 0) {
            ALOGE("Connection closed with input device...\n");
            break;
        } else {
            ALOGE("ERROR in reading from input device: %d (%s)\n", result, strerror(errno));
            if (errno == EINTR) {
                continue;
            } else {
                break;
            }
        }
    }
    //Close all the fds before exiting.
    close(sock_write);
    close(dev_read);
    return 0;
}
