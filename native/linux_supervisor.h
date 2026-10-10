#ifndef LOADONCE_LINUX_SUPERVISOR_H
#define LOADONCE_LINUX_SUPERVISOR_H

#include <fcntl.h>
#include <dirent.h>
#include <poll.h>
#include <signal.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>

extern char **environ;

#define LO_LINUX_COMPLETE UINT32_C(0x4c4f4431)
#define LO_LINUX_WORKER UINT32_C(0x4c4f5031)
#define LO_LINUX_BOOT_MILLIS INT64_C(60000)
#define LO_LINUX_GRACE_MILLIS INT64_C(5000)

static volatile sig_atomic_t lo_linux_control = -1;

static void lo_linux_signal(int signal_number) {
    int saved_errno = errno;
    unsigned char byte = (unsigned char) signal_number;
    if (lo_linux_control >= 0) {
        ssize_t written;
        do { written = write((int) lo_linux_control, &byte, 1); } while (written < 0 && errno == EINTR);
        /* A full nonblocking pipe already has cancellation queued; a closed
         * pipe means the guardian's exit will be observed by the frontend. */
        (void) written;
    }
    errno = saved_errno;
}

static int64_t lo_linux_millis(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return -1;
    return (int64_t) now.tv_sec * INT64_C(1000) + now.tv_nsec / 1000000;
}

static int lo_linux_flags(int fd, int command, int flag) {
    int current = fcntl(fd, command);
    int setter = command == F_GETFD ? F_SETFD : F_SETFL;
    return current < 0 ? -1 : fcntl(fd, setter, current | flag);
}

/* Do not turn a caller's closed standard stream into a metadata descriptor. */
static int lo_linux_pipe(int descriptors[2]) {
    int index;
    if (pipe(descriptors) != 0) return -1;
    for (index = 0; index < 2; index++) {
        if (descriptors[index] < 3) {
            int moved = fcntl(descriptors[index], F_DUPFD, 3);
            if (moved < 0) break;
            close(descriptors[index]);
            descriptors[index] = moved;
        }
        if (lo_linux_flags(descriptors[index], F_GETFD, FD_CLOEXEC) != 0) break;
    }
    if (index == 2) return 0;
    close(descriptors[0]);
    close(descriptors[1]);
    return -1;
}

static int lo_linux_disposition(int signal_number, void (*handler)(int)) {
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_handler = handler;
    if (sigemptyset(&action.sa_mask) != 0) return -1;
    return sigaction(signal_number, &action, NULL);
}

static int lo_linux_record(int fd, uint32_t magic, uint32_t number) {
    unsigned char record[8];
    size_t index;
    ssize_t written;
    for (index = 0; index < 4; index++) {
        record[index] = (unsigned char) (magic >> (24U - (unsigned int) index * 8U));
        record[index + 4] = (unsigned char) (number >> (24U - (unsigned int) index * 8U));
    }
    do { written = write(fd, record, sizeof(record)); } while (written < 0 && errno == EINTR);
    return written == (ssize_t) sizeof(record) ? 0 : -1;
}

/* Unreaped direct children retain their PID identities while we signal them.
 * Subreaper adoption brings escaped sessions here when their parents die.
 * /proc task/children depends on CONFIG_CHECKPOINT_RESTORE, so use PPID from
 * stat instead; restoration itself must not be required for tree cleanup. */
static int lo_linux_sweep(pid_t protected_child, int signal_number) {
    DIR *processes = opendir("/proc");
    struct dirent *entry;
    pid_t owner = getpid();
    int remaining = 0;
    if (processes == NULL) return -1;
    while ((entry = readdir(processes)) != NULL) {
        char path[80];
        char *line = NULL;
        char *end;
        size_t capacity = 0;
        long number;
        long parent;
        char state;
        FILE *stat_file;
        pid_t child;
        int status;
        pid_t waited;
        if (entry->d_name[0] < '0' || entry->d_name[0] > '9') continue;
        errno = 0;
        number = strtol(entry->d_name, &end, 10);
        if (errno != 0 || *end != 0) continue;
        if (number <= 1 || number > INT_MAX) continue;
        child = (pid_t) number;
        if (child == owner) continue;
        if (snprintf(path, sizeof(path), "/proc/%ld/stat", number) < 0) continue;
        stat_file = fopen(path, "r");
        if (stat_file == NULL) continue;
        if (getdelim(&line, &capacity, '\0', stat_file) < 0) { fclose(stat_file); free(line); continue; }
        fclose(stat_file);
        /* comm may contain spaces, newlines and ')' characters. Its final ')' precedes
         * the fixed state/PPID fields; never split the entire line on spaces. */
        end = strrchr(line, ')');
        if (end == NULL || sscanf(end + 1, " %c %ld", &state, &parent) != 2 || parent != (long) owner) {
            free(line);
            continue;
        }
        free(line);
        /* Count children seen even after reaping them: a killed parent's
         * descendants may be adopted behind this pass's directory cursor. */
        remaining++;
        if (child == protected_child) continue;
        if (signal_number != 0) {
            if (getpgid(child) == child) (void) kill(-child, signal_number);
            (void) kill(child, signal_number);
        }
        do { waited = waitpid(child, &status, WNOHANG); } while (waited < 0 && errno == EINTR);
        if (waited < 0 && errno != ECHILD) { closedir(processes); return -1; }
    }
    closedir(processes);
    return remaining;
}

static void lo_linux_cleanup(pid_t worker) {
    int64_t deadline = lo_linux_millis() + LO_LINUX_GRACE_MILLIS;
    int wrapper_reaped = worker <= 1;
    if (worker > 1) {
        /* The unreaped worker reserves this group ID until the initial kill. */
        (void) kill(-worker, SIGKILL);
        (void) kill(worker, SIGKILL);
    }
    for (;;) {
        int remaining = lo_linux_sweep(wrapper_reaped ? 0 : worker, SIGKILL);
        if (!wrapper_reaped) {
            int status;
            pid_t waited;
            do { waited = waitpid(worker, &status, WNOHANG); } while (waited < 0 && errno == EINTR);
            if (waited == worker || (waited < 0 && errno == ECHILD)) wrapper_reaped = 1;
        }
        if (remaining == 0 && wrapper_reaped) return;
        if (remaining < 0 || lo_linux_millis() >= deadline) {
            fprintf(stderr, "LoadOnce runtime: child cleanup could not finish\n");
            return;
        }
        (void) poll(NULL, 0, 10);
    }
}

static char **lo_linux_boot_environment(void) {
    static const char *const excluded[] = {
        "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "JDK_AOT_VM_OPTIONS",
        "LD_LIBRARY_PATH", "LD_PRELOAD", "DYLD_LIBRARY_PATH", "DYLD_INSERT_LIBRARIES"
    };
    size_t count = 0;
    size_t selected = 0;
    size_t index;
    char **result;
    while (environ[count] != NULL) count++;
    if (count > SIZE_MAX / sizeof(*result) - 1) fail("bootstrap environment is too large");
    result = allocate((count + 1) * sizeof(*result));
    for (index = 0; index < count; index++) {
        size_t excluded_index;
        int keep = 1;
        for (excluded_index = 0; excluded_index < sizeof(excluded) / sizeof(excluded[0]); excluded_index++) {
            size_t length = strlen(excluded[excluded_index]);
            if (strncmp(environ[index], excluded[excluded_index], length) == 0 && environ[index][length] == '=') {
                keep = 0;
                break;
            }
        }
        if (keep) result[selected++] = environ[index];
    }
    result[selected] = NULL;
    return result;
}

static void lo_linux_exec_wrapper(const struct configuration *config, const char *policy_path,
        int input, int completion, int ready, int gate, pid_t guardian, char **environment) {
    char input_number[32];
    char completion_number_text[32];
    char *agent_option;
    char *arguments[17];
    unsigned char byte = 1;
    ssize_t got;
    if (lo_linux_disposition(SIGTERM, SIG_DFL) != 0 || lo_linux_disposition(SIGINT, SIG_DFL) != 0
            || lo_linux_disposition(SIGHUP, SIG_DFL) != 0 || lo_linux_disposition(SIGPIPE, SIG_DFL) != 0
            || setsid() < 0 || prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() != guardian) _exit(1);
    if (write(ready, &byte, 1) != 1) _exit(1);
    close(ready);
    do { got = read(gate, &byte, 1); } while (got < 0 && errno == EINTR);
    close(gate);
    if (got != 1 || byte != 1 || getppid() != guardian) _exit(1);
    if (fcntl(input, F_SETFD, 0) != 0 || fcntl(completion, F_SETFD, 0) != 0) _exit(1);
    (void) snprintf(input_number, sizeof(input_number), "%d", input);
    (void) snprintf(completion_number_text, sizeof(completion_number_text), "%d", completion);
    agent_option = allocate(strlen(config->agent) + sizeof("-javaagent:"));
    strcpy(agent_option, "-javaagent:");
    strcat(agent_option, config->agent);
    arguments[0] = config->bootstrap_java;
    arguments[1] = "-Xms16m";
    arguments[2] = "-Xmx128m";
    arguments[3] = "-Djdk.lang.Process.allowAmbiguousCommands=true";
    arguments[4] = "--add-opens=java.base/java.io=ALL-UNNAMED";
    arguments[5] = "--disable-@files";
    arguments[6] = agent_option;
    arguments[7] = "-cp";
    arguments[8] = config->supervisor;
    arguments[9] = "com.qb20nh.loadonce.supervisor.SupervisorMain";
    arguments[10] = "--input-descriptor";
    arguments[11] = input_number;
    arguments[12] = "--completion-descriptor";
    arguments[13] = completion_number_text;
    arguments[14] = "--policy";
    arguments[15] = (char *) policy_path;
    arguments[16] = NULL;
    execve(config->bootstrap_java, arguments, environment);
    fprintf(stderr, "LoadOnce runtime: could not execute supervisor bootstrap\n");
    _exit(1);
}

/* Read the frontend's cancellation channel without ever inheriting its writer. */
static int lo_linux_control_event(int control, int *forwarded, pid_t worker) {
    unsigned char bytes[64];
    ssize_t count;
    do { count = read(control, bytes, sizeof(bytes)); } while (count < 0 && errno == EINTR);
    if (count == 0) return -1;
    if (count < 0) return errno == EAGAIN || errno == EWOULDBLOCK ? 0 : -1;
    if (!*forwarded) {
        size_t index;
        for (index = 0; index < (size_t) count; index++) {
            if (bytes[index] == SIGTERM || bytes[index] == SIGINT || bytes[index] == SIGHUP) {
                *forwarded = bytes[index];
                if (worker > 1) (void) kill(-worker, *forwarded);
                break;
            }
        }
    }
    return 0;
}

static void lo_linux_guardian(const struct configuration *config, const char *policy_path,
        const unsigned char *payload, size_t payload_size, int control, int result, char **environment) {
    int input[2] = {-1, -1};
    int completion[2] = {-1, -1};
    int ready[2] = {-1, -1};
    int gate[2] = {-1, -1};
    pid_t worker = -1;
    pid_t guardian = getpid();
    size_t sent = 0;
    unsigned char answer[9];
    size_t answer_size = 0;
    int answer_eof = 0;
    int forwarded = 0;
    int ordinary_exit = 0;
    int status = 0;
    int failed = 0;
    int64_t deadline = lo_linux_millis() + LO_LINUX_BOOT_MILLIS;
    int64_t cancellation_deadline = 0;
    int64_t completion_deadline = 0;
    DIR *processes;
    if (setsid() < 0 || prctl(PR_SET_CHILD_SUBREAPER, 1) != 0
            || lo_linux_disposition(SIGTERM, SIG_DFL) != 0 || lo_linux_disposition(SIGINT, SIG_DFL) != 0
            || lo_linux_disposition(SIGHUP, SIG_DFL) != 0 || lo_linux_disposition(SIGPIPE, SIG_IGN) != 0
            || lo_linux_flags(control, F_GETFL, O_NONBLOCK) != 0) goto cleanup;
    processes = opendir("/proc");
    if (processes == NULL) goto cleanup;
    closedir(processes);
    if (lo_linux_pipe(input) != 0 || lo_linux_pipe(completion) != 0
            || lo_linux_pipe(ready) != 0 || lo_linux_pipe(gate) != 0) goto cleanup;
    worker = fork();
    if (worker < 0) goto cleanup;
    if (worker == 0) {
        close(control);
        close(result);
        close(input[1]);
        close(completion[0]);
        close(ready[0]);
        close(gate[1]);
        lo_linux_exec_wrapper(config, policy_path, input[0], completion[1], ready[1], gate[0], guardian, environment);
    }
    close(input[0]); input[0] = -1;
    close(completion[1]); completion[1] = -1;
    close(ready[1]); ready[1] = -1;
    close(gate[0]); gate[0] = -1;
    /* Keep the child behind its barrier until its own session is established. */
    for (;;) {
        struct pollfd events[2] = {{control, POLLIN, 0}, {ready[0], POLLIN, 0}};
        unsigned char byte;
        int polled = poll(events, 2, 50);
        if (polled < 0 && errno != EINTR) goto cleanup;
        if (events[0].revents != 0 && (lo_linux_control_event(control, &forwarded, -1) != 0 || forwarded != 0)) goto cleanup;
        if (events[1].revents != 0) {
            ssize_t count = read(ready[0], &byte, 1);
            if (count != 1 || byte != 1 || getpgid(worker) != worker) goto cleanup;
            break;
        }
        if (lo_linux_millis() >= deadline) goto cleanup;
    }
    close(ready[0]); ready[0] = -1;
    if (lo_linux_record(result, LO_LINUX_WORKER, (uint32_t) worker) != 0) goto cleanup;
    {
        unsigned char byte = 1;
        if (write(gate[1], &byte, 1) != 1) goto cleanup;
    }
    close(gate[1]); gate[1] = -1;
    if (lo_linux_flags(input[1], F_GETFL, O_NONBLOCK) != 0
            || lo_linux_flags(completion[0], F_GETFL, O_NONBLOCK) != 0) goto cleanup;
    /* Only the wrapper owns application streams. Native monitors must not keep
     * launcher pipes open after the application and its helpers close them. */
    close(STDIN_FILENO);
    close(STDOUT_FILENO);
    for (;;) {
        struct pollfd events[3];
        siginfo_t observed;
        int polled;
        int64_t now = lo_linux_millis();
        if (now < 0) goto cleanup;
        events[0].fd = control; events[0].events = POLLIN; events[0].revents = 0;
        events[1].fd = input[1]; events[1].events = POLLOUT; events[1].revents = 0;
        events[2].fd = completion[0]; events[2].events = POLLIN; events[2].revents = 0;
        polled = poll(events, 3, 50);
        if (polled < 0 && errno != EINTR) goto cleanup;
        if (events[0].revents != 0) {
            if (lo_linux_control_event(control, &forwarded, worker) != 0) goto cleanup;
            if (forwarded != 0 && cancellation_deadline == 0) cancellation_deadline = now + LO_LINUX_GRACE_MILLIS;
        }
        if (input[1] >= 0 && events[1].revents != 0) {
            ssize_t written = write(input[1], payload + sent, payload_size - sent);
            if (written > 0) sent += (size_t) written;
            else if (written < 0 && errno != EINTR && errno != EAGAIN && errno != EWOULDBLOCK) goto cleanup;
            if (sent == payload_size) { close(input[1]); input[1] = -1; }
        }
        if (completion[0] >= 0 && events[2].revents != 0) {
            ssize_t count = read(completion[0], answer + answer_size, sizeof(answer) - answer_size);
            if (count > 0) {
                answer_size += (size_t) count;
                if (answer_size == sizeof(answer)) goto cleanup;
            } else if (count == 0) {
                answer_eof = 1;
                close(completion[0]); completion[0] = -1;
            } else if (errno != EINTR && errno != EAGAIN && errno != EWOULDBLOCK) goto cleanup;
        }
        memset(&observed, 0, sizeof(observed));
        if (waitid(P_PID, (id_t) worker, &observed, WEXITED | WNOHANG | WNOWAIT) != 0) goto cleanup;
        if (observed.si_pid == worker) {
            ordinary_exit = observed.si_code == CLD_EXITED;
            status = observed.si_status;
            if (completion_deadline == 0) completion_deadline = now + LO_LINUX_GRACE_MILLIS;
            if (!ordinary_exit || forwarded != 0 || sent != payload_size) goto cleanup;
            if (answer_eof) {
                uint32_t exit_number;
                pid_t waited;
                if (answer_size != 8 || completion_number(answer) != LO_LINUX_COMPLETE) goto cleanup;
                exit_number = completion_number(answer + 4);
                if ((exit_number & UINT32_C(255)) != (uint32_t) status) goto cleanup;
                /* A cancellation already queued at the boundary must win over
                 * a valid acknowledgement. The reader is nonblocking. */
                if (lo_linux_control_event(control, &forwarded, worker) != 0 || forwarded != 0) goto cleanup;
                /* Retain the worker's identity if delivering the final record
                 * fails because its frontend died at the release boundary. */
                if (lo_linux_record(result, LO_LINUX_COMPLETE, exit_number) != 0) goto cleanup;
                do { waited = waitpid(worker, &status, 0); } while (waited < 0 && errno == EINTR);
                if (waited != worker) goto cleanup;
                close(control);
                close(result);
                _exit(0);
            }
        }
        if ((input[1] >= 0 && now >= deadline) || (cancellation_deadline != 0 && now >= cancellation_deadline)
                || (completion_deadline != 0 && now >= completion_deadline)) goto cleanup;
        if (lo_linux_sweep(worker, 0) < 0) goto cleanup;
    }

cleanup:
    failed = 1;
    if (input[0] >= 0) close(input[0]);
    if (input[1] >= 0) close(input[1]);
    if (completion[0] >= 0) close(completion[0]);
    if (completion[1] >= 0) close(completion[1]);
    if (ready[0] >= 0) close(ready[0]);
    if (ready[1] >= 0) close(ready[1]);
    if (gate[0] >= 0) close(gate[0]);
    if (gate[1] >= 0) close(gate[1]);
    lo_linux_cleanup(worker);
    close(control);
    close(result);
    _exit(failed);
}

static int supervise_linux(const struct configuration *config, const char *policy_path,
        const unsigned char *payload, size_t payload_size) {
    int control[2];
    int result[2];
    pid_t guardian;
    pid_t worker = -1;
    char **environment;
    unsigned char records[17];
    size_t record_size = 0;
    int result_eof = 0;
    int status = 0;
    int observed_exit = 0;
    int valid = 0;
    DIR *processes;
    if (payload == NULL || payload_size == 0 || payload_size > 8U * 1024U * 1024U)
        fail("invalid supervisor envelope size");
    if (prctl(PR_SET_CHILD_SUBREAPER, 1) != 0 || lo_linux_disposition(SIGCHLD, SIG_DFL) != 0)
        fail("cannot establish native supervisor ownership");
    processes = opendir("/proc");
    if (processes == NULL) fail("native child ownership enumeration is unavailable");
    closedir(processes);
    environment = lo_linux_boot_environment();
    if (lo_linux_pipe(control) != 0) fail("cannot create supervisor control channel");
    if (lo_linux_pipe(result) != 0) { close(control[0]); close(control[1]); fail("cannot create supervisor result channel"); }
    if (lo_linux_flags(control[1], F_GETFL, O_NONBLOCK) != 0 || lo_linux_flags(result[0], F_GETFL, O_NONBLOCK) != 0)
        fail("cannot configure supervisor channels");
    lo_linux_control = control[1];
    if (lo_linux_disposition(SIGTERM, lo_linux_signal) != 0 || lo_linux_disposition(SIGINT, lo_linux_signal) != 0
            || lo_linux_disposition(SIGHUP, lo_linux_signal) != 0 || lo_linux_disposition(SIGPIPE, SIG_IGN) != 0)
        fail("cannot configure supervisor cancellation");
    guardian = fork();
    if (guardian < 0) fail("cannot start native supervisor guardian");
    if (guardian == 0) {
        close(control[1]);
        close(result[0]);
        lo_linux_control = -1;
        lo_linux_guardian(config, policy_path, payload, payload_size, control[0], result[1], environment);
    }
    close(control[0]);
    close(result[1]);
    free(environment);
    close(STDIN_FILENO);
    close(STDOUT_FILENO);
    for (;;) {
        struct pollfd event = {result[0], POLLIN, 0};
        siginfo_t observed;
        int polled = poll(&event, 1, 50);
        if (polled < 0 && errno != EINTR) break;
        if (event.revents != 0 && !result_eof) {
            ssize_t count = read(result[0], records + record_size, sizeof(records) - record_size);
            if (count > 0) {
                record_size += (size_t) count;
                if (record_size >= 8 && completion_number(records) == LO_LINUX_WORKER
                        && completion_number(records + 4) > 1 && completion_number(records + 4) <= INT_MAX)
                    worker = (pid_t) completion_number(records + 4);
                if (record_size == sizeof(records)) break;
            } else if (count == 0) result_eof = 1;
            else if (errno != EINTR && errno != EAGAIN && errno != EWOULDBLOCK) break;
        }
        memset(&observed, 0, sizeof(observed));
        if (waitid(P_PID, (id_t) guardian, &observed, WEXITED | WNOHANG | WNOWAIT) != 0) break;
        if (observed.si_pid == guardian) {
            observed_exit = 1;
            if (result_eof) {
                valid = observed.si_code == CLD_EXITED && observed.si_status == 0 && record_size == 16
                        && worker > 1 && completion_number(records + 8) == LO_LINUX_COMPLETE;
                break;
            }
        }
    }
    lo_linux_control = -1;
    close(control[1]);
    close(result[0]);
    if (!valid) {
        if (!observed_exit) (void) kill(guardian, SIGKILL);
        /* Guardian death reparents its descendants to this fallback subreaper.
         * Keep the guardian unreaped until its final group has been signalled. */
        lo_linux_cleanup(guardian);
        fprintf(stderr, "LoadOnce runtime: supervisor did not complete normally\n");
        return 1;
    }
    do { guardian = waitpid(guardian, &status, 0); } while (guardian < 0 && errno == EINTR);
    if (guardian < 0) fail("cannot observe native supervisor guardian exit");
    /* Preserve the signed Java exit code; the OS applies its native exit width. */
    {
        uint32_t number = completion_number(records + 12);
        return number <= INT32_MAX ? (int) number : -1 - (int) (UINT32_MAX - number);
    }
}

#endif
