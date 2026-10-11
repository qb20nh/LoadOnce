#define _GNU_SOURCE
#include <libweston/libweston.h>
#include <libweston/weston-log.h>
#include <weston/weston.h>
#include <wayland-server-core.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

/* Weston 15 exports this backend entry point; its event type is public. */
extern void notify_motion(struct weston_seat *, const struct timespec *,
                          struct weston_pointer_motion_event *);
extern void notify_pointer_frame(struct weston_seat *);

struct transport;
struct request {
    struct transport *transport;
    struct wl_event_source *source, *timer;
    int fd;
    size_t length;
    char text[64];
};
struct transport {
    struct weston_compositor *compositor;
    struct wl_event_loop *loop;
    struct wl_event_source *source;
    struct wl_listener destroy;
    struct request requests[8];
    int fd;
    char path[sizeof(((struct sockaddr_un *)0)->sun_path)];
};

static void finish(struct request *request, const char *reply)
{
    if (reply) (void)send(request->fd, reply, strlen(reply), MSG_NOSIGNAL);
    if (request->source) wl_event_source_remove(request->source);
    if (request->timer) wl_event_source_remove(request->timer);
    close(request->fd);
    request->source = request->timer = NULL;
    request->fd = -1;
}

static int timeout_request(void *data)
{
    finish(data, "TIMEOUT\n");
    return 0;
}

static int receive_request(int fd, uint32_t mask, void *data)
{
    struct request *request = data;
    (void)mask;
    ssize_t count = read(fd, request->text + request->length,
                         sizeof(request->text) - 1 - request->length);
    if (count < 0 && (errno == EAGAIN || errno == EINTR)) return 0;
    if (count <= 0) { finish(request, "INVALID\n"); return 0; }
    request->length += (size_t)count;
    request->text[request->length] = '\0';
    char *newline = memchr(request->text, '\n', request->length);
    if (!newline) {
        if (request->length == sizeof(request->text) - 1) finish(request, "INVALID\n");
        return 0;
    }
    double dx, dy;
    int end = 0;
    if (newline != request->text + request->length - 1
            || sscanf(request->text, "MOVE %lf %lf%n", &dx, &dy, &end) != 2
            || end != (int)request->length - 1 || !isfinite(dx) || !isfinite(dy)
            || fabs(dx) > 4096 || fabs(dy) > 4096) {
        finish(request, "INVALID\n");
        return 0;
    }
    struct weston_seat *seat, *selected = NULL;
    unsigned seats = 0;
    wl_list_for_each(seat, &request->transport->compositor->seat_list, link) {
        seats++;
        if (weston_seat_get_pointer(seat)) selected = seat;
    }
    if (seats != 1 || !selected) { finish(request, "NO_PRIVATE_SEAT\n"); return 0; }
    struct weston_pointer_motion_event event = {0};
    event.mask = WESTON_POINTER_MOTION_REL | WESTON_POINTER_MOTION_REL_UNACCEL;
    clock_gettime(CLOCK_MONOTONIC, &event.time);
    event.rel = weston_coord(dx, dy);
    event.rel_unaccel = event.rel;
    notify_motion(selected, &event.time, &event);
    notify_pointer_frame(selected);
    finish(request, "DISPATCHED\n"); /* Dispatch is not proof of game camera movement. */
    return 0;
}

static int accept_request(int fd, uint32_t mask, void *data)
{
    struct transport *transport = data;
    (void)mask;
    int client = accept4(fd, NULL, NULL, SOCK_CLOEXEC | SOCK_NONBLOCK);
    if (client < 0) return 0;
    struct ucred credentials;
    socklen_t length = sizeof(credentials);
    if (getsockopt(client, SOL_SOCKET, SO_PEERCRED, &credentials, &length) < 0
            || credentials.uid != getuid()) { close(client); return 0; }
    for (unsigned i = 0; i < 8; i++) {
        struct request *request = &transport->requests[i];
        if (request->fd >= 0) continue;
        request->fd = client;
        request->length = 0;
        request->source = wl_event_loop_add_fd(transport->loop, client,
                                               WL_EVENT_READABLE, receive_request, request);
        request->timer = wl_event_loop_add_timer(transport->loop, timeout_request, request);
        if (!request->source || !request->timer) finish(request, "ERROR\n");
        else wl_event_source_timer_update(request->timer, 2000);
        return 0;
    }
    close(client);
    return 0;
}

static void destroy_transport(struct wl_listener *listener, void *data)
{
    struct transport *transport = wl_container_of(listener, transport, destroy);
    (void)data;
    for (unsigned i = 0; i < 8; i++)
        if (transport->requests[i].fd >= 0) finish(&transport->requests[i], NULL);
    wl_event_source_remove(transport->source);
    close(transport->fd);
    unlink(transport->path);
    wl_list_remove(&transport->destroy.link);
    free(transport);
}

static int private_runtime(const char *runtime)
{
    char resolved[PATH_MAX], root[PATH_MAX];
    struct stat status;
    if (!runtime || !realpath(runtime, resolved) || strcmp(runtime, resolved)
            || strlen(runtime) >= sizeof(root)) return 0;
    strcpy(root, runtime);
    char *suffix = strrchr(root, '/');
    if (!suffix || strcmp(suffix, "/xdg")) return 0;
    *suffix = '\0';
    char *name = strrchr(root, '/');
    if (!name || strncmp(name + 1, "loadonce-native-display-", strlen("loadonce-native-display-"))) return 0;
    if (stat(root, &status) || !S_ISDIR(status.st_mode) || status.st_uid != getuid()
            || (status.st_mode & 0077)) return 0;
    return !stat(runtime, &status) && S_ISDIR(status.st_mode)
            && status.st_uid == getuid() && !(status.st_mode & 0077);
}

WL_EXPORT int wet_module_init(struct weston_compositor *compositor, int *argc, char *argv[])
{
    (void)argc;
    (void)argv;
    const char *runtime = getenv("XDG_RUNTIME_DIR");
    if (!private_runtime(runtime)) return -1;
    struct transport *transport = calloc(1, sizeof(*transport));
    if (!transport) return -1;
    for (unsigned i = 0; i < 8; i++) {
        transport->requests[i].fd = -1;
        transport->requests[i].transport = transport;
    }
    transport->compositor = compositor;
    transport->loop = wl_display_get_event_loop(compositor->wl_display);
    int size = snprintf(transport->path, sizeof(transport->path), "%s/loadonce-relative-input.sock", runtime);
    if (size < 0 || (size_t)size >= sizeof(transport->path)) { free(transport); return -1; }
    transport->fd = socket(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (transport->fd < 0) { free(transport); return -1; }
    struct sockaddr_un address = { .sun_family = AF_UNIX };
    strcpy(address.sun_path, transport->path);
    if (bind(transport->fd, (struct sockaddr *)&address, sizeof(address)) < 0) {
        close(transport->fd); free(transport); return -1;
    }
    if (chmod(transport->path, 0600) < 0 || listen(transport->fd, 8) < 0) {
        close(transport->fd); unlink(transport->path); free(transport); return -1;
    }
    transport->source = wl_event_loop_add_fd(transport->loop, transport->fd,
                                             WL_EVENT_READABLE, accept_request, transport);
    if (!transport->source) {
        close(transport->fd); unlink(transport->path); free(transport); return -1;
    }
    transport->destroy.notify = destroy_transport;
    wl_signal_add(&compositor->destroy_signal, &transport->destroy);
    weston_log("LoadOnce private relative input: %s\n", transport->path);
    return 0;
}
