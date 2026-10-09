package com.termux.terminal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The interface for communication between {@link TerminalSession} and its client. It is used to
 * send callbacks to the client when {@link TerminalSession} changes or for sending other
 * back data to the client like logs.
 */
public interface TerminalSessionClient {

    void onTextChanged(@NonNull TerminalSession changedSession);

    void onTitleChanged(@NonNull TerminalSession changedSession);

    void onSessionFinished(@NonNull TerminalSession finishedSession);

    void onCopyTextToClipboard(@NonNull TerminalSession session, String text);

    void onPasteTextFromClipboard(@Nullable TerminalSession session);

    void onBell(@NonNull TerminalSession session);

    void onColorsChanged(@NonNull TerminalSession session);

    void onTerminalCursorStateChange(boolean state);

    void setTerminalShellPid(@NonNull TerminalSession session, int pid);



    Integer getTerminalCursorStyle();

    /**
     * Identifier reported by the emulator in response to XTVERSION (`CSI > 0 q`).
     * Format: `<name> <version>`, e.g. `"Podroid 1.1.7-debug"`. Apps like Neovim
     * use this to detect terminal capabilities. Default returns null → emulator
     * falls back to a bare name without version.
     */
    default String getTerminalVersionString() {
        return null;
    }



    void logError(String tag, String message);

    void logWarn(String tag, String message);

    void logInfo(String tag, String message);

    void logDebug(String tag, String message);

    void logVerbose(String tag, String message);

    void logStackTraceWithMessage(String tag, String message, Exception e);

    void logStackTrace(String tag, Exception e);

}
