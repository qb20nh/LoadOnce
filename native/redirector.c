#ifndef _WIN32
#define _POSIX_C_SOURCE 200809L
#define _XOPEN_SOURCE 700
#elif !defined(_WIN32_WINNT)
#define _WIN32_WINNT 0x0a00
#endif

#include <errno.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#include <windows.h>
#include <shellapi.h>
#include <wchar.h>
#else
#include <sys/stat.h>
#include <unistd.h>
#endif

#define CONFIG_NAME "loadonce-runtime.conf"
#define CONFIG_LIMIT (1024U * 1024U)
#define PAYLOAD_LIMIT (8U * 1024U * 1024U)
#define ENTRY_LIMIT 65536U

struct configuration {
    char *storage;
    char *java;
    char *bootstrap_java;
    char *supervisor;
    char *agent;
};

static void fail(const char *message) {
    fprintf(stderr, "LoadOnce runtime: %s\n", message);
    exit(1);
}

static void *allocate(size_t size) {
    void *result = malloc(size);
    if (result == NULL) fail("allocation failed");
    return result;
}

static int valid_utf8(const unsigned char *bytes, size_t length) {
    size_t cursor = 0;
    while (cursor < length) {
        unsigned char first = bytes[cursor++];
        uint32_t value;
        uint32_t minimum;
        unsigned int count;
        if (first < 0x80U) continue;
        if (first >= 0xc2U && first <= 0xdfU) { value = first & 0x1fU; minimum = 0x80U; count = 1; }
        else if (first >= 0xe0U && first <= 0xefU) { value = first & 0x0fU; minimum = 0x800U; count = 2; }
        else if (first >= 0xf0U && first <= 0xf4U) { value = first & 0x07U; minimum = 0x10000U; count = 3; }
        else return 0;
        if (length - cursor < count) return 0;
        while (count-- != 0) {
            unsigned char next = bytes[cursor++];
            if ((next & 0xc0U) != 0x80U) return 0;
            value = (value << 6) | (next & 0x3fU);
        }
        if (value < minimum || value > 0x10ffffU || (value >= 0xd800U && value <= 0xdfffU)) return 0;
    }
    return 1;
}

static int valid_value(const char *value) {
    const unsigned char *cursor = (const unsigned char *) value;
    if (*cursor == 0) return 0;
    for (; *cursor != 0; cursor++) if (*cursor < 0x20U || *cursor == 0x7fU) return 0;
    return 1;
}

static struct configuration read_configuration(FILE *file) {
    struct configuration result = {NULL, NULL, NULL, NULL, NULL};
    long file_size;
    size_t length;
    char *cursor;
    if (file == NULL) fail("cannot open adjacent " CONFIG_NAME);
    if (fseek(file, 0, SEEK_END) != 0 || (file_size = ftell(file)) <= 0
            || (unsigned long) file_size > CONFIG_LIMIT || fseek(file, 0, SEEK_SET) != 0) {
        fclose(file);
        fail("invalid configuration size");
    }
    length = (size_t) file_size;
    result.storage = allocate(length + 1);
    if (fread(result.storage, 1, length, file) != length || fclose(file) != 0) fail("configuration read failed");
    if (memchr(result.storage, 0, length) != NULL || !valid_utf8((const unsigned char *) result.storage, length))
        fail("configuration must be UTF-8 without NUL bytes");
    result.storage[length] = 0;
    cursor = result.storage;
    while (*cursor != 0) {
        char *line = cursor;
        char *end = strchr(cursor, '\n');
        char **destination;
        size_t line_length;
        if (end != NULL) { *end = 0; cursor = end + 1; }
        else cursor += strlen(cursor);
        line_length = strlen(line);
        if (line_length != 0 && line[line_length - 1] == '\r') line[line_length - 1] = 0;
        if (strncmp(line, "java=", 5) == 0) { destination = &result.java; line += 5; }
        else if (strncmp(line, "bootstrap-java=", 15) == 0) { destination = &result.bootstrap_java; line += 15; }
        else if (strncmp(line, "supervisor=", 11) == 0) { destination = &result.supervisor; line += 11; }
        else if (strncmp(line, "agent=", 6) == 0) { destination = &result.agent; line += 6; }
        else fail("configuration contains an unknown or malformed field");
        if (*destination != NULL || !valid_value(line)) fail("configuration contains a duplicate or empty field");
        *destination = line;
    }
    if (result.java == NULL || result.bootstrap_java == NULL || result.supervisor == NULL || result.agent == NULL)
        fail("configuration requires java, bootstrap-java, supervisor and agent fields");
    if (strchr(result.agent, '=') != NULL) fail("agent path cannot contain the Java agent option separator");
    return result;
}

struct payload {
    unsigned char *bytes;
    size_t length;
    size_t capacity;
};

static void payload_append(struct payload *payload, const void *bytes, size_t length) {
    size_t required;
    if (length > PAYLOAD_LIMIT - payload->length) fail("supervisor input exceeds the protocol limit");
    required = payload->length + length;
    if (required > payload->capacity) {
        size_t capacity = payload->capacity == 0 ? 4096 : payload->capacity;
        unsigned char *replacement;
        while (capacity < required) capacity *= 2;
        replacement = realloc(payload->bytes, capacity);
        if (replacement == NULL) fail("allocation failed");
        payload->bytes = replacement;
        payload->capacity = capacity;
    }
    memcpy(payload->bytes + payload->length, bytes, length);
    payload->length = required;
}

static void payload_number(struct payload *payload, uint32_t value) {
    unsigned char bytes[4];
    bytes[0] = (unsigned char) (value >> 24);
    bytes[1] = (unsigned char) (value >> 16);
    bytes[2] = (unsigned char) (value >> 8);
    bytes[3] = (unsigned char) value;
    payload_append(payload, bytes, sizeof(bytes));
}

static void payload_string_length(struct payload *payload, const char *value, size_t length) {
    if (length > PAYLOAD_LIMIT || !valid_utf8((const unsigned char *) value, length))
        fail("supervisor input must be bounded UTF-8");
    payload_number(payload, (uint32_t) length);
    payload_append(payload, value, length);
}

static void payload_string(struct payload *payload, const char *value) {
    payload_string_length(payload, value, strlen(value));
}

static uint32_t completion_number(const unsigned char *bytes) {
    return ((uint32_t) bytes[0] << 24) | ((uint32_t) bytes[1] << 16)
            | ((uint32_t) bytes[2] << 8) | bytes[3];
}

#ifndef _WIN32
static char *executable_path(void) {
    size_t capacity = 256;
    for (;;) {
        char *path = allocate(capacity + 1);
        ssize_t length = readlink("/proc/self/exe", path, capacity);
        if (length < 0) fail("cannot locate redirector executable");
        if ((size_t) length < capacity) { path[length] = 0; return path; }
        free(path);
        if (capacity >= CONFIG_LIMIT) fail("redirector executable path is too long");
        capacity *= 2;
    }
}

static char *adjacent_path(const char *executable, const char *name) {
    const char *separator = strrchr(executable, '/');
    size_t prefix;
    char *path;
    if (separator == NULL) fail("executable path is not absolute");
    prefix = (size_t) (separator - executable) + 1;
    path = allocate(prefix + strlen(name) + 1);
    memcpy(path, executable, prefix);
    strcpy(path + prefix, name);
    return path;
}

static void validate_linux_backend(const char *self, const char *backend) {
    struct stat self_info;
    struct stat backend_info;
    char *backend_config;
    char *canonical_backend;
    if (backend[0] != '/') fail("configured paths must be absolute");
    if (stat(self, &self_info) != 0 || stat(backend, &backend_info) != 0
            || !S_ISREG(backend_info.st_mode) || access(backend, X_OK) != 0)
        fail("configured backend is not an executable regular file");
    if (self_info.st_dev == backend_info.st_dev && self_info.st_ino == backend_info.st_ino)
        fail("configured backend redirects to this executable");
    canonical_backend = realpath(backend, NULL);
    if (canonical_backend == NULL) fail("cannot resolve configured backend");
    backend_config = adjacent_path(canonical_backend, CONFIG_NAME);
    if (access(backend_config, F_OK) == 0) fail("configured backend is another LoadOnce redirect runtime");
    free(backend_config);
    free(canonical_backend);
}

static void validate_linux_artifact(const char *path) {
    struct stat info;
    if (path[0] != '/') fail("configured paths must be absolute");
    if (stat(path, &info) != 0 || !S_ISREG(info.st_mode) || access(path, R_OK) != 0)
        fail("configured artifact is not a readable regular file");
}

#include "linux_supervisor.h"

int main(int argc, char **argv) {
    extern char **environ;
    char *self = executable_path();
    char *config_path = adjacent_path(self, CONFIG_NAME);
    char *policy = adjacent_path(self, "../loadonce-policy.conf");
    struct configuration config = read_configuration(fopen(config_path, "rb"));
    struct payload payload = {NULL, 0, 0};
    size_t count = 0;
    size_t index;
    int result;
    validate_linux_backend(self, config.java);
    validate_linux_backend(self, config.bootstrap_java);
    validate_linux_artifact(config.agent);
    validate_linux_artifact(config.supervisor);
    if (argc < 1 || (unsigned int) (argc - 1) > ENTRY_LIMIT) fail("too many Java arguments");
    payload_number(&payload, UINT32_C(0x4c4f5331));
    payload_string(&payload, config.java);
    payload_string(&payload, config.agent);
    payload_number(&payload, (uint32_t) (argc - 1));
    for (index = 1; index < (size_t) argc; index++) payload_string(&payload, argv[index]);
    while (environ[count] != NULL) {
        if (++count > ENTRY_LIMIT) fail("too many environment entries");
    }
    payload_number(&payload, (uint32_t) count);
    for (index = 0; index < count; index++) {
        const char *separator = strchr(environ[index], '=');
        if (separator == NULL || separator == environ[index]) fail("malformed environment entry");
        payload_string_length(&payload, environ[index], (size_t) (separator - environ[index]));
        payload_string(&payload, separator + 1);
    }
    result = supervise_linux(&config, policy, payload.bytes, payload.length);
    free(payload.bytes);
    free(config.storage);
    free(self);
    free(config_path);
    free(policy);
    return result;
}
#else
static wchar_t *executable_path(void) {
    DWORD capacity = 256;
    for (;;) {
        wchar_t *path = allocate((size_t) capacity * sizeof(*path));
        DWORD length = GetModuleFileNameW(NULL, path, capacity);
        if (length == 0) fail("cannot locate redirector executable");
        if (length < capacity) return path;
        free(path);
        if (capacity >= 65536U) fail("redirector executable path is too long");
        capacity *= 2;
    }
}

static wchar_t *adjacent_path(const wchar_t *executable, const wchar_t *name) {
    const wchar_t *separator = wcsrchr(executable, L'\\');
    const wchar_t *slash = wcsrchr(executable, L'/');
    size_t prefix;
    wchar_t *path;
    if (slash != NULL && (separator == NULL || slash > separator)) separator = slash;
    if (separator == NULL) fail("executable path has no directory");
    prefix = (size_t) (separator - executable) + 1;
    path = allocate((prefix + wcslen(name) + 1) * sizeof(*path));
    memcpy(path, executable, prefix * sizeof(*path));
    wcscpy(path + prefix, name);
    return path;
}

static wchar_t *utf8_path(const char *value) {
    int length = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value, -1, NULL, 0);
    wchar_t *result;
    if (length == 0) fail("configured path is not valid UTF-8");
    result = allocate((size_t) length * sizeof(*result));
    if (MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value, -1, result, length) != length)
        fail("configured path conversion failed");
    if (!((result[0] != 0 && result[1] == L':' && (result[2] == L'\\' || result[2] == L'/'))
            || (result[0] == L'\\' && result[1] == L'\\')))
        fail("configured paths must be absolute");
    return result;
}

static BY_HANDLE_FILE_INFORMATION file_identity(const wchar_t *path) {
    BY_HANDLE_FILE_INFORMATION result;
    HANDLE file = CreateFileW(path, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
            NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (file == INVALID_HANDLE_VALUE) fail("configured file is not readable");
    if (!GetFileInformationByHandle(file, &result)) { CloseHandle(file); fail("cannot inspect configured file"); }
    CloseHandle(file);
    if ((result.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0) fail("configured file is a directory");
    return result;
}

static int same_file(BY_HANDLE_FILE_INFORMATION first, BY_HANDLE_FILE_INFORMATION second) {
    return first.dwVolumeSerialNumber == second.dwVolumeSerialNumber
            && first.nFileIndexHigh == second.nFileIndexHigh && first.nFileIndexLow == second.nFileIndexLow;
}

static wchar_t *canonical_path(const wchar_t *path) {
    HANDLE file = CreateFileW(path, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
            NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    DWORD capacity;
    DWORD length;
    wchar_t *result;
    if (file == INVALID_HANDLE_VALUE) fail("cannot resolve configured backend");
    capacity = GetFinalPathNameByHandleW(file, NULL, 0, FILE_NAME_NORMALIZED | VOLUME_NAME_DOS);
    if (capacity == 0) { CloseHandle(file); fail("cannot resolve configured backend"); }
    result = allocate((size_t) capacity * sizeof(*result));
    length = GetFinalPathNameByHandleW(file, result, capacity, FILE_NAME_NORMALIZED | VOLUME_NAME_DOS);
    CloseHandle(file);
    if (length == 0 || length >= capacity) fail("cannot resolve configured backend");
    return result;
}

static wchar_t *command_line(size_t count, const wchar_t *const *arguments) {
    size_t capacity = 1;
    size_t cursor = 0;
    wchar_t *result;
    size_t index;
    for (index = 0; index < count; index++) capacity += 2 * wcslen(arguments[index]) + 4;
    result = allocate(capacity * sizeof(*result));
    for (index = 0; index < count; index++) {
        const wchar_t *scan = arguments[index];
        if (index != 0) result[cursor++] = L' ';
        result[cursor++] = L'"';
        while (*scan != 0) {
            size_t slashes = 0;
            while (*scan == L'\\') { slashes++; scan++; }
            if (*scan == L'"' || *scan == 0) slashes *= 2;
            while (slashes-- != 0) result[cursor++] = L'\\';
            if (*scan == L'"') result[cursor++] = L'\\';
            if (*scan != 0) result[cursor++] = *scan++;
        }
        result[cursor++] = L'"';
    }
    if (cursor >= 32767U) fail("redirected command line exceeds the Windows limit");
    result[cursor] = 0;
    return result;
}

static PVOID volatile control_process = NULL;
static HANDLE cancellation_event = NULL;
static LONG volatile cancellation_requested = 0;

static BOOL WINAPI console_event(DWORD event) {
    /* The child inherits the console group, so Windows delivers each event directly.
       Replaying it with GenerateConsoleCtrlEvent would deliver Ctrl-C/Break twice. */
    if (event == CTRL_C_EVENT || event == CTRL_BREAK_EVENT || event == CTRL_CLOSE_EVENT
            || event == CTRL_LOGOFF_EVENT || event == CTRL_SHUTDOWN_EVENT) {
        HANDLE process = (HANDLE) InterlockedCompareExchangePointer(&control_process, NULL, NULL);
        (void) InterlockedExchange(&cancellation_requested, 1);
        if (cancellation_event != NULL) (void) SetEvent(cancellation_event);
        // Windows bounds console-close handlers. Let the JVM's direct event run its shutdown hooks first.
        if (event != CTRL_C_EVENT && event != CTRL_BREAK_EVENT && process != NULL)
            (void) WaitForSingleObject(process, 4000);
        return TRUE;
    }
    return FALSE;
}

static HANDLE inherited_standard_handle(DWORD name) {
    HANDLE current = GetStdHandle(name);
    HANDLE duplicate = NULL;
    if (current == NULL || current == INVALID_HANDLE_VALUE) return NULL;
    if (!DuplicateHandle(GetCurrentProcess(), current, GetCurrentProcess(), &duplicate,
            0, TRUE, DUPLICATE_SAME_ACCESS)) fail("cannot inherit a standard handle");
    return duplicate;
}

static void payload_wide_length(struct payload *payload, const wchar_t *value, size_t length) {
    int bytes;
    char *encoded;
    if (length > INT_MAX) fail("supervisor input string is too long");
    if (length == 0) { payload_string(payload, ""); return; }
    bytes = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, value, (int) length, NULL, 0, NULL, NULL);
    if (bytes <= 0) fail("supervisor input contains invalid Unicode");
    encoded = allocate((size_t) bytes);
    if (WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, value, (int) length,
            encoded, bytes, NULL, NULL) != bytes) fail("supervisor input conversion failed");
    payload_string_length(payload, encoded, (size_t) bytes);
    free(encoded);
}

static void payload_wide(struct payload *payload, const wchar_t *value) {
    payload_wide_length(payload, value, wcslen(value));
}

static const wchar_t *environment_separator(const wchar_t *entry) {
    const wchar_t *separator = wcschr(entry + (*entry == L'=' ? 1 : 0), L'=');
    if (separator == NULL || separator == entry) fail("malformed environment entry");
    return separator;
}

static int bootstrap_environment_key(const wchar_t *entry) {
    static const wchar_t *const removed[] = {
        L"JAVA_TOOL_OPTIONS", L"JDK_JAVA_OPTIONS", L"_JAVA_OPTIONS", L"JDK_AOT_VM_OPTIONS",
        L"LD_LIBRARY_PATH", L"LD_PRELOAD", L"DYLD_LIBRARY_PATH", L"DYLD_INSERT_LIBRARIES"
    };
    const wchar_t *separator = environment_separator(entry);
    size_t length = (size_t) (separator - entry);
    size_t index;
    for (index = 0; index < sizeof(removed) / sizeof(removed[0]); index++) {
        if (wcslen(removed[index]) == length && _wcsnicmp(entry, removed[index], length) == 0) return 0;
    }
    return 1;
}

static wchar_t *bootstrap_environment(const wchar_t *environment) {
    const wchar_t *entry;
    size_t length = 1;
    size_t cursor = 0;
    wchar_t *filtered;
    for (entry = environment; *entry != 0; entry += wcslen(entry) + 1) {
        if (bootstrap_environment_key(entry)) length += wcslen(entry) + 1;
    }
    if (length == 1) length = 2;
    filtered = allocate(length * sizeof(*filtered));
    for (entry = environment; *entry != 0; entry += wcslen(entry) + 1) {
        if (bootstrap_environment_key(entry)) {
            size_t count = wcslen(entry) + 1;
            memcpy(filtered + cursor, entry, count * sizeof(*filtered));
            cursor += count;
        }
    }
    filtered[cursor++] = 0;
    if (cursor == 1) filtered[cursor] = 0;
    return filtered;
}

static void validate_windows_backend(const wchar_t *self, const wchar_t *java) {
    wchar_t *canonical_backend;
    wchar_t *backend_config;
    if (same_file(file_identity(self), file_identity(java))) fail("configured backend redirects to this executable");
    canonical_backend = canonical_path(java);
    backend_config = adjacent_path(canonical_backend, L"loadonce-runtime.conf");
    if (GetFileAttributesW(backend_config) != INVALID_FILE_ATTRIBUTES)
        fail("configured backend is another LoadOnce redirect runtime");
    free(backend_config);
    free(canonical_backend);
}

struct payload_writer {
    HANDLE pipe;
    const unsigned char *bytes;
    size_t length;
    LONG volatile failed;
};

static DWORD WINAPI write_payload(LPVOID argument) {
    struct payload_writer *writer = argument;
    size_t cursor = 0;
    while (cursor < writer->length) {
        size_t remaining = writer->length - cursor;
        DWORD count = (DWORD) (remaining > 65536U ? 65536U : remaining);
        DWORD written = 0;
        if (!WriteFile(writer->pipe, writer->bytes + cursor, count, &written, NULL) || written == 0) {
            (void) InterlockedExchange(&writer->failed, 1);
            break;
        }
        cursor += written;
    }
    CloseHandle(writer->pipe);
    return 0;
}

static int read_completion(HANDLE pipe, DWORD exit_code) {
    unsigned char bytes[8];
    DWORD available = 0;
    DWORD read_count = 0;
    if (!PeekNamedPipe(pipe, NULL, 0, NULL, &available, NULL) || available != sizeof(bytes)) return 0;
    if (!ReadFile(pipe, bytes, (DWORD) sizeof(bytes), &read_count, NULL) || read_count != sizeof(bytes)) return 0;
    return completion_number(bytes) == UINT32_C(0x4c4f4431) && completion_number(bytes + 4) == exit_code;
}

static int redirect_windows(void) {
    wchar_t *self = executable_path();
    wchar_t *config_path = adjacent_path(self, L"loadonce-runtime.conf");
    struct configuration config = read_configuration(_wfopen(config_path, L"rb"));
    wchar_t *java = utf8_path(config.java);
    wchar_t *bootstrap_java = utf8_path(config.bootstrap_java);
    wchar_t *supervisor = utf8_path(config.supervisor);
    wchar_t *agent = utf8_path(config.agent);
    wchar_t *policy = adjacent_path(self, L"../loadonce-policy.conf");
    wchar_t **argv;
    wchar_t *line;
    wchar_t *agent_option;
    wchar_t *original_environment;
    wchar_t *filtered_environment;
    wchar_t input_descriptor[32];
    wchar_t completion_descriptor[32];
    const wchar_t *bootstrap_arguments[16];
    const wchar_t *entry;
    struct payload payload = {NULL, 0, 0};
    struct payload_writer writer;
    SECURITY_ATTRIBUTES pipe_security;
    HANDLE input_read;
    HANDLE input_write;
    HANDLE completion_read;
    HANDLE completion_write;
    HANDLE writer_thread;
    HANDLE inherited_handles[5];
    HANDLE wait_handles[3];
    size_t inherited_count = 0;
    uint32_t environment_count = 0;
    int index;
    int argc;
    HANDLE job;
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits;
    STARTUPINFOEXW startup;
    SIZE_T attribute_size = 0;
    PROCESS_INFORMATION process;
    DWORD exit_code = 125;
    DWORD waited;
    int acknowledged;
    BOOL created;
#ifdef LOADONCE_GUI
    {
        wchar_t *console_java = java;
        java = adjacent_path(console_java, L"javaw.exe");
        free(console_java);
        console_java = bootstrap_java;
        bootstrap_java = adjacent_path(console_java, L"javaw.exe");
        free(console_java);
    }
#endif
    validate_windows_backend(self, java);
    validate_windows_backend(self, bootstrap_java);
    (void) file_identity(agent);
    (void) file_identity(supervisor);
    argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    if (argv == NULL || argc < 1 || (unsigned int) (argc - 1) > ENTRY_LIMIT)
        fail("cannot parse bounded Unicode command line");
    original_environment = GetEnvironmentStringsW();
    if (original_environment == NULL) fail("cannot snapshot application environment");
    payload_number(&payload, UINT32_C(0x4c4f5331));
    payload_wide(&payload, java);
    payload_wide(&payload, agent);
    payload_number(&payload, (uint32_t) (argc - 1));
    for (index = 1; index < argc; index++) payload_wide(&payload, argv[index]);
    for (entry = original_environment; *entry != 0; entry += wcslen(entry) + 1) {
        if (++environment_count > ENTRY_LIMIT) fail("too many environment entries");
    }
    payload_number(&payload, environment_count);
    for (entry = original_environment; *entry != 0; entry += wcslen(entry) + 1) {
        const wchar_t *separator = environment_separator(entry);
        payload_wide_length(&payload, entry, (size_t) (separator - entry));
        payload_wide(&payload, separator + 1);
    }
    filtered_environment = bootstrap_environment(original_environment);
    FreeEnvironmentStringsW(original_environment);
    memset(&pipe_security, 0, sizeof(pipe_security));
    pipe_security.nLength = (DWORD) sizeof(pipe_security);
    pipe_security.bInheritHandle = TRUE;
    if (!CreatePipe(&input_read, &input_write, &pipe_security, 0)
            || !CreatePipe(&completion_read, &completion_write, &pipe_security, 0)
            || !SetHandleInformation(input_write, HANDLE_FLAG_INHERIT, 0)
            || !SetHandleInformation(completion_read, HANDLE_FLAG_INHERIT, 0))
        fail("cannot create supervisor transport");
    if (swprintf(input_descriptor, sizeof(input_descriptor) / sizeof(input_descriptor[0]), L"%llu",
            (unsigned long long) (uintptr_t) input_read) < 0
            || swprintf(completion_descriptor, sizeof(completion_descriptor) / sizeof(completion_descriptor[0]), L"%llu",
                    (unsigned long long) (uintptr_t) completion_write) < 0) fail("cannot format supervisor descriptors");
    agent_option = allocate((wcslen(agent) + 12) * sizeof(*agent_option));
    wcscpy(agent_option, L"-javaagent:");
    wcscat(agent_option, agent);
    bootstrap_arguments[0] = bootstrap_java;
    bootstrap_arguments[1] = L"-Xms16m";
    bootstrap_arguments[2] = L"-Xmx128m";
    bootstrap_arguments[3] = L"-Djdk.lang.Process.allowAmbiguousCommands=true";
    bootstrap_arguments[4] = L"--add-opens=java.base/java.io=ALL-UNNAMED";
    bootstrap_arguments[5] = L"--disable-@files";
    bootstrap_arguments[6] = agent_option;
    bootstrap_arguments[7] = L"-cp";
    bootstrap_arguments[8] = supervisor;
    bootstrap_arguments[9] = L"dev.loadonce.supervisor.SupervisorMain";
    bootstrap_arguments[10] = L"--input-descriptor";
    bootstrap_arguments[11] = input_descriptor;
    bootstrap_arguments[12] = L"--completion-descriptor";
    bootstrap_arguments[13] = completion_descriptor;
    bootstrap_arguments[14] = L"--policy";
    bootstrap_arguments[15] = policy;
    line = command_line(sizeof(bootstrap_arguments) / sizeof(bootstrap_arguments[0]), bootstrap_arguments);
    job = CreateJobObjectW(NULL, NULL);
    if (job == NULL) fail("cannot create child cleanup job");
    memset(&limits, 0, sizeof(limits));
    limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    if (!SetInformationJobObject(job, JobObjectExtendedLimitInformation, &limits, (DWORD) sizeof(limits)))
        fail("cannot configure child cleanup job");
    memset(&startup, 0, sizeof(startup));
    startup.StartupInfo.cb = (DWORD) sizeof(startup);
    startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    startup.StartupInfo.hStdInput = inherited_standard_handle(STD_INPUT_HANDLE);
    startup.StartupInfo.hStdOutput = inherited_standard_handle(STD_OUTPUT_HANDLE);
    startup.StartupInfo.hStdError = inherited_standard_handle(STD_ERROR_HANDLE);
    if (startup.StartupInfo.hStdInput != NULL) inherited_handles[inherited_count++] = startup.StartupInfo.hStdInput;
    if (startup.StartupInfo.hStdOutput != NULL) inherited_handles[inherited_count++] = startup.StartupInfo.hStdOutput;
    if (startup.StartupInfo.hStdError != NULL) inherited_handles[inherited_count++] = startup.StartupInfo.hStdError;
    inherited_handles[inherited_count++] = input_read;
    inherited_handles[inherited_count++] = completion_write;
    if (InitializeProcThreadAttributeList(NULL, 2, 0, &attribute_size)
            || GetLastError() != ERROR_INSUFFICIENT_BUFFER) fail("cannot size Java startup attributes");
    startup.lpAttributeList = allocate(attribute_size);
    if (!InitializeProcThreadAttributeList(startup.lpAttributeList, 2, 0, &attribute_size)
            || !UpdateProcThreadAttribute(startup.lpAttributeList, 0, PROC_THREAD_ATTRIBUTE_JOB_LIST,
                    &job, sizeof(job), NULL, NULL)
            || !UpdateProcThreadAttribute(startup.lpAttributeList, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST,
                    inherited_handles, inherited_count * sizeof(inherited_handles[0]), NULL, NULL))
        fail("cannot configure atomic supervisor ownership");
    memset(&process, 0, sizeof(process));
    cancellation_event = CreateEventW(NULL, TRUE, FALSE, NULL);
    if (cancellation_event == NULL) fail("cannot create cancellation event");
    if (!SetConsoleCtrlHandler(console_event, TRUE) && GetLastError() != ERROR_INVALID_HANDLE)
        fail("cannot register console event propagation");
    // Windows 10+ assigns the job inside process creation, including a wrapper death before ResumeThread.
    created = CreateProcessW(bootstrap_java, line, NULL, NULL, TRUE,
            CREATE_SUSPENDED | EXTENDED_STARTUPINFO_PRESENT | CREATE_UNICODE_ENVIRONMENT,
            filtered_environment, NULL, &startup.StartupInfo, &process);
    DeleteProcThreadAttributeList(startup.lpAttributeList);
    free(startup.lpAttributeList);
    if (startup.StartupInfo.hStdInput != NULL) CloseHandle(startup.StartupInfo.hStdInput);
    if (startup.StartupInfo.hStdOutput != NULL) CloseHandle(startup.StartupInfo.hStdOutput);
    if (startup.StartupInfo.hStdError != NULL) CloseHandle(startup.StartupInfo.hStdError);
    CloseHandle(input_read);
    CloseHandle(completion_write);
    if (!created) fail("could not execute configured supervisor JVM");
    (void) InterlockedExchangePointer(&control_process, process.hProcess);
    if (ResumeThread(process.hThread) == (DWORD) -1) { CloseHandle(job); fail("cannot resume supervisor JVM"); }
    CloseHandle(process.hThread);
    writer.pipe = input_write;
    writer.bytes = payload.bytes;
    writer.length = payload.length;
    writer.failed = 0;
    writer_thread = CreateThread(NULL, 0, write_payload, &writer, 0, NULL);
    if (writer_thread == NULL) { CloseHandle(job); fail("cannot start supervisor transport"); }
    wait_handles[0] = process.hProcess;
    wait_handles[1] = cancellation_event;
    wait_handles[2] = writer_thread;
    waited = WaitForMultipleObjects(3, wait_handles, FALSE, 60000);
    if (waited == WAIT_OBJECT_0 + 2) {
        if (InterlockedCompareExchange(&writer.failed, 0, 0) != 0) {
            CloseHandle(job);
            fail("supervisor input delivery failed");
        }
        waited = WaitForMultipleObjects(2, wait_handles, FALSE, INFINITE);
    }
    if (waited == WAIT_OBJECT_0 + 1) {
        if (WaitForSingleObject(process.hProcess, 4000) != WAIT_OBJECT_0) {
            (void) TerminateJobObject(job, 130);
            (void) WaitForSingleObject(process.hProcess, 5000);
        }
    } else if (waited != WAIT_OBJECT_0) {
        CloseHandle(job);
        fail("supervisor bootstrap timed out or failed");
    }
    (void) CancelSynchronousIo(writer_thread);
    if (WaitForSingleObject(writer_thread, 5000) != WAIT_OBJECT_0
            || !GetExitCodeProcess(process.hProcess, &exit_code)) {
        CloseHandle(job);
        fail("cannot observe supervisor completion");
    }
    CloseHandle(writer_thread);
    acknowledged = read_completion(completion_read, exit_code);
    CloseHandle(completion_read);
    // Keep the process handle valid for a concurrently running console-close handler until ExitProcess.
    // Only an acknowledged application exit releases intentional surviving helpers.
    if (acknowledged && InterlockedCompareExchange(&cancellation_requested, 0, 0) == 0) {
        limits.BasicLimitInformation.LimitFlags = 0;
        if (!SetInformationJobObject(job, JobObjectExtendedLimitInformation, &limits, (DWORD) sizeof(limits))) {
            CloseHandle(job);
            fail("cannot finish supervisor cleanup job");
        }
    }
    CloseHandle(job);
    if (!acknowledged && InterlockedCompareExchange(&cancellation_requested, 0, 0) == 0)
        fail("supervisor did not acknowledge application completion");
    LocalFree(argv);
    free(line);
    free(config.storage);
    free(self);
    free(config_path);
    free(agent);
    free(java);
    free(bootstrap_java);
    free(supervisor);
    free(policy);
    free(agent_option);
    free(filtered_environment);
    free(payload.bytes);
    ExitProcess(exit_code);
}

#ifdef LOADONCE_GUI
int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous, PWSTR command, int show) {
    (void) instance; (void) previous; (void) command; (void) show;
    return redirect_windows();
}
#else
int wmain(void) { return redirect_windows(); }
#endif
#endif
