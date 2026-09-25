/*
 * wxshim — LD_PRELOAD shim for bionic libc (target SDK 35, Android 10+).
 *
 * Android 10+ enforces W^X on app data directories (SELinux execute_no_trans),
 * so execve() on an ELF that lives in the app's data dir fails with EACCES.
 * This shim intercepts the exec* family and, on EACCES, re-executes the target
 * by handing it to the dynamic linker directly:
 *     execve("/system/bin/linker[64]", {linker, target, argv[1..]}, envp)
 * Loading via the linker is allowed (execute via mapping, not execve
 * transition). #! scripts are unpacked: the interpreter (and its optional
 * single argument) is prepended the same way.
 *
 * Build (NDK clang, API 26+):
 *   <triple>26-clang -shared -fPIC -O2 -o libwxshim.so wxshim.c
 * Activate:  LD_PRELOAD=libwxshim.so  (put libwxshim.so in jniLibs and
 * load via the app's normal native lib mechanism)
 */

#define _GNU_SOURCE
#include <unistd.h>
#include <errno.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <limits.h>
#include <spawn.h>
#include <stdarg.h>
#include <stddef.h>

#define WX_API_VISIBLE __attribute__((visibility("default")))

#if defined(__aarch64__) || defined(__x86_64__)
#define LINKER "/system/bin/linker64"
#else
#define LINKER "/system/bin/linker"
#endif

#define MAX_ARGS 1024
#define MAX_PATH_LEN PATH_MAX

/* Raw syscall so we never re-enter our own hooks (and libc's execve wrapper
 * is not involved). bionic's syscall() sets errno and returns -1 on failure. */
static long raw_execve(const char *path, char *const argv[], char *const envp[]) {
    return syscall(SYS_execve, path, argv, envp);
}

/* Build the linker-mediated argv on the stack only (safe between fork and
 * exec where malloc may deadlock). Returns 1 on success (fills nv), 0 if
 * the target is not an ELF/script we can rewrite. */
static int build_linker_argv(const char *target, char *const argv[],
                             char **nv, size_t nv_cap) {
    unsigned char magic[5];
    int fd = open(target, O_RDONLY | O_CLOEXEC);
    if (fd < 0)
        return 0;
    ssize_t n = read(fd, magic, sizeof(magic));
    if (n < (ssize_t)sizeof(magic)) {
        close(fd);
        return 0;
    }

    char *av1 = NULL; /* optional interp arg for #! */
    char *av2 = NULL; /* interp for #! */
    char interp[256];
    char arg1[128];
    int is_script = 0;

    if (memcmp(magic, "\177" "ELF", 4) == 0) {
        close(fd);
    } else if (magic[0] == '#' && magic[1] == '!') {
        /* Parse "#!interp[ arg]" from the first line. */
        char line[512];
        if (lseek(fd, 0, SEEK_SET) < 0) { close(fd); return 0; }
        ssize_t ln = read(fd, line, sizeof(line) - 1);
        close(fd);
        if (ln <= 0)
            return 0;
        line[ln] = '\0';
        char *nl = strpbrk(line, "\r\n");
        if (nl)
            *nl = '\0';
        char *p = line + 2;
        while (*p == ' ' || *p == '\t')
            p++;
        char *end = strpbrk(p, " \t");
        size_t ilen = end ? (size_t)(end - p) : strlen(p);
        if (ilen == 0 || ilen >= sizeof(interp))
            return 0;
        memcpy(interp, p, ilen);
        interp[ilen] = '\0';
        p += ilen;
        while (*p == ' ' || *p == '\t')
            p++;
        if (*p) { /* optional single interpreter argument */
            end = strpbrk(p, " \t");
            size_t alen = end ? (size_t)(end - p) : strlen(p);
            if (alen >= sizeof(arg1))
                alen = sizeof(arg1) - 1;
            memcpy(arg1, p, alen);
            arg1[alen] = '\0';
            av1 = arg1;
        }
        av2 = interp;
        is_script = 1;
    } else {
        close(fd);
        return 0;
    }

    /* {linker, target, [arg,] interp, argv[1...]} */
    size_t k = 0;
    nv[k++] = LINKER;
    nv[k++] = (char *)target;
    if (is_script) {
        if (av1)
            nv[k++] = av1;
        nv[k++] = av2;
    }
    /* append original argv[1..] */
    if (argv) {
        for (int i = 1; argv[i] != NULL; i++) {
            if (k + 1 >= nv_cap)
                return 0;
            nv[k++] = argv[i];
        }
    }
    nv[k] = NULL;
    return 1;
}

/* Core execve for all entry points. Tries a direct exec first and, on
 * EACCES, re-executes through the dynamic linker. */
static int do_execve(const char *path, char *const argv[], char *const envp[]) {
    static _Thread_local char *nv[MAX_ARGS];
    long rc = raw_execve(path, argv, envp);
    if (rc == -1 && errno == EACCES) {
        if (build_linker_argv(path, argv, nv, MAX_ARGS)) {
            rc = raw_execve(LINKER, nv, envp);
            if (rc != -1)
                return 0;
            /* If the linker re-exec also hit EACCES keep the original one. */
            if (errno != EACCES)
                return -1;
        }
        errno = EACCES;
    }
    return (int)rc;
}

/* ------------------------------------------------------------------ */
/* execve family                                                       */
/* ------------------------------------------------------------------ */

WX_API_VISIBLE int execve(const char *path, char *const argv[], char *const envp[]) {
    return do_execve(path, argv, envp);
}

WX_API_VISIBLE int execveat(int dirfd, const char *path,
                            char *const argv[], char *const envp[], int flags) {
    char buf[MAX_PATH_LEN];
    const char *full = NULL;

    if (path && path[0] == '/') {
        full = path;
    } else if ((flags & AT_EMPTY_PATH) && (path == NULL || path[0] == '\0')) {
        char link[MAX_PATH_LEN];
        snprintf(link, sizeof(link), "/proc/self/fd/%d", dirfd);
        ssize_t n = readlink(link, buf, sizeof(buf) - 1);
        if (n > 0) {
            buf[n] = '\0';
            full = buf;
        } else {
            errno = ENOENT;
            return -1;
        }
    } else if (path && path[0] != '\0') {
        if (dirfd == AT_FDCWD) {
            full = path;
        } else {
            snprintf(buf, sizeof(buf), "/proc/self/fd/%d/%s", dirfd, path);
            full = buf;
        }
    } else {
        errno = ENOENT;
        return -1;
    }
    return do_execve(full, argv, envp);
}

WX_API_VISIBLE int execv(const char *path, char *const argv[]) {
    extern char **environ;
    return do_execve(path, argv, environ);
}

/* PATH search used by execvp/execlp. Paths containing '/' are exec'd
 * directly; bare names are looked up on PATH. Never calls malloc. */
static int execvp_search(const char *path, char *const argv[], char *const envp[]) {
    if (!path)
        return (errno = ENOENT), -1;
    if (path[0] == '/' || strchr(path, '/'))
        return do_execve(path, argv, envp);

    const char *ppath = getenv("PATH");
    if (!ppath)
        ppath = "/bin:/usr/bin";

    const char *p = ppath;
    int tried = 0;
    while (1) {
        const char *colon = strchr(p, ':');
        size_t dlen = colon ? (size_t)(colon - p) : strlen(p);
        char cand[MAX_PATH_LEN];
        if (dlen == 0) {
            /* empty PATH entry means cwd */
            if (snprintf(cand, sizeof(cand), "./%s", path) < (int)sizeof(cand)) {
                tried = 1;
                if (access(cand, X_OK) == 0 && do_execve(cand, argv, envp) == 0)
                    return 0;
            }
        } else if (dlen < sizeof(cand) - strlen(path) - 1) {
            snprintf(cand, sizeof(cand), "%.*s/%s", (int)dlen, p, path);
            tried = 1;
            if (access(cand, X_OK) == 0 && do_execve(cand, argv, envp) == 0)
                return 0;
        }
        if (!colon)
            break;
        p = colon + 1;
    }
    (void)tried;
    errno = ENOENT;
    return -1;
}

WX_API_VISIBLE int execvp(const char *path, char *const argv[]) {
    extern char **environ;
    return execvp_search(path, argv, environ);
}

WX_API_VISIBLE int execvpe(const char *file, char *const argv[], char *const envp[]) {
    return execvp_search(file, argv, envp);
}

/* execl-style: collect varargs into a stack array, then delegate to
 * execve/execvp so everything funnels through do_execve. */
static int execl_varargs(const char *path, const char *arg, va_list ap,
                         int use_path, char **envp) {
    extern char **environ;
    static _Thread_local char *av[MAX_ARGS];
    size_t k = 0;
    if (arg) {
        av[k++] = (char *)arg;
        char *a;
        while (k + 1 < MAX_ARGS && (a = va_arg(ap, char *)) != NULL)
            av[k++] = a;
    }
    av[k] = NULL;
    if (!envp)
        envp = environ;
    if (use_path)
        return execvp_search(path, av, envp);
    return do_execve(path, av, envp);
}

WX_API_VISIBLE int execl(const char *path, const char *arg, ...) {
    va_list ap;
    va_start(ap, arg);
    int rc = execl_varargs(path, arg, ap, 0, NULL);
    va_end(ap);
    return rc;
}

WX_API_VISIBLE int execlp(const char *file, const char *arg, ...) {
    va_list ap;
    va_start(ap, arg);
    int rc = execl_varargs(file, arg, ap, 1, NULL);
    va_end(ap);
    return rc;
}

WX_API_VISIBLE int execlpe(const char *file, const char *arg, ...) {
    /* Layout: argv items terminated by a NULL pointer, then char *const *envp
     * as the final vararg (glibc/musl convention). */
    va_list ap;
    va_start(ap, arg);
    static _Thread_local char *av[MAX_ARGS];
    size_t k = 0;
    if (arg)
        av[k++] = (char *)arg;
    char *p;
    while (k + 1 < MAX_ARGS && (p = va_arg(ap, char *)) != NULL)
        av[k++] = p;
    av[k] = NULL;
    char **envp = va_arg(ap, char **);
    va_end(ap);
    return execvp_search(file, av, envp);
}

/* ------------------------------------------------------------------ */
/* posix_spawn family                                                  */
/* ------------------------------------------------------------------ */

typedef int (*spawn_fn)(pid_t *, const char *,
                        const posix_spawn_file_actions_t *,
                        const posix_spawnattr_t *,
                        char *const argv[], char *const envp[]);

/* fork()+execve() fallback: the child runs our do_execve(), which already
 * includes the linker retry, so W^X-blocked targets succeed here. If even
 * that fails the child exits 126 so waiters see an "exec blocked" status.
 * Malloc is acceptable in the fallback child (standard fork+exec rules,
 * and we only use stack buffers inside do_execve anyway). */
static int spawn_fallback(pid_t *pid, const char *path,
                          char *const argv[], char *const envp[]) {
    pid_t child = fork();
    if (child < 0)
        return errno;
    if (child == 0) {
        do_execve(path, argv, envp);
        _exit(126);
    }
    *pid = child;
    return 0;
}

static int spawn_common(spawn_fn real_spawn, const char *sym, pid_t *pid,
                        const char *path,
                        const posix_spawn_file_actions_t *fa,
                        const posix_spawnattr_t *at,
                        char *const argv[], char *const envp[]) {
    if (!real_spawn)
        real_spawn = (spawn_fn)dlsym(RTLD_NEXT, sym);

    if (real_spawn) {
        int rc = real_spawn(pid, path, fa, at, argv, envp);
        if (rc == EACCES || rc == ENOEXEC) {
            /* Spawn failed up-front on the exec permission check. */
            int rc2 = spawn_fallback(pid, path, argv, envp);
            return rc2 ? rc : rc2;
        }
        if (rc != 0)
            return rc;
        /*
         * Spawn succeeded. Note: reaping here is the price of the literal
         * "check child status for 126/2" strategy used by this shim; the
         * caller's subsequent waitpid may return ECHILD for an already
         * finished child. For launcher-style fire-and-forget execs this is
         * acceptable and keeps the fallback logic simple.
         */
        int status;
        pid_t r = waitpid(*pid, &status, 0);
        if (r == *pid && WIFEXITED(status) &&
            (WEXITSTATUS(status) == 126 || WEXITSTATUS(status) == 2)) {
            /* Child could not exec due to W^X: retry through our fork +
             * linker execve path. */
            return spawn_fallback(pid, path, argv, envp);
        }
        return 0;
    }
    return spawn_fallback(pid, path, argv, envp);
}

WX_API_VISIBLE int posix_spawn(pid_t *pid, const char *path,
                               const posix_spawn_file_actions_t *fa,
                               const posix_spawnattr_t *at,
                               char *const argv[], char *const envp[]) {
    return spawn_common(NULL, "posix_spawn", pid, path, fa, at, argv, envp);
}

WX_API_VISIBLE int posix_spawnp(pid_t *pid, const char *file,
                                const posix_spawn_file_actions_t *fa,
                                const posix_spawnattr_t *at,
                                char *const argv[], char *const envp[]) {
    /* Resolve via PATH, then run the same logic (do_execve handles the
     * direct-then-linker retry inside our fallback child). */
    if (file && file[0] != '/' && !strchr(file, '/')) {
        const char *ppath = getenv("PATH");
        if (!ppath)
            ppath = ":/bin:/usr/bin";
        char cand[MAX_PATH_LEN];
        const char *p = ppath;
        while (1) {
            const char *colon = strchr(p, ':');
            size_t dlen = colon ? (size_t)(colon - p) : strlen(p);
            snprintf(cand, sizeof(cand), "%.*s/%s", (int)dlen, p, file);
            if (dlen > 0 && access(cand, X_OK) == 0)
                return spawn_common(NULL, "posix_spawn", pid, cand, fa, at, argv, envp);
            if (!colon)
                break;
            p = colon + 1;
        }
    }
    return spawn_common(NULL, "posix_spawn", pid, file, fa, at, argv, envp);
}
