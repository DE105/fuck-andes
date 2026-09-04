#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static volatile sig_atomic_t stop_signal;
static volatile sig_atomic_t window_changed;

static void signal_received(int signal_number) {
    if (signal_number == SIGWINCH) window_changed = 1;
    else if (signal_number != SIGCHLD) stop_signal = signal_number;
}

static int write_all(int descriptor, const void *data, size_t size) {
    const unsigned char *bytes = data;
    while (size && !stop_signal) {
        ssize_t written = write(descriptor, bytes, size);
        if (written < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (!written) return -1;
        bytes += written;
        size -= (size_t)written;
    }
    return size ? -1 : 0;
}

static int dimension(const char *value) {
    char *end;
    errno = 0;
    long parsed = strtol(value, &end, 10);
    if (errno || *end || end == value || parsed < 1 || parsed > 1000) return -1;
    return (int)parsed;
}

static void terminate_group(pid_t child) {
    /* 会话首进程退出后仍清理组内后台作业，避免关闭终端留下执行中的子进程。 */
    kill(-child, SIGTERM);
    kill(child, SIGTERM);
    usleep(100000);
    kill(-child, SIGKILL);
    kill(child, SIGKILL);
}

int main(int argc, char **argv) {
    if (argc < 5 || strcmp(argv[3], "--")) {
        fprintf(stderr, "ETA_PTY_INVALID_ARGUMENT\n");
        return 64;
    }
    int rows = dimension(argv[1]);
    int columns = dimension(argv[2]);
    if (rows < 0 || columns < 0) {
        fprintf(stderr, "ETA_PTY_INVALID_SIZE\n");
        return 64;
    }
    struct sigaction action = {0};
    action.sa_handler = signal_received;
    sigemptyset(&action.sa_mask);
    sigaction(SIGTERM, &action, NULL);
    sigaction(SIGHUP, &action, NULL);
    sigaction(SIGINT, &action, NULL);
    sigaction(SIGCHLD, &action, NULL);
    sigaction(SIGWINCH, &action, NULL);
    signal(SIGPIPE, SIG_IGN);

    /* 父进程可能在注册死亡信号前退出，注册后再核对一次以封闭竞态。 */
    pid_t parent = getppid();
    if (prctl(PR_SET_PDEATHSIG, SIGTERM) < 0 || parent == 1 || getppid() != parent) return 71;
    int master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0 || grantpt(master) < 0 || unlockpt(master) < 0) {
        fprintf(stderr, "ETA_PTY_OPEN_FAILED\n");
        if (master >= 0) close(master);
        return 71;
    }
    char slave_name[256];
    if (ptsname_r(master, slave_name, sizeof(slave_name))) {
        close(master);
        return 71;
    }
    struct winsize size = {.ws_row = (unsigned short)rows, .ws_col = (unsigned short)columns};
    if (ioctl(master, TIOCSWINSZ, &size) < 0) {
        close(master);
        return 71;
    }
    pid_t child = fork();
    if (child < 0) {
        close(master);
        return 71;
    }
    if (!child) {
        pid_t wrapper = getppid();
        signal(SIGTERM, SIG_DFL);
        signal(SIGHUP, SIG_DFL);
        signal(SIGINT, SIG_DFL);
        signal(SIGCHLD, SIG_DFL);
        signal(SIGWINCH, SIG_DFL);
        signal(SIGPIPE, SIG_DFL);
        if (prctl(PR_SET_PDEATHSIG, SIGHUP) < 0 || wrapper == 1 || getppid() != wrapper) _exit(71);
        if (setsid() < 0) _exit(71);
        int slave = open(slave_name, O_RDWR | O_NOCTTY);
        if (slave < 0 || ioctl(slave, TIOCSCTTY, 0) < 0) _exit(71);
        if (dup2(slave, STDIN_FILENO) < 0 || dup2(slave, STDOUT_FILENO) < 0 ||
            dup2(slave, STDERR_FILENO) < 0) _exit(71);
        if (slave > STDERR_FILENO) close(slave);
        close(master);
        execvp(argv[4], &argv[4]);
        fprintf(stderr, "ETA_PTY_EXEC_FAILED errno=%d\n", errno);
        _exit(127);
    }

    int status = 0;
    int reaped = 0;
    int child_exited = 0;
    int input_open = 1;
    unsigned char buffer[8192];
    while (!stop_signal) {
        if (window_changed) {
            window_changed = 0;
            struct winsize current;
            if (!ioctl(STDIN_FILENO, TIOCGWINSZ, &current) && current.ws_row && current.ws_col)
                ioctl(master, TIOCSWINSZ, &current);
        }
        /* 先观察退出、最后再回收，清理进程组期间不会复用会话首进程的 PID。 */
        siginfo_t child_info = {0};
        if (!child_exited && !waitid(P_PID, child, &child_info, WEXITED | WNOHANG | WNOWAIT)
            && child_info.si_pid == child) child_exited = 1;
        struct pollfd descriptors[2] = {
            {.fd = master, .events = POLLIN},
            {.fd = input_open && !child_exited ? STDIN_FILENO : -1, .events = POLLIN}
        };
        int ready = poll(descriptors, 2, child_exited ? 0 : 250);
        if (ready < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (!ready && child_exited) break;
        if (descriptors[0].revents & (POLLIN | POLLHUP | POLLERR)) {
            ssize_t read_size = read(master, buffer, sizeof(buffer));
            if (read_size < 0 && errno == EINTR) continue;
            if (read_size <= 0 || write_all(STDOUT_FILENO, buffer, (size_t)read_size)) break;
        }
        if (descriptors[1].revents & (POLLIN | POLLHUP | POLLERR)) {
            ssize_t read_size = read(STDIN_FILENO, buffer, sizeof(buffer));
            if (read_size < 0 && errno == EINTR) continue;
            if (read_size <= 0) {
                /* 输入管道关闭按终端 EOF 处理，仍读取最后一批输出。 */
                const unsigned char eof = 4;
                write_all(master, &eof, 1);
                input_open = 0;
            } else if (write_all(master, buffer, (size_t)read_size)) break;
        }
    }
    close(master);
    terminate_group(child);
    if (!reaped) {
        for (int attempt = 0; attempt < 25; ++attempt) {
            if (waitpid(child, &status, WNOHANG) == child) {
                reaped = 1;
                break;
            }
            usleep(20000);
        }
    }
    if (stop_signal) return 128 + stop_signal;
    if (!reaped) return 71;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    return WIFSIGNALED(status) ? 128 + WTERMSIG(status) : 71;
}
