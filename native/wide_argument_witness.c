#ifndef _WIN32
#error This test witness requires Windows
#endif
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0a00
#endif

#include <windows.h>
#include <shellapi.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

/* Records the redirector's actual Unicode CreateProcess boundary before a JVM
   launcher can convert arguments through the host's ANSI code page. */
static int write_argument(FILE *record, const wchar_t *argument) {
    static const char alphabet[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    int length = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, argument, -1, NULL, 0, NULL, NULL);
    unsigned char *utf8;
    size_t count;
    size_t index;
    if (length <= 0) return 0;
    utf8 = malloc((size_t) length);
    if (utf8 == NULL) return 0;
    if (WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, argument, -1, (char *) utf8, length, NULL, NULL) != length) {
        free(utf8);
        return 0;
    }
    count = (size_t) length - 1;
    for (index = 0; index < count; index += 3) {
        unsigned int first = utf8[index];
        unsigned int second = index + 1 < count ? utf8[index + 1] : 0;
        unsigned int third = index + 2 < count ? utf8[index + 2] : 0;
        char encoded[4];
        encoded[0] = alphabet[first >> 2];
        encoded[1] = alphabet[((first & 3U) << 4) | (second >> 4)];
        encoded[2] = index + 1 < count ? alphabet[((second & 15U) << 2) | (third >> 6)] : '=';
        encoded[3] = index + 2 < count ? alphabet[third & 63U] : '=';
        if (fwrite(encoded, 1, sizeof(encoded), record) != sizeof(encoded)) { free(utf8); return 0; }
    }
    free(utf8);
    return fputc('\n', record) != EOF;
}

int wmain(void) {
    int argc;
    wchar_t **argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    FILE *record;
    int index;
    int success;
    if (argv == NULL || argc < 4 || wcsncmp(argv[1], L"-javaagent:", 11) != 0 || wcscmp(argv[2], L"--record") != 0) {
        fputs("Wide argument witness requires -javaagent:PATH --record PATH [ARGUMENTS...]\n", stderr);
        if (argv != NULL) LocalFree(argv);
        return 1;
    }
    record = _wfopen(argv[3], L"wb");
    if (record == NULL) { LocalFree(argv); return 1; }
    success = fprintf(record, "LOADONCE_WIDE_ARGV %d\n", argc - 1) >= 0;
    for (index = 1; success && index < argc; index++) success = write_argument(record, argv[index]);
    if (fclose(record) != 0) success = 0;
    LocalFree(argv);
    return success ? 0 : 1;
}
