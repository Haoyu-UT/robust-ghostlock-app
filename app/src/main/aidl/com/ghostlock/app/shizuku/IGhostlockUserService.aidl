package com.ghostlock.app.shizuku;

import android.os.ParcelFileDescriptor;
import com.ghostlock.app.shizuku.IGhostlockCallback;
import com.ghostlock.app.shizuku.IGhostlockStatusCallback;

interface IGhostlockUserService {
    void destroy() = 16777114;
    void runExploit(int primaryCpu, int consumerCpu, boolean safeMode, boolean forceAttack, boolean anchorTrusted, in byte[] profileBlob, @nullable String debugDir, @nullable String managerPackage, IGhostlockCallback callback, IGhostlockStatusCallback statusCallback) = 2;

    /* Crash evidence: the shell can read the boot-reason properties and the
     * pstore nodes that an app cannot. The pstore names are a fixed allowlist
     * inside the service; the app only ever asks for those. */
    String[] readBootFacts() = 3;
    @nullable ParcelFileDescriptor openPstore(String name) = 4;

    /* The previous boot's kernel console as system_server saved it in DropBox
     * (SYSTEM_LAST_KMSG). DropBox is readable by shell where pstore is not.
     * Writes the entry text into `sink`; returns the bytes written, 0 when the
     * tag exists but the entry is empty, and -1 when the tag is absent. */
    long writeDropboxLastKmsg(in ParcelFileDescriptor sink) = 5;
}
