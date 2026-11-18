package com.musik.app;

import android.content.Context;
import androidx.media3.common.MediaItem;

/** What the car-side helpers (CarLyrics, Feedback) need from the car player, so they don't have to reach into it. */
public interface CarHost {
    Context context();
    MediaItem item();
    long position();
    long duration();
    boolean playing();
    void refresh();
}
