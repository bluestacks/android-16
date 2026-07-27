#define LOG_TAG "BstShutdownCore"

#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/wait.h>
#include <cutils/log.h>
#include <cutils/properties.h>
#include <sys/reboot.h>
#include <time.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <string.h>
#include <cutils/klog.h>

extern int syncfs(int __fd);
#define BUFFER_SIZE 128
#define COMPARE_OUTPUT "Starting: Intent { act=com.android.internal.intent.action.REQUEST_SHUTDOWN (has extras) }"

/*
   try to shutdown the VM cleanly by syncing the FS contents before plugging off.
*/
int wait_for_child_process(pid_t childID)
{
    int i = 0, status = 0;
    pid_t endID = -1;

    /* Wait for 5 seconds for child process to terminate.           */
    for(i = 1; i < 6; i++) {
        endID = waitpid(childID, &status, WNOHANG|WUNTRACED);
        if (endID == -1) {            /* error calling waitpid       */
            KLOG_INFO(LOG_TAG, "waitpid error %d: %s\n", errno, strerror(errno));
            return -1;
        }
        else if (endID == 0) {        /* child still running , sleep for 1 sec        */
            KLOG_INFO(LOG_TAG, "(%d) Child %d still running ....\n", i, childID);
            sleep(1);
        }
        else if (endID == childID) {  /* child ended                 */
            if (WIFEXITED(status))
                KLOG_INFO(LOG_TAG, "Child %d ended normally\n", childID);
            else if (WIFSIGNALED(status))
                KLOG_INFO(LOG_TAG, "Child %d ended because of an uncaught signal.\n", childID);
            else if (WIFSTOPPED(status))
                KLOG_INFO(LOG_TAG, "Child %d process has stopped.\n", childID);
            return 0;
        }
    }

    return -1;
}

int execute_cmd(char *command)
{
    FILE *fpipe;
    char line[BUFFER_SIZE];
    int ret_value = -1;

    if ( !(fpipe = (FILE*)popen(command, "r"))) {
        perror("problem with opening pipe");
        return errno;
    }

    while ( fgets (line, sizeof(char) * BUFFER_SIZE, fpipe)) {
        //printf("%s", line);
    }

    int status = pclose(fpipe);

    // For debugging, if commands fails, logs for comparing output
    //printf("compare to : %s",line);
    //printf("compare with : %s",COMPARE_OUTPUT);

    if (status != 0) {
        ret_value = -1;
    }

    if (!strncmp(COMPARE_OUTPUT, line, strlen(COMPARE_OUTPUT)))
        ret_value = 0;

    return ret_value;
}

int shutdown_from_framework()
{
    int fd = -1;
    int ret = -1;

    if (property_set("ro.build.shutdown_timeout", "4") <  0) {
        KLOG_ERROR(LOG_TAG, "Not able to set shutdown timeout property\n");
    } else {
        KLOG_INFO(LOG_TAG, "Successfully set the shutdown timeout property.\n");
    }

    fd = open ("/data/done.txt", O_RDONLY);
    if (fd < 0 || syncfs(fd)) {
        KLOG_WARNING(LOG_TAG, "error in syncing data fs, errno %d (%s)\n", errno , strerror(errno));
        fflush(stdout);
    }

    KLOG_INFO(LOG_TAG , "pid = %d, sync command completed successfully for dataFS \n", getpid());

    fd = open ("/sdcard/.bstshutdown_sync", O_RDWR | O_CREAT | O_APPEND, 0660);
    if (fd < 0 || syncfs(fd)) {
        KLOG_WARNING(LOG_TAG, "error in syncing sdcard fs, errno %d (%s)\n", errno , strerror(errno));
        fflush(stdout);
    }

    KLOG_INFO(LOG_TAG, "pid = %d, sync command completed successfully for sdcardfs \n", getpid());

    ret = execute_cmd("/system/bin/am start -a com.android.internal.intent.action.REQUEST_SHUTDOWN --ez android.intent.extra.USER_REQUESTED_SHUTDOWN true");
    if (ret != 0) {
        KLOG_ERROR(LOG_TAG, "Not able to call am shutdown ret is %d\n",ret);
        fflush(stdout);
    }

    return ret;
}

void low_level_shutdown()
{
    //int ishypervenabled;
    pid_t pid = -1;
    int fd = -1;
    //char hypervenabled[PROPERTY_VALUE_MAX];
    
    KLOG_INFO(LOG_TAG, "pid = %d, executing bstshutdown_core binary\n", getpid());
    pid = fork();
    if (pid == 0)
    {
        /* Child Process */
        KLOG_INFO(LOG_TAG, "pid = %d, executing stop command\n", getpid());
        execl("/system/bin/stop", "stop", (char *)0);
        KLOG_INFO(LOG_TAG, "pid = %d, not able to execute stop command\n", getpid());
        fflush(stdout);
        _exit (EXIT_FAILURE); //This statement should never execute
    }
    else if (pid > 0)
    {
        // First wait for stop command to complete
        KLOG_INFO(LOG_TAG, "pid = %d, waiting for stop command to complete\n", getpid());
        if (wait_for_child_process(pid) != 0) {
            KLOG_INFO(LOG_TAG, "error in executing stop command, child process still running\n");
        }
        KLOG_INFO(LOG_TAG, "pid = %d, executing syncfs command now \n", getpid());
        fd = open ("/data/.bstshutdown_sync", O_RDWR | O_CREAT | O_APPEND, 0660);
        if (fd < 0 || syncfs(fd)) {
            if (fd > 0) {
                close(fd);
            }
            KLOG_WARNING(LOG_TAG, "error in syncing data fs, errno %d (%s)\n", errno , strerror(errno));
            fflush(stdout);
        }

        KLOG_INFO(LOG_TAG, "pid = %d, sync command completed successfully for dataFS, executing it for sdcard now \n", getpid());

        fd = open ("/sdcard/.bstshutdown_sync", O_RDWR | O_CREAT | O_APPEND, 0660);
        if (fd < 0 || syncfs(fd)) {
            if (fd > 0) {
                close(fd);
            }
            KLOG_WARNING(LOG_TAG, "error in syncing sdcard fs, errno %d (%s)\n", errno , strerror(errno));
            fflush(stdout);
        }

        KLOG_INFO(LOG_TAG, "pid = %d, sync command completed successfully for sdcardfs \n", getpid());
    }
    else
    {
        KLOG_INFO(LOG_TAG, "pid = %d, error in fork() syscall, errno = %d : %s\n", getpid(), errno, strerror(errno));
        fflush(stdout);
    }

    /*
    property_get("bst.config.hypervenabled", hypervenabled, "0");
    ishypervenabled = atoi(hypervenabled);

    ALOGD("ishypervenabled = %d", ishypervenabled);
    if (ishypervenabled > 0) {
        ALOGD("Executing poweroff command");
        sync();
        reboot(LINUX_REBOOT_CMD_POWER_OFF);
        ALOGI("Not able to execute poweroff command");
        fflush(stdout);
        _exit(EXIT_FAILURE); //This statement should never execute
    }
    */

    // Do a low level shutdown call via android init.
    if (property_set("sys.powerctl", "shutdown") <  0) {
        KLOG_ERROR(LOG_TAG, "Not able to set shutdown property correctly, exiting now\n");
        //return reboot(RB_POWER_OFF);
    } else {
        KLOG_INFO(LOG_TAG, "Successfully set the shutdown property.\n");
    }
}

int main(int argc, const char* argv[])
{
    klog_set_level(7);
    KLOG_INFO(LOG_TAG, "pid = %d, executing bstshutdown_core binary\n", getpid());

    // call shutdown from android framework, with ACTION_REQUEST_SHUTDOWN via am call.
    int ret = shutdown_from_framework();

    // if am calls fails, perform a low level shutdown call, by executing stop and asking android init to shutdown
    if (ret != 0) {
        KLOG_ERROR(LOG_TAG, "shutdown_from_framework failed, executing low_level_shutdown \n");
        // error case, when we are not able to ask framework to shutdown properly, do a low level shutdown call.
        low_level_shutdown();
    }

    KLOG_INFO(LOG_TAG, "exiting from bstshutdown_core \n");
    return 0;
}
