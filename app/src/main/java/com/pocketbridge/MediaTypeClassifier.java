package com.pocketbridge;

import java.util.Locale;

/** Stable media classification when Android's MediaStore MIME value is missing or generic. */
final class MediaTypeClassifier {
    private MediaTypeClassifier() { }

    static String mimeType(String name, String reportedMime) {
        String mime = reportedMime == null ? "" : reportedMime.trim().toLowerCase(Locale.ROOT);
        if (!mime.isEmpty() && !"application/octet-stream".equals(mime) && !mime.startsWith("application/")) return mime;
        switch (extension(name)) {
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "bmp": return "image/bmp";
            case "heic": return "image/heic";
            case "heif": return "image/heif";
            case "avif": return "image/avif";
            case "tif": case "tiff": return "image/tiff";
            case "mp4": return "video/mp4";
            case "m4v": return "video/x-m4v";
            case "mov": return "video/quicktime";
            case "3gp": return "video/3gpp";
            case "3g2": return "video/3gpp2";
            case "mkv": return "video/x-matroska";
            case "webm": return "video/webm";
            case "avi": return "video/x-msvideo";
            case "wmv": return "video/x-ms-wmv";
            case "flv": return "video/x-flv";
            case "ts": case "mts": case "m2ts": return "video/mp2t";
            case "mp3": return "audio/mpeg";
            case "m4a": return "audio/mp4";
            case "aac": return "audio/aac";
            case "ogg": case "opus": return "audio/ogg";
            case "wav": return "audio/wav";
            case "flac": return "audio/flac";
            case "amr": return "audio/amr";
            case "wma": return "audio/x-ms-wma";
            // Documents, so a received file opens in a viewer instead of only downloading.
            case "pdf": return "application/pdf";
            case "txt": case "log": return "text/plain";
            case "csv": return "text/csv";
            case "zip": return "application/zip";
            case "apk": return "application/vnd.android.package-archive";
            case "doc": return "application/msword";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls": return "application/vnd.ms-excel";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt": return "application/vnd.ms-powerpoint";
            case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            default: return mime.isEmpty() ? "application/octet-stream" : mime;
        }
    }

    static String kind(String mime, String name, String fallback) {
        String normalized = mime == null ? "" : mime.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("image/")) return "image";
        if (normalized.startsWith("video/")) return "video";
        if (normalized.startsWith("audio/")) return "audio";
        switch (extension(name)) {
            case "jpg": case "jpeg": case "png": case "gif": case "webp": case "bmp":
            case "heic": case "heif": case "avif": case "tif": case "tiff": return "image";
            case "mp4": case "m4v": case "mov": case "3gp": case "3g2": case "mkv":
            case "webm": case "avi": case "wmv": case "flv": case "ts": case "mts": case "m2ts": return "video";
            case "mp3": case "m4a": case "aac": case "ogg": case "opus": case "wav":
            case "flac": case "amr": case "wma": return "audio";
            default: return fallback;
        }
    }

    private static String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
