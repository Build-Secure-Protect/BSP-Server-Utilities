package com.mrgregles.bsp_server_utilities.client;

/** Puts chat's own lines back if the stream picture stopped while chat was drawing its filtered copy (StreamerMode's safety). */
public interface StreamChatAccess {
    void bspSu$restoreLines();
}
