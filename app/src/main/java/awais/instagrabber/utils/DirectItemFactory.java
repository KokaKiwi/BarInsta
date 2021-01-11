package awais.instagrabber.utils;

import android.net.Uri;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import awais.instagrabber.models.enums.DirectItemType;
import awais.instagrabber.models.enums.MediaItemType;
import awais.instagrabber.repositories.responses.Audio;
import awais.instagrabber.repositories.responses.ImageVersions2;
import awais.instagrabber.repositories.responses.Media;
import awais.instagrabber.repositories.responses.MediaCandidate;
import awais.instagrabber.repositories.responses.VideoVersion;
import awais.instagrabber.repositories.responses.directmessages.DirectItem;
import awais.instagrabber.repositories.responses.directmessages.DirectItemVoiceMedia;

public class DirectItemFactory {

    public static DirectItem createText(final long userId,
                                        final String clientContext,
                                        final String text) {
        return new DirectItem(
                UUID.randomUUID().toString(),
                userId,
                System.currentTimeMillis() * 1000,
                DirectItemType.TEXT,
                text,
                null,
                null,
                clientContext,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0
        );
    }

    public static DirectItem createImageOrVideo(final long userId,
                                                final String clientContext,
                                                final Uri uri,
                                                final int width,
                                                final int height,
                                                final boolean isVideo) {
        final ImageVersions2 imageVersions2 = new ImageVersions2(Collections.singletonList(new MediaCandidate(width, height, uri.toString())));
        List<VideoVersion> videoVersions = null;
        if (isVideo) {
            final VideoVersion videoVersion = new VideoVersion(
                    null,
                    null,
                    width,
                    height,
                    uri.toString()
            );
            videoVersions = Collections.singletonList(videoVersion);
        }
        final Media media = new Media(
                null,
                UUID.randomUUID().toString(),
                null,
                -1,
                null,
                false,
                imageVersions2,
                width,
                height,
                isVideo ? MediaItemType.MEDIA_TYPE_VIDEO : MediaItemType.MEDIA_TYPE_IMAGE,
                false,
                false,
                -1,
                -1,
                -1,
                false,
                false,
                videoVersions,
                false,
                0f,
                0,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                null,
                null);
        return new DirectItem(
                UUID.randomUUID().toString(),
                userId,
                System.currentTimeMillis() * 1000,
                DirectItemType.MEDIA,
                null,
                null,
                null,
                clientContext,
                null,
                null,
                null,
                null,
                null,
                media,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0
        );
    }

    public static DirectItem createVoice(final long userId,
                                         final String clientContext,
                                         final Uri uri,
                                         final long duration,
                                         final List<Float> waveform,
                                         final int samplingFreq) {
        final Audio audio = new Audio(
                uri.toString(),
                duration,
                waveform,
                samplingFreq,
                0
        );
        final Media media = new Media(
                null,
                UUID.randomUUID().toString(),
                null,
                -1,
                null,
                false,
                null,
                0,
                0,
                MediaItemType.MEDIA_TYPE_VOICE,
                false,
                false,
                -1,
                0,
                0,
                false,
                false,
                null,
                false,
                0f,
                0,
                null,
                false,
                audio,
                null,
                null,
                null,
                null,
                false,
                false,
                null,
                null);
        final DirectItemVoiceMedia voiceMedia = new DirectItemVoiceMedia(
                media,
                0,
                "permanent"
        );
        return new DirectItem(
                UUID.randomUUID().toString(),
                userId,
                System.currentTimeMillis() * 1000,
                DirectItemType.VOICE_MEDIA,
                null,
                null,
                null,
                clientContext,
                null,
                null,
                null,
                null,
                null,
                media,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                voiceMedia,
                null,
                0
        );
    }
}
