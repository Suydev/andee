package net.kuafuai.andee.shell;

interface IShellService {
    /** Run argv directly (no shell interpreter). Returns a JSON result string. */
    String exec(String[] cmd, long timeoutMs);

    /** Service version, so clients can detect a stale process after an app update. */
    int version();

    /** Reserved Shizuku destroy transaction (use code 16777114 in AIDL). */
    void destroy() = 16777114;
}
