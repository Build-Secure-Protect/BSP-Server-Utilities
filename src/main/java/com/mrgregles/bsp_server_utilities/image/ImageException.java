package com.mrgregles.bsp_server_utilities.image;

/** Why a picture could not be loaded: a short key (shown to admins through the language file) and a detail. */
public class ImageException extends Exception {
    private final String key, detail;

    public ImageException(String key, String detail) {
        super(key + ": " + detail);
        this.key = key;
        this.detail = detail;
    }

    public String key() {
        return key;
    }

    public String detail() {
        return detail;
    }
}
