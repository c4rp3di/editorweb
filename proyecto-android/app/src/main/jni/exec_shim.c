// libexec-shim: ejecuta un binario descargado por la app, sorteando W^X en Android 10+.
// Compilado como .so, empaquetado en el APK (jniLibs), cargado con
// System.loadLibrary, invocado vía JNI.
//
// Uso: ExecShim.run(File("/data/data/.../files/modules/radare2/bin/r2"), listOf("-v"))
//
// Por qué funciona: el shim vive en el APK (jniLibs), es código ejecutable
// de origen (parte del paquete firmado). El binario descargado se chmodea
// 0500 (read-only + exec) ANTES de invocarlo, por lo que nunca es
// escribible-y-ejecutable a la vez — Android lo permite bajo esa condición.
// Ver docs/ARCHITECTURE.md y la referencia a Termux en el plan (§14).

#include <jni.h>
#include <unistd.h>
#include <errno.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <android/log.h>

#define LOG_TAG "ExecShim"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

JNIEXPORT jint JNICALL
Java_com_carpe_panoptes_nativebridge_ExecShim_nativeRun(
    JNIEnv *env, jclass clazz,
    jstring jPath, jobjectArray jArgs, jstring jWorkDir, jstring jStdoutFile) {

    const char *path = (*env)->GetStringUTFChars(env, jPath, NULL);
    const char *workdir = jWorkDir ? (*env)->GetStringUTFChars(env, jWorkDir, NULL) : NULL;
    const char *stdoutFile = jStdoutFile ? (*env)->GetStringUTFChars(env, jStdoutFile, NULL) : NULL;

    // Asegurar permisos ejecutables de solo lectura (nunca escribible+ejecutable a la vez)
    chmod(path, S_IRUSR | S_IXUSR);

    int argc = (*env)->GetArrayLength(env, jArgs);
    char **argv = malloc((argc + 2) * sizeof(char *));
    argv[0] = strdup(path);
    for (int i = 0; i < argc; i++) {
        jstring jArg = (jstring)(*env)->GetObjectArrayElement(env, jArgs, i);
        const char *cArg = (*env)->GetStringUTFChars(env, jArg, NULL);
        argv[i + 1] = strdup(cArg);
        (*env)->ReleaseStringUTFChars(env, jArg, cArg);
        (*env)->DeleteLocalRef(env, jArg);
    }
    argv[argc + 1] = NULL;

    if (workdir) chdir(workdir);

    pid_t pid = fork();
    if (pid == 0) {
        if (stdoutFile) {
            FILE *out = freopen(stdoutFile, "w", stdout);
            if (!out) {
                LOGE("No se pudo redirigir stdout a %s (errno=%d)", stdoutFile, errno);
                _exit(126);
            }
        }
        execv(path, argv);
        LOGE("execv falló para %s (errno=%d)", path, errno);
        _exit(127);
    } else if (pid < 0) {
        LOGE("fork() falló");
        for (int i = 0; i <= argc; i++) free(argv[i]);
        free(argv);
        (*env)->ReleaseStringUTFChars(env, jPath, path);
        if (workdir) (*env)->ReleaseStringUTFChars(env, jWorkDir, workdir);
        if (stdoutFile) (*env)->ReleaseStringUTFChars(env, jStdoutFile, stdoutFile);
        return -1;
    }

    int status = 0;
    waitpid(pid, &status, 0);

    for (int i = 0; i <= argc; i++) free(argv[i]);
    free(argv);
    (*env)->ReleaseStringUTFChars(env, jPath, path);
    if (workdir) (*env)->ReleaseStringUTFChars(env, jWorkDir, workdir);
    if (stdoutFile) (*env)->ReleaseStringUTFChars(env, jStdoutFile, stdoutFile);

    return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
}
