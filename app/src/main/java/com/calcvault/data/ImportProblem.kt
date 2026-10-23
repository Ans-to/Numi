package com.calcvault.data

import android.system.ErrnoException
import android.system.OsConstants
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.security.GeneralSecurityException

/** Safe, actionable messages; never render a provider exception containing paths or personal data. */
enum class ImportProblem(val help: String) {
    ACCESS("Read permission was denied or expired. Select the file again in the system picker."),
    UNAVAILABLE("The source file is unavailable. If it is in cloud storage, download it on the device first and select it again."),
    SPACE("Not enough free device storage. Free some space, then retry. Do not clear this app's data."),
    INTERRUPTED("Import was interrupted or the vault locked. Unlock and retry while keeping the app open."),
    READ("The file provider stopped responding or could not supply the file. Try selecting a local copy with Documents & other files."),
    VERIFY("The encrypted copy failed verification. It was not added. Retry with an intact source file."),
    WRITE("Private storage could not be written. Check free storage and restart the app, then retry."),
    COMMIT("The vault index could not be updated. Keep the original and do not clear app data; check free storage before retrying.")
}
class VaultImportException(val problem: ImportProblem, cause: Throwable? = null) : IOException(problem.help, cause)
internal fun importProblem(error: Exception, fallback: ImportProblem): ImportProblem {
    if (error is VaultImportException) return error.problem
    val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
    return when {
        causes.any { it is SecurityException } -> ImportProblem.ACCESS
        causes.any { (it is ErrnoException && it.errno == OsConstants.ENOSPC) || it.message?.contains("ENOSPC") == true || it.message?.contains("No space left", true) == true } -> ImportProblem.SPACE
        causes.any { it is FileNotFoundException } -> ImportProblem.UNAVAILABLE
        causes.any { it is InterruptedIOException } -> ImportProblem.INTERRUPTED
        causes.any { it is GeneralSecurityException } -> ImportProblem.VERIFY
        else -> fallback
    }
}
