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

struct configuration {
    char *storage;
    char *java;
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
    struct configuration result = {NULL, NULL, NULL};
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
        else if (strncmp(line, "agent=", 6) == 0) { destination = &result.agent; line += 6; }
        else fail("configuration contains an unknown or malformed field");
        if (*destination != NULL || !valid_value(line)) fail("configuration contains a duplicate or empty field");
        *destination = line;
    }
    if (result.java == NULL || result.agent == NULL) fail("configuration requires java and agent fields");
    if (strchr(result.agent, '=') != NULL) fail("agent path cannot contain the Java agent option separator");
    return result;
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

int main(int argc, char **argv) {
    char *self = executable_path();
    char *config_path = adjacent_path(self, CONFIG_NAME);
    struct configuration config = read_configuration(fopen(config_path, "rb"));
    struct stat self_info;
    struct stat backend_info;
    struct stat agent_info;
    char *backend_config;
    char *canonical_backend;
    char *agent_option;
    char **arguments;
    size_t index;
    if (config.java[0] != '/' || config.agent[0] != '/') fail("configured paths must be absolute");
    if (stat(self, &self_info) != 0 || stat(config.java, &backend_info) != 0
            || !S_ISREG(backend_info.st_mode) || access(config.java, X_OK) != 0)
        fail("configured backend is not an executable regular file");
    if (self_info.st_dev == backend_info.st_dev && self_info.st_ino == backend_info.st_ino)
        fail("configured backend redirects to this executable");
    canonical_backend = realpath(config.java, NULL);
    if (canonical_backend == NULL) fail("cannot resolve configured backend");
    backend_config = adjacent_path(canonical_backend, CONFIG_NAME);
    if (access(backend_config, F_OK) == 0) fail("configured backend is another LoadOnce redirect runtime");
    if (stat(config.agent, &agent_info) != 0 || !S_ISREG(agent_info.st_mode) || access(config.agent, R_OK) != 0)
        fail("configured agent is not a readable regular file");
    agent_option = allocate(strlen(config.agent) + sizeof("-javaagent:"));
    strcpy(agent_option, "-javaagent:");
    strcat(agent_option, config.agent);
    arguments = allocate(((size_t) argc + 2) * sizeof(*arguments));
    arguments[0] = config.java;
    arguments[1] = agent_option;
    for (index = 1; index < (size_t) argc; index++) arguments[index + 1] = argv[index];
    arguments[(size_t) argc + 1] = NULL;
    /* exec preserves process identity, signals, cwd, environment and inherited descriptors. */
    execv(config.java, arguments);
    fail("could not execute configured Java backend");
    return 1;
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

static wchar_t *command_line(int argc, wchar_t **argv, const wchar_t *java, const wchar_t *agent) {
    size_t capacity = 2 * (wcslen(java) + wcslen(agent)) + 32;
    size_t cursor = 0;
    wchar_t *result;
    wchar_t *agent_option;
    int index;
    for (index = 1; index < argc; index++) capacity += 2 * wcslen(argv[index]) + 4;
    result = allocate(capacity * sizeof(*result));
    agent_option = allocate((wcslen(agent) + 12) * sizeof(*agent_option));
    wcscpy(agent_option, L"-javaagent:");
    wcscat(agent_option, agent);
    for (index = 0; index <= argc; index++) {
        const wchar_t *argument = index == 0 ? java : index == 1 ? agent_option : argv[index - 1];
        const wchar_t *scan = argument;
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
    free(agent_option);
    return result;
}

static PVOID volatile control_process = NULL;

static BOOL WINAPI console_event(DWORD event) {
    /* The child inherits the console group, so Windows delivers each event directly.
       Replaying it with GenerateConsoleCtrlEvent would deliver Ctrl-C/Break twice. */
    if (event == CTRL_C_EVENT || event == CTRL_BREAK_EVENT) return TRUE;
    if (event == CTRL_CLOSE_EVENT || event == CTRL_LOGOFF_EVENT || event == CTRL_SHUTDOWN_EVENT) {
        HANDLE process = (HANDLE) InterlockedCompareExchangePointer(&control_process, NULL, NULL);
        // Windows bounds console-close handlers. Let the JVM's direct event run its shutdown hooks first.
        if (process != NULL) (void) WaitForSingleObject(process, 4000);
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

static int redirect_windows(void) {
    wchar_t *self = executable_path();
    wchar_t *config_path = adjacent_path(self, L"loadonce-runtime.conf");
    struct configuration config = read_configuration(_wfopen(config_path, L"rb"));
    wchar_t *java = utf8_path(config.java);
    wchar_t *agent = utf8_path(config.agent);
    wchar_t *backend_config;
    wchar_t *canonical_backend;
    wchar_t **argv;
    wchar_t *line;
    int argc;
    HANDLE job;
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits;
    STARTUPINFOEXW startup;
    SIZE_T attribute_size = 0;
    PROCESS_INFORMATION process;
    DWORD exit_code;
    BOOL created;
#ifdef LOADONCE_GUI
    {
        wchar_t *console_java = java;
        java = adjacent_path(console_java, L"javaw.exe");
        free(console_java);
    }
#endif
    if (same_file(file_identity(self), file_identity(java))) fail("configured backend redirects to this executable");
    (void) file_identity(agent);
    canonical_backend = canonical_path(java);
    backend_config = adjacent_path(canonical_backend, L"loadonce-runtime.conf");
    if (GetFileAttributesW(backend_config) != INVALID_FILE_ATTRIBUTES)
        fail("configured backend is another LoadOnce redirect runtime");
    argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    if (argv == NULL || argc < 1) fail("cannot parse Unicode command line");
    line = command_line(argc, argv, java, agent);
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
    if (InitializeProcThreadAttributeList(NULL, 1, 0, &attribute_size)
            || GetLastError() != ERROR_INSUFFICIENT_BUFFER) fail("cannot size Java startup attributes");
    startup.lpAttributeList = allocate(attribute_size);
    if (!InitializeProcThreadAttributeList(startup.lpAttributeList, 1, 0, &attribute_size)
            || !UpdateProcThreadAttribute(startup.lpAttributeList, 0, PROC_THREAD_ATTRIBUTE_JOB_LIST,
                    &job, sizeof(job), NULL, NULL)) fail("cannot configure atomic child cleanup job");
    memset(&process, 0, sizeof(process));
    if (!SetConsoleCtrlHandler(console_event, TRUE) && GetLastError() != ERROR_INVALID_HANDLE)
        fail("cannot register console event propagation");
    // Windows 10+ assigns the job inside process creation, including a wrapper death before ResumeThread.
    created = CreateProcessW(java, line, NULL, NULL, TRUE, CREATE_SUSPENDED | EXTENDED_STARTUPINFO_PRESENT,
            NULL, NULL, &startup.StartupInfo, &process);
    DeleteProcThreadAttributeList(startup.lpAttributeList);
    free(startup.lpAttributeList);
    if (startup.StartupInfo.hStdInput != NULL) CloseHandle(startup.StartupInfo.hStdInput);
    if (startup.StartupInfo.hStdOutput != NULL) CloseHandle(startup.StartupInfo.hStdOutput);
    if (startup.StartupInfo.hStdError != NULL) CloseHandle(startup.StartupInfo.hStdError);
    if (!created) fail("could not execute configured Java backend");
    (void) InterlockedExchangePointer(&control_process, process.hProcess);
    if (ResumeThread(process.hThread) == (DWORD) -1) { CloseHandle(job); fail("cannot resume Java backend"); }
    CloseHandle(process.hThread);
    if (WaitForSingleObject(process.hProcess, INFINITE) != WAIT_OBJECT_0
            || !GetExitCodeProcess(process.hProcess, &exit_code)) {
        CloseHandle(job);
        fail("cannot observe Java backend exit");
    }
    // Keep the process handle valid for a concurrently running console-close handler until ExitProcess.
    // A successful Java exit may intentionally leave a helper process running, as direct Java does.
    limits.BasicLimitInformation.LimitFlags = 0;
    if (!SetInformationJobObject(job, JobObjectExtendedLimitInformation, &limits, (DWORD) sizeof(limits))) {
        CloseHandle(job);
        fail("cannot finish child cleanup job");
    }
    CloseHandle(job);
    LocalFree(argv);
    free(line);
    free(config.storage);
    free(self);
    free(config_path);
    free(agent);
    free(java);
    free(backend_config);
    free(canonical_backend);
    ExitProcess(exit_code);
    return 0;
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
