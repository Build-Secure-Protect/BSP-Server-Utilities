package com.mrgregles.bsp_server_utilities.tutorial;

import org.jetbrains.annotations.Nullable;

/** A tutorial's two copies (owner decision D734): the admins' draft, and the published one players follow. */
public enum TutorialStage {
    DRAFT("draft"), PUBLISHED("published");

    /** The name in the shared table and in notices. */
    public final String key;

    TutorialStage(String key) {
        this.key = key;
    }

    @Nullable
    public static TutorialStage of(String key) {
        for (TutorialStage s : values()) {
            if (s.key.equals(key)) {
                return s;
            }
        }
        return null;
    }
}
