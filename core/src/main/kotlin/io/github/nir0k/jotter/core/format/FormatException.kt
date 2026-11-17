package io.github.nir0k.jotter.core.format

/** What went wrong with a library file, as the desktop's error codes name it. */
enum class ErrorCode {
    /** Reading or writing a file or directory failed. */
    Io,

    /** A file or directory that must exist is missing. */
    NotFound,

    /** A file does not follow the library format. */
    Malformed,

    /** A file was written by a newer version of Jotter. */
    NewerFormat,

    /** A new library was requested in a folder that already holds files. */
    NotEmpty,

    /** A change was requested in a library opened read-only. */
    ReadOnly,

    /** A file changed before it could be replaced; it is left for the next try. */
    Deferred,

    /** A file is larger than an attachment may be (desktop spec section 3.5). */
    TooLarge,

    /** A file is not of the kind asked for, such as a pasted picture that is not an accepted image. */
    Refused,

    /** Another device holds the lock of the sync storage (desktop spec section 12.3). */
    Locked,

    /** The sync storage holds another library (desktop spec section 12.6). */
    OtherLibrary,

    /** The sync storage could not be reached or the connection was lost; worth retrying. */
    Network,

    /** The sync storage refused the sign-in. */
    SignIn,

    /** The sync storage's host key is unknown or changed and must be confirmed by the person. */
    HostKey,
}

/**
 * FormatException reports a library file that cannot be read or written, or a sync storage that
 * cannot be used, with the desktop's error codes.
 *
 * @param code What went wrong.
 * @param path The file or folder concerned.
 * @param detail An English description for logs and the problems list; empty when the code says
 *   everything.
 */
open class FormatException(
    val code: ErrorCode,
    val path: String,
    val detail: String = "",
    cause: Throwable? = null,
) : Exception(if (detail.isEmpty()) "$code: $path" else "$code: $path: $detail", cause)
