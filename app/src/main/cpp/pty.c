// JNI PTY bridge. Exposes forkpty() + waitpid() + TIOCSWINSZ + kill() to Kotlin so the
// Helper can run each proot supervisor as its own child attached to a real pseudo-terminal. A PTY
// (not a pipe) is what gives interactive shells for the terminal UI, correct job-control signal
// delivery (Ctrl-C -> SIGINT to the foreground pgrp via the line discipline), and line-accurate
// streaming. bionic has forkpty since API 23; we ship minSdk 26. untrusted_app can open /dev/ptmx
// (this is exactly how Termux's terminal works on targetSdk 28 — so it is proven to be permitted).
//
// CRITICAL: between fork() and execve() the child may call ONLY async-signal-safe functions and NO
// JNI at all (the child is a fork of a multithreaded VM). So every Java string is converted to a C
// string BEFORE forkpty, and the child path does only chdir()/execve()/_exit().

#include <jni.h>
#include <pty.h>
#include <unistd.h>
#include <stddef.h>
#include <stdio.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/utsname.h>
#include <sys/wait.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/system_properties.h>

#ifndef __NR_close_range
#define __NR_close_range 436 // arm64, the only ABI shipped
#endif

// The API level the phone runs, read in the parent before the fork.
static int device_api(void) {
    char v[PROP_VALUE_MAX] = {0};
    return __system_property_get("ro.build.version.sdk", v) > 0 ? atoi(v) : 0;
}

// Whether the running kernel is [major].[minor] or later, from its release string.
static int kernel_at_least(int major, int minor) {
    struct utsname u;
    int ma = 0, mi = 0;
    if (uname(&u) != 0 || sscanf(u.release, "%d.%d", &ma, &mi) != 2) return 0;
    return ma > major || (ma == major && mi >= minor);
}

// close_range arrived in Linux 5.9. An older kernel that answers it carries a backport, and some walk every number up
// to the bound asked for, about four billion for "everything from 3", in one uninterruptible call that runs for minutes
// and ignores SIGKILL. So the upstream call is trusted only from 5.9; the app seccomp filter allows it from API 33 and
// traps it with SIGSYS below.
static int trusted_close_range(int api) {
    return api >= 33 && kernel_at_least(5, 9);
}

// Closes every descriptor above stdio, in the child before execve, so a job never holds the Helper's own: the MCP
// server's listening and request sockets (a job could accept the next request and read its bearer token) and other
// sessions' terminal masters. The VM marks none of them close-on-exec. Without a trusted close_range each descriptor
// is closed with the raw syscall, because libc close() runs fdsan checks that write a tombstone per job at targetSdk 28.
// Only raw syscalls: the child may make async-signal-safe calls alone.
static void close_inherited(int use_close_range, long max_fd) {
    if (use_close_range && syscall(__NR_close_range, 3u, ~0u, 0u) == 0) return;
    for (long fd = 3; fd < max_fd; fd++) syscall(__NR_close, (int) fd);
}

// On a kernel before 5.9 that the app filter lets reach close_range, answers it with ENOSYS for this process and
// everything it starts, as such a kernel should: Python's subprocess and glibc's posix_spawn then list /proc/self/fd
// instead of calling a backport that can run for minutes. Installed on every thread, so any thread's children inherit
// it. Returns 1 when installed, 0 when the kernel needs none, -errno on failure.
JNIEXPORT jint JNICALL
Java_com_nujan_helper_runtime_PtyNative_hideCloseRange(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    if (device_api() < 33 || kernel_at_least(5, 9)) return 0;
    struct sock_filter code[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_close_range, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | (ENOSYS & SECCOMP_RET_DATA)),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog prog = { .len = (unsigned short) (sizeof(code) / sizeof(code[0])), .filter = code };
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) return -errno;
    if (syscall(__NR_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, &prog) != 0) return -errno;
    return 1;
}

// Convert a String[] into a NULL-terminated char** (heap). Caller frees with free_vec.
static char **to_vec(JNIEnv *env, jobjectArray arr) {
    jsize n = (*env)->GetArrayLength(env, arr);
    char **v = (char **) calloc((size_t) n + 1, sizeof(char *));
    if (!v) return NULL;
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, arr, i);
        const char *cs = (*env)->GetStringUTFChars(env, s, NULL);
        v[i] = strdup(cs ? cs : "");
        (*env)->ReleaseStringUTFChars(env, s, cs);
        (*env)->DeleteLocalRef(env, s);
    }
    v[n] = NULL;
    return v;
}

static void free_vec(char **v) {
    if (!v) return;
    for (int i = 0; v[i]; i++) free(v[i]);
    free(v);
}

// startProcess(argv, envp, cwd, cols, rows) -> int[2] {pid, masterFd}. On failure returns {-1,-1}.
JNIEXPORT jintArray JNICALL
Java_com_nujan_helper_runtime_PtyNative_startProcess(
        JNIEnv *env, jobject thiz,
        jobjectArray jargv, jobjectArray jenvp, jstring jcwd, jint cols, jint rows) {
    (void) thiz;

    jintArray result = (*env)->NewIntArray(env, 2);
    jint fail[2] = {-1, -1};

    // All JNI work happens HERE, before the fork.
    char **argv = to_vec(env, jargv);
    char **envp = to_vec(env, jenvp);
    char *cwd = NULL;
    if (jcwd) {
        const char *c = (*env)->GetStringUTFChars(env, jcwd, NULL);
        cwd = strdup(c ? c : "");
        (*env)->ReleaseStringUTFChars(env, jcwd, c);
    }
    if (!argv || !envp) {
        free_vec(argv); free_vec(envp); free(cwd);
        (*env)->SetIntArrayRegion(env, result, 0, 2, fail);
        return result;
    }

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short) cols;
    ws.ws_row = (unsigned short) rows;

    int use_close_range = trusted_close_range(device_api());
    struct rlimit nofile;
    long max_fd = (getrlimit(RLIMIT_NOFILE, &nofile) == 0 && nofile.rlim_cur != RLIM_INFINITY) ? (long) nofile.rlim_cur : 4096;

    int master = -1;
    pid_t pid = forkpty(&master, NULL, NULL, &ws);
    if (pid < 0) {
        free_vec(argv); free_vec(envp); free(cwd);
        (*env)->SetIntArrayRegion(env, result, 0, 2, fail);
        return result;
    }
    if (pid == 0) {
        // Child: forkpty already made us session leader with the slave as controlling tty.
        // Only async-signal-safe calls + execve from here — no JNI, no free().
        if (cwd) { if (chdir(cwd) != 0) { /* stay in inherited cwd */ } }
        close_inherited(use_close_range, max_fd);
        execve(argv[0], argv, envp);
        _exit(127); // execve failed (missing binary / bad loader).
    }

    // Parent.
    free_vec(argv); free_vec(envp); free(cwd);
    jint ok[2] = {(jint) pid, (jint) master};
    (*env)->SetIntArrayRegion(env, result, 0, 2, ok);
    return result;
}

// Resize the terminal (TIOCSWINSZ). Returns 0 on success, -1 on error.
JNIEXPORT jint JNICALL
Java_com_nujan_helper_runtime_PtyNative_setWinSize(
        JNIEnv *env, jobject thiz, jint fd, jint cols, jint rows) {
    (void) env; (void) thiz;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short) cols;
    ws.ws_row = (unsigned short) rows;
    return ioctl(fd, TIOCSWINSZ, &ws);
}

// Block until [pid] exits; return its exit code, or 128+signal if it was killed, or -1 on error.
JNIEXPORT jint JNICALL
Java_com_nujan_helper_runtime_PtyNative_waitFor(
        JNIEnv *env, jobject thiz, jint pid) {
    (void) env; (void) thiz;
    int status = 0;
    pid_t r;
    do { r = waitpid((pid_t) pid, &status, 0); } while (r < 0 && errno == EINTR);
    if (r < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

// Send [sig] to the process group of [pid] (negative pid => whole group). Returns 0 / -1.
JNIEXPORT jint JNICALL
Java_com_nujan_helper_runtime_PtyNative_sendSignal(
        JNIEnv *env, jobject thiz, jint pid, jint sig) {
    (void) env; (void) thiz;
    // Signal the whole process group so children inside proot get it too (Ctrl-C semantics).
    if (kill((pid_t) -pid, sig) == 0) return 0;
    return kill((pid_t) pid, sig);
}
