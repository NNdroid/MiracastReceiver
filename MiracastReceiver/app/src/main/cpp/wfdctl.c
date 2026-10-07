/*
 * wfdctl —— wpa_supplicant control-interface client for injecting Wi-Fi Display sink state.
 *
 * Normal applications cannot call WifiP2pManager.setWFDInfo() on modern Android because it
 * requires signature-level CONFIGURE_WIFI_DISPLAY. On rooted devices this small executable talks
 * directly to the supplicant control socket.
 *
 * Exit codes are a contract with the caller — "sent" must never be read as "configured":
 *   0  every command was acknowledged by wpa_supplicant
 *   1  at least one command was explicitly rejected (unsupported, FAIL, parameter error)
 *   2  nothing was rejected, but at least one command got no reply — usually SELinux refusing the
 *      response packet, so the command reached the supplicant but confirmation never came back
 *   3  usage error
 */
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#define WIFI_UID 1010 /* AID_WIFI */
#define REPLY_TIMEOUT_USEC 300000

#define CTRL_OK        0
#define CTRL_REJECTED  1
#define CTRL_NO_REPLY  2

static int ctrl_fd = -1;
static char local_path[108];

static int ctrl_open(const char *server_path)
{
    struct sockaddr_un local, dest;
    char dir[108];
    char *dir_end;

    ctrl_fd = socket(AF_UNIX, SOCK_DGRAM, 0);
    if (ctrl_fd < 0) {
        fprintf(stderr, "socket() failed: %s\n", strerror(errno));
        return -1;
    }

    snprintf(dir, sizeof(dir), "%s", server_path);
    dir_end = strrchr(dir, '/');
    if (dir_end)
        *dir_end = '\0';
    snprintf(local_path, sizeof(local_path), "%s/wfdctl_%d", dir, (int) getpid());

    memset(&local, 0, sizeof(local));
    local.sun_family = AF_UNIX;
    snprintf(local.sun_path, sizeof(local.sun_path), "%s", local_path);
    unlink(local_path);
    if (bind(ctrl_fd, (struct sockaddr *) &local, sizeof(local)) < 0) {
        fprintf(stderr, "bind(%s) failed: %s\n", local_path, strerror(errno));
        close(ctrl_fd);
        ctrl_fd = -1;
        return -1;
    }
    if (chown(local_path, WIFI_UID, WIFI_UID) < 0)
        fprintf(stderr, "warn: chown failed: %s\n", strerror(errno));
    if (chmod(local_path, 0770) < 0)
        fprintf(stderr, "warn: chmod failed: %s\n", strerror(errno));

    memset(&dest, 0, sizeof(dest));
    dest.sun_family = AF_UNIX;
    snprintf(dest.sun_path, sizeof(dest.sun_path), "%s", server_path);
    if (connect(ctrl_fd, (struct sockaddr *) &dest, sizeof(dest)) < 0) {
        fprintf(stderr, "connect(%s) failed: %s\n", server_path, strerror(errno));
        close(ctrl_fd);
        ctrl_fd = -1;
        unlink(local_path);
        return -1;
    }
    return 0;
}

/*
 * Every way wpa_supplicant says "no". A rejection that is not detected here becomes a silent
 * no-op that the caller reports as success, which is exactly how an app ends up claiming "WFD
 * sink advertised" while the Source still cannot see it.
 *
 * Notable ones:
 *   FAIL                 - generic refusal
 *   UNKNOWN COMMAND      - feature not compiled in, or sent on the wrong interface type
 *   UNKNOWN              - abbreviated form of the above
 *   ERROR                - used by some vendor builds instead of FAIL
 *   WPA_NOT_IMPLEMENTED  - the command is outside the STA control interface
 *   "<CMD> failed: -2"   - p2p_ctrl_wfd reports wpa_supplicant_wfd_elem_set() failure this way,
 *                          which starts with the command name, not with FAIL
 */
static int is_rejected_reply(const char *p)
{
    if (strncmp(p, "FAIL", 4) == 0 ||
        strncmp(p, "UNKNOWN COMMAND", 15) == 0 ||
        strncmp(p, "UNKNOWN", 7) == 0 ||
        strncmp(p, "ERROR", 5) == 0 ||
        strncmp(p, "WPA_NOT_IMPLEMENTED", 19) == 0)
        return 1;
    if (strstr(p, "failed") != NULL)
        return 1;
    return 0;
}

/*
 * Send one command and briefly wait for a reply. Some SELinux policies allow the command to reach
 * wpa_supplicant but prevent the response from reaching the su-domain client. That is reported as
 * CTRL_NO_REPLY rather than CTRL_OK: the state is unconfirmed, and pretending otherwise is what
 * makes a misconfigured sink look healthy in the logs. An explicit rejection is always a failure.
 */
static int ctrl_request(const char *cmd)
{
    char reply[4096];
    struct timeval tv;
    ssize_t n;

    if (send(ctrl_fd, cmd, strlen(cmd), 0) < 0) {
        fprintf(stderr, "send(%s) failed: %s\n", cmd, strerror(errno));
        return CTRL_REJECTED;
    }

    tv.tv_sec = 0;
    tv.tv_usec = REPLY_TIMEOUT_USEC;
    setsockopt(ctrl_fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    n = recv(ctrl_fd, reply, sizeof(reply) - 1, 0);
    if (n > 0) {
        char *p;
        reply[n] = '\0';
        printf("%s -> %s\n", cmd, reply);

        p = reply;
        while (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n')
            p++;
        return is_rejected_reply(p) ? CTRL_REJECTED : CTRL_OK;
    }

    printf("%s -> (sent, no reply)\n", cmd);
    return CTRL_NO_REPLY;
}

int main(int argc, char *argv[])
{
    int i, rc = CTRL_OK;

    if (argc < 3) {
        fprintf(stderr, "usage: %s <ctrl_socket> <command> [command...]\n", argv[0]);
        return 3;
    }

    if (ctrl_open(argv[1]) < 0) {
        if (local_path[0])
            unlink(local_path);
        return 1;
    }

    for (i = 2; i < argc; i++) {
        int res = ctrl_request(argv[i]);
        if (res == CTRL_REJECTED)
            rc = CTRL_REJECTED;
        else if (res == CTRL_NO_REPLY && rc == CTRL_OK)
            rc = CTRL_NO_REPLY; /* "sent, unconfirmed" downgrades an otherwise clean run */
    }

    printf("wfdctl: verdict=%s\n",
           rc == CTRL_OK ? "ok" : rc == CTRL_REJECTED ? "rejected" : "unconfirmed");

    if (ctrl_fd >= 0)
        close(ctrl_fd);
    if (local_path[0])
        unlink(local_path);
    return rc;
}
