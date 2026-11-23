package net.kuafuai.andee.shell;

interface IShellService {
    /** Run argv directly (no shell interpreter). Returns a JSON result string. */
    String exec(in String[] cmd, long timeoutMs) = 2;

    /** Service version, so clients can detect a stale process after an app update. */
    int version() = 1;

    /** Reserved Shizuku destroy transaction (use code 16777114 in AIDL). */
    void destroy() = 16777114;
}
